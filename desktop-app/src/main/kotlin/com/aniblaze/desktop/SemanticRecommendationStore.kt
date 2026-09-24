package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

@Serializable
internal data class CachedSemantic(val profile: SemanticProfile, val at: Long)
@Serializable
internal data class CachedRecommendationMeta(val card: PersistedAnime?, val at: Long)
@Serializable
internal data class SemanticCacheState(
    val profiles: Map<String, CachedSemantic> = emptyMap(),
    val metadata: Map<String, CachedRecommendationMeta> = emptyMap(),
    val retryAfter: Map<String, Long> = emptyMap(),
    val budgetDay: Long = -1,
    val requests: Int = 0,
    val apiRetryAfter: Long = 0,
)

/** One repository-owned store, one single-flight pipeline, atomic disk cache.
 * No requests from composition, poster rendering or progress-save subscriptions. */
internal class SemanticRecommendationStore(
    directory: File,
    private val extractor: SemanticExtractor? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val loadMetadata: (suspend (Anime) -> Anime?)? = null,
) {
    private val file = File(directory, "semantic-profiles-v1.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    @Volatile private var loaded = false
    @Volatile private var cache = SemanticCacheState()
    var lastStatus: String = "Локальный подбор"
        private set
    companion object {
        const val BATCH_SIZE = 8
        const val MAX_REQUESTS_PER_PASS = 20
        const val MAX_REQUESTS_PER_DAY = 24
        const val ANCHOR_LIMIT = 32
        private const val DAY = 86_400_000L
    }
    private fun load() {
        if (loaded) return
        loaded = true
        cache = runCatching {
            if (file.isFile && file.length() <= 16 * 1024 * 1024) json.decodeFromString<SemanticCacheState>(file.readText())
            else SemanticCacheState()
        }.getOrDefault(SemanticCacheState())
    }
    private fun persist() {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(cache))
        try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun enriched(state: PersistedState, snapshot: SemanticCacheState = cache): PersistedState {
        fun patch(a: PersistedAnime): PersistedAnime {
            val m = snapshot.metadata[a.id]?.card ?: return a
            return a.copy(description = m.description.ifBlank { a.description }, genres = m.genres.ifBlank { a.genres },
                studio = m.studio.ifBlank { a.studio }, contentType = m.contentType.ifBlank { a.contentType },
                country = m.country.ifBlank { a.country }, episodesTotal = m.episodesTotal.takeIf { it > 0 } ?: a.episodesTotal,
                episodesAvailable = maxOf(m.episodesAvailable, a.episodesAvailable),
                airingStatus = m.airingStatus.takeIf { it > 0 } ?: a.airingStatus)
        }
        return state.copy(ratings = state.ratings.map { it.copy(anime = patch(it.anime)) },
            history = state.history.map(::patch), favorites = state.favorites.map(::patch),
            progress = state.progress.map { it.copy(anime = patch(it.anime)) },
            favoriteRemovals = state.favoriteRemovals.map { it.copy(anime = patch(it.anime)) })
    }

    data class Result(val taste: Recommender.Taste, val items: List<Recommender.Recommendation>)

    private fun cachedResult(saved: PersistedState, pool: List<Anime>, snapshot: SemanticCacheState): Result {
        val taste = Recommender.buildTaste(enriched(saved, snapshot), now())
        val relevant = taste.sources.map { it.anime } + taste.negativeSources.map { it.anime } +
            HybridRecommender.candidates(taste, pool).map { it.anime }
        val profiles = relevant.mapNotNull { a -> snapshot.profiles[AnimeSemantics.cacheKey(a)]?.let { a.id to it.profile } }.toMap()
        return Result(taste, HybridRecommender.recommend(taste, pool, profiles, limit = pool.size + 1))
    }

    /** Read-only warm ranking first, optional bounded enrichment second. */
    suspend fun rank(saved: PersistedState, pool: List<Anime>, online: Boolean): Result = withContext(Dispatchers.IO) {
        // Pagination/new windows can read the immutable snapshot while an API call
        // is in flight. A long extraction must not block the cheap first paint.
        if (!online && loaded) return@withContext cachedResult(saved, pool, cache)
        mutex.withLock {
            load()
            if (online) hydrateMetadata(saved)
            val taste = Recommender.buildTaste(enriched(saved), now())
            val shortlist = HybridRecommender.candidates(taste, pool).map { it.anime }
            val anchors = (taste.sources.filter { (it.rating ?: 0) >= 4 } + taste.negativeSources +
                taste.sources.filter { it.signal == Recommender.HistorySignal.WATCHED } + taste.sources)
                .distinctBy { it.anime.id }.take(ANCHOR_LIMIT).map { it.anime }
            val required = (anchors + shortlist).distinctBy { it.id }
            if (online && !taste.isEmpty && anchors.isNotEmpty()) analyse(required)
            cachedResult(saved, pool, cache)
        }
    }

    private suspend fun hydrateMetadata(saved: PersistedState) {
        val loader = loadMetadata ?: return
        val watchedIds = saved.watched.mapTo(HashSet()) { it.substringBeforeLast('#') }
        // Public metadata, not AI. Migrate only relevant user cards; never scrape a catalog.
        val cards = (saved.ratings.sortedByDescending { it.score }.map { it.anime } +
            saved.history.filter { it.id in watchedIds } + saved.favorites)
            .filter { isAnimeRecommendationId(it.id) }.distinctBy { it.id }.filter { card ->
                val old = cache.metadata[card.id]
                val ttl = if (old?.card?.airingStatus == 1) 30 * DAY else DAY
                val needs = card.airingStatus != 1 || card.episodesTotal == 0 || card.description.length < 80
                needs && (old == null || now() - old.at >= ttl)
            }.take(24)
        for (card in cards) {
            coroutineContext.ensureActive()
            val fresh = try { loader(card.toAnime())?.let { it.copy(description = it.description.take(3000)) }
                ?.toPersisted()?.copy(id = card.id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            cache = cache.copy(metadata = (cache.metadata + (card.id to CachedRecommendationMeta(fresh, now())))
                .entries.sortedByDescending { it.value.at }.take(256).associate { it.toPair() })
            persist()
        }
    }

    private suspend fun analyse(required: List<Anime>) {
        val api = extractor ?: return
        if (now() < cache.apiRetryAfter) { lastStatus = "AI временно недоступен · локальный подбор"; return }
        var calls = 0
        // This finite set is captured from THIS candidate pool. No background catalog crawl.
        val missing = required.filter { it.description.length >= 40 && AnimeSemantics.cacheKey(it) !in cache.profiles }
        for (chunk in missing.chunked(BATCH_SIZE)) {
            coroutineContext.ensureActive()
            val batch = chunk.filter { a ->
                val key = AnimeSemantics.cacheKey(a)
                key !in cache.profiles && now() >= (cache.retryAfter[key] ?: 0)
            }
            if (batch.isEmpty()) continue
            val day = now() / DAY
            if (cache.budgetDay != day) cache = cache.copy(budgetDay = day, requests = 0)
            if (calls >= MAX_REQUESTS_PER_PASS || cache.requests >= MAX_REQUESTS_PER_DAY) {
                lastStatus = "Лимит AI на сегодня · кеш и локальный подбор"; break
            }
            // Reserve before dispatch: restarts/cancellation cannot reset the spending budget.
            cache = cache.copy(requests = cache.requests + 1,
                retryAfter = cache.retryAfter.filterValues { it > now() } +
                    batch.associate { AnimeSemantics.cacheKey(it) to now() + 30 * 60_000L })
            persist()
            calls++
            try {
                val result = api.extract(batch)
                require(result.size == batch.size)
                val updated = cache.profiles.toMutableMap()
                batch.zip(result).forEach { (anime, profile) ->
                    updated[AnimeSemantics.cacheKey(anime)] = CachedSemantic(profile.sanitized(), now())
                }
                cache = cache.copy(profiles = updated.entries.sortedByDescending { it.value.at }.take(1024)
                    .associate { it.toPair() }, retryAfter = cache.retryAfter - batch.map { AnimeSemantics.cacheKey(it) })
                persist()
                lastStatus = "Смысловой профиль готов"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // Never log response bodies, headers or exception messages with credentials.
                cache = cache.copy(apiRetryAfter = now() + 30 * 60_000L)
                persist()
                lastStatus = "AI временно недоступен · локальный подбор"
                break
            }
        }
    }
}

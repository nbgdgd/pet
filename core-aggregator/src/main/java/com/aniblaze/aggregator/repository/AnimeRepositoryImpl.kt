package com.aniblaze.aggregator.repository

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.ContentResolver
import com.aniblaze.aggregator.source.KinogoSource
import com.aniblaze.aggregator.source.LampaCatalogSource
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.StreamMode
import com.aniblaze.aggregator.search.searchTitles
import com.aniblaze.aggregator.search.toLatinLayout
import com.aniblaze.aggregator.search.toRussianLayout
import com.aniblaze.database.dao.ContentDao
import com.aniblaze.database.dao.FavoriteDao
import com.aniblaze.database.dao.HistoryDao
import com.aniblaze.database.dao.SegmentDao
import com.aniblaze.database.dao.WatchProgressDao
import com.aniblaze.database.entity.ContentEntity
import com.aniblaze.database.entity.FavoriteEntity
import com.aniblaze.database.entity.HistoryEntity
import com.aniblaze.database.entity.SegmentEntity
import com.aniblaze.database.entity.WatchProgressEntity
import com.aniblaze.database.settings.SettingsDataStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeRepositoryImpl @Inject constructor(
    private val aggregators: Set<@JvmSuppressWildcards ContentAggregator>,
    private val resolver: ContentResolver,
    private val airDates: com.aniblaze.aggregator.source.EpisodeAirDates,
    private val aniskip: com.aniblaze.aggregator.source.AniskipTimings,
    private val settings: SettingsDataStore,
    private val contentDao: ContentDao,
    private val segmentDao: SegmentDao,
    private val watchProgressDao: WatchProgressDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
) : AnimeRepository {

    private suspend fun activeSources(): List<ContentAggregator> {
        val enabled = settings.settings.first().enabledSources
        return aggregators.filter { it.name in enabled || it.alwaysActive }
            .ifEmpty { aggregators.toList() }
    }

    private val kinogo: KinogoSource?
        get() = aggregators.filterIsInstance<KinogoSource>().firstOrNull()

    private val lampaCinema: LampaCatalogSource?
        get() = aggregators.filterIsInstance<LampaCatalogSource>().firstOrNull()

    override suspend fun cinemaBrowse(kind: String, page: Int): List<Anime> {
        val list = lampaCinema?.browse(kind, page).orEmpty().ifEmpty {
            val fallbackKind = runCatching { KinogoSource.Kind.valueOf(kind) }
                .getOrDefault(KinogoSource.Kind.FILM)
            kinogo?.browse(fallbackKind, page).orEmpty()
        }
        if (list.isNotEmpty()) contentDao.upsertAll(list.map { it.toEntity(now()) })
        return list
    }

    override suspend fun cinemaSearch(query: String): List<Anime> {
        val list = lampaCinema?.searchCinema(query).orEmpty().ifEmpty {
            kinogo?.searchCinema(query).orEmpty()
        }
        if (list.isNotEmpty()) contentDao.upsertAll(list.map { it.toEntity(now()) })
        return list
    }

    override suspend fun cinemaEmbed(pageUrl: String): String? = kinogo?.embedUrlFor(pageUrl)

    /**
     * Поиск по названию.
     *
     * Источники ищут ПОДСТРОКОЙ и ничем больше: одна опечатка — и выдача пустая. Поверх
     * их ответа работает [searchTitles] — он терпит опечатки, регистр, ё/е, дефисы и
     * несколько названий у одного тайтла (см. пакет `search`).
     *
     * Второй запрос с исправленной раскладкой уходит ТОЛЬКО когда первый вернул мало.
     * Иначе на каждое нажатие клавиши на телефоне уходило бы вдвое больше запросов, а
     * латинские названия у источников настоящие: «bleach» надо искать как «bleach», а
     * не как «идуфср», и терять честную выдачу ради догадки нельзя.
     */
    override suspend fun search(query: String): List<Anime> = coroutineScope {
        Timber.d("[Repo] search query='%s'", query)
        val sources = activeSources()

        suspend fun ask(q: String): List<Anime> = sources
            .map { src -> async {
                val result = runCatching { src.search(q) }.getOrDefault(emptyList())
                Timber.d("[Repo] search source='%s' q='%s' returned %d results", src.name, q, result.size)
                result
            } }
            .awaitAll()
            .flatten()

        val direct = ask(query)
        val pool = if (direct.size < LAYOUT_RETRY_BELOW) {
            val flipped = listOfNotNull(
                toRussianLayout(query.lowercase()),
                toLatinLayout(query.lowercase()),
            ).filter { it.isNotBlank() }
            if (flipped.isEmpty()) direct else direct + flipped.flatMap { ask(it) }
        } else {
            direct
        }

        val merged = pool
            .let(::dedupeTitles)
            // Oldest → newest so franchise seasons read in order; unknown years last.
            .sortedWith(compareBy({ it.year == 0 }, { it.year }))

        // Ранжирование по названию. Если оно не нашло НИЧЕГО (источник ответил на
        // запрос чем-то, что с названием не пересекается вовсе), отдаём то, что
        // пришло: пустая выдача при непустом ответе источника — это хуже, чем
        // неотсортированная.
        val ranked = searchTitles(query, merged).map { it.anime }
        val result = ranked.ifEmpty { merged }

        Timber.d("[Repo] search total=%d unique, ranked=%d", merged.size, ranked.size)
        if (result.isNotEmpty()) {
            contentDao.upsertAll(result.map { it.toEntity(now()) })
        } else {
            Timber.w("[Repo] search returned empty for '%s'", query)
        }
        result
    }

    override fun trending(limit: Int): Flow<List<Anime>> =
        contentDao.trending(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun refreshTrending() = coroutineScope {
        Timber.d("[Repo] refreshTrending start")
        val sources = activeSources()
        val merged = sources
            .map { src -> async {
                val result = runCatching { src.trending() }.getOrDefault(emptyList())
                Timber.d("[Repo] trending source='%s' returned %d items", src.name, result.size)
                result
            } }
            .awaitAll()
            .flatten()
            .distinctBy { it.id }
        Timber.d("[Repo] trending total=%d unique", merged.size)
        if (merged.isNotEmpty()) {
            contentDao.upsertAll(merged.map { it.toEntity(now()) })
            Timber.d("[Repo] cached %d trending titles in Room", merged.size)
        } else {
            Timber.w("[Repo] ALL sources returned empty trending")
        }
    }

    override suspend fun catalog(category: String): List<Anime> = coroutineScope {
        val merged = activeSources()
            .map { src -> async { runCatching { src.catalog(category) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .distinctBy { it.id }
        if (merged.isNotEmpty()) contentDao.upsertAll(merged.map { it.toEntity(now()) })
        merged
    }

    override suspend fun catalogPage(sort: Int, page: Int): List<Anime> = coroutineScope {
        val merged = activeSources()
            .map { src -> async { runCatching { src.catalogPage(sort, page) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .distinctBy { it.id }
        if (merged.isNotEmpty()) contentDao.upsertAll(merged.map { it.toEntity(now()) })
        merged
    }

    override suspend fun catalogFiltered(
        filter: com.aniblaze.aggregator.model.CatalogFilter,
        page: Int,
    ): List<Anime> = coroutineScope {
        val anixart = aggregators.filterIsInstance<com.aniblaze.aggregator.source.AnixartSource>().firstOrNull()
        // Фильтрует только Anixart: у остальных источников такого API нет, а тянуть их
        // общую ленту и просеивать её у себя — это страница чужого каталога ради
        // двух-трёх подходящих карточек, на мобильном канале неоправданно.
        val merged = if (anixart != null) {
            runCatching { anixart.filteredPage(filter, page) }.getOrDefault(emptyList())
        } else {
            activeSources()
                .map { src -> async { runCatching { src.catalogPage(filter.sort.anixartId, page) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten().filter(filter::matches)
        }.distinctBy { it.id }
        if (merged.isNotEmpty()) contentDao.upsertAll(merged.map { it.toEntity(now()) })
        merged
    }

    override suspend fun seasonalCatalog(season: Int, year: Int, page: Int): List<Anime> = coroutineScope {
        val merged = activeSources()
            .map { src -> async { runCatching { src.seasonal(season, year, page) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten().distinctBy { it.id }
        if (merged.isNotEmpty()) contentDao.upsertAll(merged.map { it.toEntity(now()) })
        merged
    }

    override suspend fun watchingNow(season: Int, year: Int, page: Int): List<Anime> = coroutineScope {
        val merged = activeSources()
            .map { src -> async { runCatching { src.watchingNow(season, year, page) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten().distinctBy { it.id }
            .sortedByDescending { it.watchingCount }
        if (merged.isNotEmpty()) contentDao.upsertAll(merged.map { it.toEntity(now()) })
        merged
    }

    override suspend fun recommendations(): List<Anime> = coroutineScope {
        val favs = favoriteDao.favoritesOnce().map { it.toDomain() }
        if (favs.size < 3) return@coroutineScope emptyList()

        val favIds = favs.mapTo(HashSet()) { it.id }
        // Weight genres by how often they appear across favourites; keep the top few.
        val topGenres = favs
            .flatMap { it.genres.split(",").map(String::trim).filter(String::isNotEmpty) }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(3).map { it.key }.toHashSet()

        // Exclude everything the user already has — favourites AND watch history —
        // matched on the base release id so voiceover-suffixed ids don't slip through.
        val exclude = (favIds + historyDao.watchedIds())
            .mapTo(HashSet()) { baseId(it) }
        // Also exclude whole franchises the user already follows, so we don't recommend
        // "<Show> 2" when "<Show>" is a favourite (recommended_releases loves doing that).
        val favFranchises = favs.mapTo(HashSet()) { franchiseKey(it.title) }

        // Favourite studios are a strong taste signal (e.g. Ufotable, MAPPA, KyoAni).
        val favStudios = favs.map { it.studio.trim() }.filter { it.isNotEmpty() }.toHashSet()

        // The typical era of the user's taste (median favourite year).
        val years = favs.mapNotNull { it.year.takeIf { y -> y > 0 } }.sorted()
        val medianYear = years.getOrNull(years.size / 2) ?: 0

        // Anixart's own "similar / you might like" picks for a few favourites — strong signal,
        // and (unlike related_releases) not just other seasons of the same show.
        val similar = favs.take(6)
            .map { fav -> async { runCatching { recommendedFor(fav.id) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten()
            .distinctBy { it.id }
        val similarIds = similar.mapTo(HashSet()) { baseId(it.id) }

        // Genre/popularity pool: trending + top-rated across a few pages.
        val pool = listOf(3, 4)
            .flatMap { sort -> (0..2).map { page -> async { runCatching { catalogPage(sort, page) }.getOrDefault(emptyList()) } } }
            .awaitAll().flatten()

        val candidates = (similar + pool)
            .filter { baseId(it.id) !in exclude && franchiseKey(it.title) !in favFranchises }
            .distinctBy { baseId(it.id) }
        if (topGenres.isEmpty() && similarIds.isEmpty()) return@coroutineScope emptyList()

        val result = candidates.asSequence()
            .map { anime ->
                val genreScore = anime.genres.split(",").map(String::trim).count { it in topGenres } * 3
                val similarBonus = if (baseId(anime.id) in similarIds) 6 else 0
                val studioBonus = if (anime.studio.trim() in favStudios) 4 else 0
                // Closer release year to the user's taste gives a small nudge.
                val yearScore = if (medianYear > 0 && anime.year > 0) (3 - kotlin.math.abs(anime.year - medianYear) / 3).coerceAtLeast(0) else 0
                anime to (genreScore + similarBonus + studioBonus + yearScore)
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Anime, Int>> { it.second }.thenByDescending { it.first.rating })
            .map { it.first }
            .take(24)
            .toList()
        result
    }

    /** Strips a voiceover suffix ("ax:11:t5" → "ax:11") so ids compare by release. */
    private fun baseId(id: String): String = id.split(":").take(2).joinToString(":")

    /** Coarse franchise key: lowercased title without season numbers/sub-title, e.g.
     *  "Атака титанов: Финал 2" → "атака титанов". Used to drop sequels of favourites. */
    private fun franchiseKey(title: String): String =
        title.lowercase()
            .substringBefore(':')
            .replace(Regex("[0-9]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    override suspend fun similarTitles(contentId: String): List<Anime> =
        lampaCinema?.similar(contentId)?.takeIf { it.isNotEmpty() }
            ?: recommendedFor(contentId).distinctBy { it.id }

    override suspend fun skipTimings(
        title: String,
        episode: Int,
        altTitle: String,
    ): com.aniblaze.aggregator.source.AniskipTimings.SkipTimings =
        runCatching { aniskip.timings(title, episode, altTitle) }
            .getOrDefault(com.aniblaze.aggregator.source.AniskipTimings.SkipTimings.EMPTY)

    override suspend fun titleSchedule(
        title: String,
        altTitle: String,
    ): com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule =
        runCatching { airDates.forTitle(title, altTitle) }
            .getOrDefault(com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule.EMPTY)

    private val anixart: com.aniblaze.aggregator.source.AnixartSource?
        get() = aggregators.filterIsInstance<com.aniblaze.aggregator.source.AnixartSource>().firstOrNull()

    /**
     * The Anixart release id for ANY anime card: own ids pass through, foreign ids
     * (AniLibria alias, TMDB cinema ids, Rezka/Kinogo page URLs …) go through a
     * STRICT title search — name must match AND years must agree when both are
     * known. No confident match → null, so descriptions/comments can never mix
     * titles up.
     */
    private suspend fun anixartIdFor(contentId: String): String? {
        if (contentId.startsWith("ax:")) return contentId
        val src = anixart ?: return null
        val anime = detail(contentId) ?: return null
        if (anime.title.isBlank()) return null
        return runCatching { src.search(anime.title) }.getOrDefault(emptyList())
            .firstOrNull {
                it.id.startsWith("ax:") && titleMatches(anime.title, it.title) &&
                    (anime.year == 0 || it.year == 0 || it.year == anime.year)
            }?.id
    }

    override suspend fun fullDescription(contentId: String): String? {
        lampaCinema?.description(contentId)?.let { return it }
        val src = anixart ?: return null
        val id = anixartIdFor(contentId) ?: return null
        return runCatching { src.details(id) }.getOrNull()?.description?.takeIf { it.isNotBlank() }
    }

    override suspend fun titleComments(contentId: String, page: Int): List<com.aniblaze.aggregator.model.TitleComment> {
        // Cinema: try Lampa (TMDB reviews) first, then Anixart as fallback
        if (contentId.startsWith("tmdb") || contentId.startsWith("http")) {
            lampaCinema?.comments(contentId, page)?.takeIf { it.isNotEmpty() }?.let { return it }
            // Fallback: try Anixart if there's an Anixart id mapping
            val src = anixart ?: return emptyList()
            val id = anixartIdFor(contentId) ?: return emptyList()
            return runCatching { src.comments(id, page) }.getOrDefault(emptyList())
        }
        // Anime: try Anixart first, then Lampa as fallback
        val src = anixart
        if (src != null) {
            val id = anixartIdFor(contentId)
            if (id != null) {
                val comments = runCatching { src.comments(id, page) }.getOrDefault(emptyList())
                if (comments.isNotEmpty()) return comments
            }
        }
        // Fallback to Lampa for anime ids that map to TMDB
        lampaCinema?.comments(contentId, page)?.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    override suspend fun commentReplies(commentId: Long): List<com.aniblaze.aggregator.model.TitleComment> =
        runCatching { anixart?.commentReplies(commentId) ?: emptyList() }.getOrDefault(emptyList())

    /** Anixart "you might like" picks for a content id, via the first source that has them. */
    private suspend fun recommendedFor(contentId: String): List<Anime> {
        for (src in activeSources()) {
            val recs = runCatching { src.recommended(contentId) }.getOrDefault(emptyList())
            if (recs.isNotEmpty()) return recs
        }
        return emptyList()
    }

    override suspend fun seasons(contentId: String): List<Anime> = coroutineScope {
        val merged = activeSources()
            .map { src -> async { runCatching { src.related(contentId) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .let(::dedupeTitles)
            .sortedWith(compareBy({ it.year == 0 }, { it.year }, { it.title }))
        if (merged.isNotEmpty()) contentDao.upsertAll(merged.map { it.toEntity(now()) })
        merged
    }

    override suspend fun randomAnime(): Anime? {
        for (src in activeSources()) {
            val a = runCatching { src.random() }.getOrNull()
            if (a != null) {
                contentDao.upsert(a.toEntity(now()))
                return a
            }
        }
        return null
    }

    override suspend fun watchedSegments(contentId: String): Set<String> =
        watchProgressDao.watchedSegments(contentId).toSet()

    override suspend fun titleWatchState(contentId: String): TitleWatchState {
        val rows = watchProgressDao.forContent(contentId)
        return TitleWatchState(
            completedEpisodes = rows.count { it.completed },
            hasInProgress = rows.any { !it.completed && it.position > 8_000L && it.position < it.duration },
            lastUpdatedAt = rows.maxOfOrNull { it.updatedAt } ?: 0L,
        )
    }

    override suspend fun resumePoint(contentId: String): ResumePoint? =
        watchProgressDao.latest(contentId)?.let {
            ResumePoint(it.segmentId, it.position, it.duration)
        }

    override suspend fun detail(contentId: String): Anime? =
        contentDao.getById(contentId)?.toDomain()

    override suspend fun segments(contentId: String): List<Segment> = coroutineScope {
        Timber.d("[Repo] segments contentId=%s", contentId)
        // A Set has no useful source order. Asking unrelated network sources first made
        // a poster tap wait for their timeouts before reaching the source that owns the
        // id (notably ax:/tmdb:/ao:/av:). Keep fallbacks, but put the owner first.
        val sources = activeSources().sortedByDescending { source ->
            contentSourceAffinity(contentId, source.name)
        }
        for (src in sources) {
            val segments = runCatching {
                src.getContentSegments(contentId).also {
                    Timber.d("[Repo] segments source='%s' returned %d", src.name, it.size)
                }
            }.getOrDefault(emptyList())
            if (segments.isNotEmpty()) {
                Timber.d("[Repo] segments using %d from source '%s'", segments.size, src.name)
                // Replace the title's cache instead of only upserting. Otherwise
                // previously cached future episodes remain after a corrected API list.
                segmentDao.replaceForContent(contentId, segments.map { it.toEntity() })
                return@coroutineScope segments
            }
            Timber.w("[Repo] segments source '%s' returned empty, trying next", src.name)
        }
        // Fall back to whatever we cached previously.
        val cached = segmentDao.forContent(contentId).map { it.toDomain() }
        Timber.d("[Repo] segments fallback to Room cache: %d", cached.size)
        cached
    }

    override suspend fun resolveStream(contentId: String, segment: Int, mode: StreamMode): ContentResult =
        resolver.resolve(contentId, segment, mode)

    override suspend fun prepareCinemaPlayback(contentId: String) {
        lampaCinema?.preparePlayback(contentId)
    }

    /**
     * Sources offerable in the player's «Источник» picker: cinema ones excluded, and
     * metadata-only ones too. Shikimori's extractContent() returns null by
     * construction, so listing it gave a menu entry that could never work.
     */
    override fun playerSources(): List<String> =
        aggregators.filterNot { it.alwaysActive || it.name in METADATA_ONLY_SOURCES }.map { it.name }

    override suspend fun resolveStreamVia(
        source: String,
        contentId: String,
        segment: Int,
        title: String,
        year: Int,
    ): ContentResult? {
        val src = aggregators.firstOrNull { it.name.equals(source, ignoreCase = true) } ?: return null
        // Direct hit when the source owns the id.
        runCatching { src.extractContent(contentId, segment) }.getOrNull()?.let { return it }
        if (title.isBlank()) return null
        // Its own catalog by title. Sources split franchises per season and a fuzzy
        // search can return an unrelated show — gate every hit on the title (and
        // year, when both sides know it) before trusting its episode.
        val hits = runCatching { src.search(title) }.getOrDefault(emptyList())
        for (hit in hits.take(8)) {
            if (!titleMatches(title, hit.title)) continue
            if (year > 0 && hit.year > 0 && hit.year != year) continue
            runCatching { src.extractContent(hit.id, segment) }.getOrNull()?.let { return it }
        }
        return null
    }

    override suspend fun hiResVariants(title: String, segment: Int, year: Int): List<StreamVariant> {
        if (title.isBlank() || segment <= 0) return emptyList()
        val libria = aggregators.firstOrNull { it.name.contains("libria", ignoreCase = true) }
            ?: return emptyList()
        val res = runCatching { resolveStreamVia(libria.name, "", segment, title, year) }.getOrNull()
            ?: return emptyList()
        return res.variants.orEmpty()
            .filter { qualityHeight(it.quality) >= 1080 }
            .map { StreamVariant("${it.quality} · AniLibria", it.url) }
    }

    override suspend fun randomOngoingPick(excludeId: String): Anime? {
        // «уже смотрел» — не предлагать: история + всё с прогрессом, и по id, и по
        // нормализованному названию (то же аниме на другом источнике = другой id).
        val seenAnime = runCatching { history(100).first() }.getOrDefault(emptyList()) +
            runCatching { continueWatching(60).first().map { it.anime } }.getOrDefault(emptyList())
        val seenIds = seenAnime.mapTo(mutableSetOf(excludeId)) { it.id }
        val seenTitles = seenAnime.mapTo(mutableSetOf()) { normTitle(it.title) }
        fun unseen(a: Anime) = a.id !in seenIds && normTitle(a.title) !in seenTitles
        for (kind in listOf("trending", "watching", "random").shuffled()) {
            val pick = when (kind) {
                // Random endpoint = one title per call; reroll a few times if the
                // dice keep landing on something already watched.
                "random" -> (1..4).firstNotNullOfOrNull {
                    runCatching { randomAnime() }.getOrNull()?.takeIf(::unseen)
                }
                else -> runCatching { catalog(kind) }.getOrDefault(emptyList())
                    .filter(::unseen).randomOrNull()
            }
            if (pick != null) {
                contentDao.upsert(pick.toEntity(now()))
                return pick
            }
        }
        return null
    }

    /** Loose cross-source title equivalence (season markers and punctuation ignored). */
    private fun titleMatches(a: String, b: String): Boolean {
        val stop = setOf("сезон", "часть", "the", "tv", "ova", "ona", "season", "part", "и")
        fun tokens(s: String) = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ')
            .filter { it.length > 2 && it !in stop && it.toIntOrNull() == null && !it.matches(Regex("[ivxl]+")) }
            .toSet()
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val common = ta.count { it in tb }
        return common == minOf(ta.size, tb.size) || common >= kotlin.math.max(2, (minOf(ta.size, tb.size) * 0.6).toInt())
    }

    private fun normTitle(title: String): String =
        title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Схлопывает один и тот же тайтл из разных источников. distinctBy{id} этого не
     * делал: у Anixart id вида "ax:11", у AniLibria — alias, так что одно аниме
     * показывалось двумя карточками. Год участвует в ключе только когда известен с
     * обеих сторон — у AniLibria он часто 0.
     */
    private fun dedupeTitles(all: List<Anime>): List<Anime> {
        val out = LinkedHashMap<String, Anime>()
        val yearByKey = HashMap<String, Int>()
        for (a in all) {
            val key = normTitle(a.title)
            if (key.isBlank()) { out.putIfAbsent(a.id, a); continue }
            val seenYear = yearByKey[key]
            val distinct = seenYear != null && seenYear > 0 && a.year > 0 && seenYear != a.year
            val slot = if (distinct) "$key#${a.year}" else key
            if (out.putIfAbsent(slot, a) == null && a.year > 0) yearByKey[slot] = a.year
        }
        return out.values.toList()
    }

    /** Pixel height out of a quality label: "1080p" → 1080, "Auto" → 0. */
    private fun qualityHeight(quality: String): Int =
        Regex("(\\d{3,4})").find(quality)?.value?.toIntOrNull() ?: 0

    private companion object {
        /** Catalog/metadata sources that never resolve a playable stream. */
        val METADATA_ONLY_SOURCES = setOf("Shikimori")

        /**
         * Ниже скольких результатов имеет смысл переспросить с исправленной раскладкой.
         *
         * Пять, а не ноль: источник отвечает подстрокой, и на «bkbx» он вполне может
         * вернуть один случайный тайтл — формально «не пусто», а по делу промах. И не
         * больше пяти: при живой выдаче второй круг запросов на мобильном канале
         * оплачивать нечем.
         */
        const val LAYOUT_RETRY_BELOW = 5
    }

    override fun continueWatching(limit: Int): Flow<List<ContinueWatchingItem>> =
        watchProgressDao.continueWatching(limit).map { rows ->
            rows.mapNotNull { row ->
                val anime = contentDao.getById(row.contentId)?.toDomain() ?: return@mapNotNull null
                val segment = segmentDao.getById(row.segmentId)?.toDomain()
                    ?: Segment(row.segmentId, row.contentId, 1, "Episode")
                ContinueWatchingItem(anime, segment, row.position, row.duration)
            }
        }

    override suspend fun saveProgress(
        contentId: String,
        segmentId: String,
        positionMs: Long,
        durationMs: Long,
        measuredPlaybackMs: Long,
        ended: Boolean,
    ) {
        val previous = watchProgressDao.get(contentId, segmentId)
        val evidence = progressEvidence(
            previousPositionMs = previous?.position ?: positionMs,
            previousVerifiedMs = previous?.verifiedPlaybackMs ?: 0L,
            alreadyCompleted = previous?.completed == true,
            positionMs = positionMs,
            durationMs = durationMs,
            measuredPlaybackMs = measuredPlaybackMs,
            ended = ended,
        )
        watchProgressDao.save(
            WatchProgressEntity(
                contentId = contentId,
                segmentId = segmentId,
                position = positionMs,
                duration = durationMs,
                updatedAt = now(),
                verifiedPlaybackMs = evidence.verifiedPlaybackMs,
                completed = evidence.completed,
            ),
        )
    }

    override suspend fun progressFor(contentId: String, segmentId: String): Long =
        watchProgressDao.get(contentId, segmentId)?.position ?: 0L

    override fun favorites(): Flow<List<Anime>> =
        favoriteDao.favorites().map { list -> list.map { it.toDomain() } }

    override fun isFavorite(contentId: String): Flow<Boolean> =
        favoriteDao.isFavorite(contentId)

    override suspend fun toggleFavorite(anime: Anime) {
        contentDao.upsert(anime.toEntity(now()))
        val isFav = favoriteDao.isFavorite(anime.id).first()
        if (isFav) favoriteDao.remove(anime.id)
        else favoriteDao.add(FavoriteEntity(anime.id, now()))
    }

    override fun history(limit: Int): Flow<List<Anime>> =
        historyDao.history(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun recordHistory(contentId: String, segmentId: String) {
        historyDao.record(HistoryEntity(contentId, segmentId, now()))
    }

    override suspend fun clearHistory() = historyDao.clear()

    private fun now() = System.currentTimeMillis()
}

internal fun contentSourceAffinity(contentId: String, sourceName: String): Int {
    val id = contentId.lowercase()
    return when {
        id.startsWith("tmdb:") || id.startsWith("tmdbtv:") ->
            if (sourceName == "LampaTMDB") 100 else 0
        id.startsWith("ax:") -> if (sourceName == "Anixart") 100 else 0
        id.startsWith("ao:") -> if (sourceName == "AnimeOn") 100 else 0
        id.startsWith("av:") -> if (sourceName == "AnimeVost") 100 else 0
        id.startsWith("http") && id.contains("rezka") -> if (sourceName == "Rezka") 100 else 0
        else -> 0
    }
}

// ---- mappers ---------------------------------------------------------------

private fun Anime.toEntity(now: Long) = ContentEntity(
    id = id,
    title = title,
    poster = poster,
    description = description,
    rating = rating,
    status = status,
    updatedAt = now,
    broadcast = broadcast,
    genres = genres,
    year = year,
    favoritesCount = favoritesCount,
    studio = studio,
)

private fun ContentEntity.toDomain() = Anime(
    id = id,
    title = title,
    poster = poster,
    description = description,
    rating = rating,
    status = status,
    broadcast = broadcast,
    genres = genres,
    year = year,
    favoritesCount = favoritesCount,
    studio = studio,
)

private fun Segment.toEntity() = SegmentEntity(
    id = id,
    contentId = contentId,
    number = number,
    title = title,
    releaseDate = releaseDate,
)

private fun SegmentEntity.toDomain() = Segment(
    id = id,
    contentId = contentId,
    number = number,
    title = title,
    releaseDate = releaseDate,
)

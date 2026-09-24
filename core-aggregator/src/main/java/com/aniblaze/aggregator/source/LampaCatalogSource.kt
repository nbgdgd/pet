package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.CinemaTorrentIdentity
import com.aniblaze.aggregator.lampa.LampaExtractor
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mobile counterpart of the desktop Lampa cinema source.
 *
 * TMDB supplies the film/series catalog and stable metadata. Playback is resolved
 * by the configured Lampa plugin, using the same balancer order as desktop. For
 * series, CDNVideoHub's full playlist is retained so seasons, episodes and dubs
 * remain separate instead of being flattened into one long video.
 */
@Singleton
class LampaCatalogSource @Inject constructor(
    private val http: HttpClient,
    private val lampa: LampaExtractor,
    private val settings: SettingsDataStore,
) : ContentAggregator {

    override val name: String = "LampaTMDB"
    override val alwaysActive: Boolean = true

    /**
     * TMDB does not reliably expose source-release type (TS/WEB-DL/BD), so we use
     * Kinozapas + Lordfilm as a metadata confirmation source and only use release
     * dates as a conservative fallback when a tag is unavailable.
     */
    @Volatile private var qualityIndex: Map<String, String> = emptyMap()
    @Volatile private var qualityIndexLoadedAt: Long = 0L
    private val qualityMutex = Mutex()
    private val mediaMutex = Mutex()
    private val mediaCache = ConcurrentHashMap<String, Map<String, Any?>>()

    /** Cinema must never leak into the merged anime catalog. */
    override suspend fun search(query: String): List<Anime> = emptyList()
    override suspend fun trending(): List<Anime> = emptyList()

    suspend fun browse(kind: String, page: Int): List<Anime> {
        val p = page.coerceAtLeast(1)
        val items = when (kind) {
            "SERIES" -> parse(get("$API/tv/popular?$KEY&language=ru-RU&page=$p"), tv = true)
            "CARTOON" -> parse(
                get("$API/discover/movie?$KEY&language=ru-RU&sort_by=popularity.desc&vote_count.gte=25&with_genres=16&page=$p"),
                tv = false,
            )
            "CARTOON_SERIES" -> parse(
                get("$API/discover/tv?$KEY&language=ru-RU&sort_by=popularity.desc&vote_count.gte=25&with_genres=16&page=$p"),
                tv = true,
            )
            else -> mixMovies(p)
        }
        return enrichReleaseQuality(items)
    }

    suspend fun searchCinema(query: String): List<Anime> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val movies = parse(get("$API/search/movie?$KEY&language=ru-RU&query=$q&page=1"), tv = false)
        val series = parse(get("$API/search/tv?$KEY&language=ru-RU&query=$q&page=1"), tv = true)
        return enrichReleaseQuality(
            (movies + series).distinctBy { it.id }.sortedByDescending { it.rating },
        )
    }

    private suspend fun enrichReleaseQuality(items: List<Anime>): List<Anime> {
        if (items.isEmpty()) return items
        val now = System.currentTimeMillis()
        qualityMutex.withLock {
        if (qualityIndexLoadedAt == 0L || now - qualityIndexLoadedAt > QUALITY_CACHE_MS) {
            val fresh = runCatching { loadKinozapasQualityIndex() }.getOrDefault(emptyMap())
            // Do not replace a known-good index with a transient network failure.
            if (fresh.isNotEmpty()) qualityIndex = fresh
            qualityIndexLoadedAt = now
        }
        }
        val index = qualityIndex
        return items.map { anime ->
            val release = index[cinemaQualityKey(anime.title, anime.year)].orEmpty()
            val inferred = release.ifBlank { inferReleaseQualityByDate(anime) }
            if (inferred.isBlank() || anime.status.isNotBlank()) anime else anime.copy(status = inferred)
        }
    }

    private fun inferReleaseQualityByDate(anime: Anime): String {
        if (anime.contentType != "Фильм") return ""
        if (anime.releaseDate.isBlank()) return fallbackByYear(anime.year)
        val release = runCatching { LocalDate.parse(anime.releaseDate.take(10)) }.getOrNull()
            ?: return fallbackByYear(anime.year)
        if (release.isAfter(LocalDate.now())) return ""
        val ageDays = ChronoUnit.DAYS.between(release, LocalDate.now()).toInt()
        return when {
            ageDays <= CAM_TO_SCREENER_WINDOW_DAYS -> "Плохое"
            ageDays <= WEB_DL_WINDOW_DAYS -> "Нормальное"
            else -> "Лучшее"
        }
    }

    private fun fallbackByYear(year: Int): String {
        val ageYears = LocalDate.now().year - year
        return when {
            ageYears >= 2 -> "Лучшее"
            ageYears == 1 -> "Нормальное"
            else -> ""
        }
    }

    private suspend fun loadKinozapasQualityIndex(): Map<String, String> = coroutineScope {
        // Bounded batch shared by every catalogue page; no per-poster requests.
        val urls = listOf(KINOZAPAS, "${KINOZAPAS}page/2/", "${KINOZAPAS}page/3/",
            "https://lordfilm.org/films/", "https://lordfilm.org/films/page/2/",
            "https://lordfilm.org/films/page/3/")
        val pages = urls.map { url -> async {
            withTimeoutOrNull(8_000) {
                http.getHtml(url, referer = url)?.let(::parseCinemaQualityIndex)
            }.orEmpty()
        } }.awaitAll()
        // Disagreement between sources is not a confirmed release quality.
        val grouped = pages.flatMap { it.entries }.groupBy({ it.key }, { it.value })
        grouped.mapNotNull { (key, labels) ->
            labels.distinct().singleOrNull()?.let { key to it }
        }.toMap()
    }

    private suspend fun mixMovies(page: Int): List<Anime> {
        val popular = parse(get("$API/movie/popular?$KEY&language=ru-RU&region=RU&page=$page"), tv = false)
        val current = parse(get("$API/movie/now_playing?$KEY&language=ru-RU&region=RU&page=$page"), tv = false)
        val result = LinkedHashMap<String, Anime>()
        for (i in 0 until maxOf(popular.size, current.size)) {
            popular.getOrNull(i)?.let { result.putIfAbsent(it.id, it) }
            current.getOrNull(i)?.let { result.putIfAbsent(it.id, it) }
        }
        return result.values.toList()
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val base = contentId.substringBefore(TRANSLATION_SUFFIX)
        if (!owns(base)) return emptyList()
        if (!base.startsWith(TV_PREFIX)) {
            return listOf(Segment("$base#1", base, 1, "Смотреть"))
        }

        val episodes = seriesEpisodes(base)
        if (episodes.isEmpty()) return listOf(Segment("$base#1", base, 1, "Смотреть"))
        return episodes.mapIndexed { index, episode ->
            Segment(
                id = "$base#${index + 1}",
                contentId = base,
                number = index + 1,
                title = buildString {
                    append("С${episode.season} · Серия ${episode.episode}")
                    if (episode.name.isNotBlank()) append(" · ${episode.name}")
                },
                releaseDate = episode.releaseDate,
            )
        }
    }

    /**
     * Hides the expensive first-click setup behind the already-open detail screen.
     * The plugin and TMDB/IMDb/KinoPoisk identity are stable for the session and are
     * reused by both the ordinary parser and the optional torrent transport.
     */
    suspend fun preparePlayback(contentId: String) = coroutineScope {
        val target = cinemaTarget(contentId) ?: return@coroutineScope
        val prefs = settings.settings.first()
        val metadata = async { mediaMap(target.id, target.isTv) }
        val plugin = async(Dispatchers.IO) {
            runCatching { lampa.load(prefs.lampaPluginUrl) }
                .onFailure { Timber.w(it, "Lampa warm-up failed for %s", target.base) }
        }
        metadata.await()
        plugin.await()
        Unit
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val base = contentId.substringBefore(TRANSLATION_SUFFIX)
        if (!owns(base)) return null
        val isTv = base.startsWith(TV_PREFIX)
        val requestedTranslation = contentId.substringAfter(TRANSLATION_SUFFIX, "").toIntOrNull()
        val userSettings = settings.settings.first()
        val manualVoiceover = userSettings.manualVoiceovers[base].orEmpty()
        val preferredVoiceover = manualVoiceover.ifBlank {
            if (userSettings.voiceoverPriorityEnabled) "" else userSettings.preferredVoiceover
        }

        if (isTv) {
            val episode = seriesEpisodes(base).getOrNull((segment - 1).coerceAtLeast(0))
            if (episode != null && episode.dubs.isNotEmpty()) {
                val preferredIndex = requestedTranslation
                    ?: preferredVoiceoverIndex(
                        episode.dubs.map { it.label },
                        preferredVoiceover,
                        userSettings.voiceoverPriorityEnabled,
                    )
                    ?: 0
                val dubIndex = preferredIndex.coerceIn(0, episode.dubs.lastIndex)
                val variants = fetchVideoVariants(episode.dubs[dubIndex].videoId)
                if (variants.isNotEmpty()) {
                    return ContentResult(
                        location = variants.first().url,
                        quality = variants.first().quality,
                        source = "Lampa/cdnvideohub",
                        referer = CVH_REF,
                        variants = variants,
                        translations = episode.dubs.mapIndexed { index, dub ->
                            Translation(index, dub.label)
                        }.takeIf { it.size > 1 },
                        translationId = dubIndex,
                    )
                }
            }

            // A generic Lampa resolve returns the rendered episode cards. The old
            // code exposed those cards as "озвучки" (torrent_serial_episode 1…)
            // and could play a different episode. Resolve exactly the requested
            // season/episode and never publish the card list as translations.
            if (episode != null) {
                val tmdbId = base.removePrefix(TV_PREFIX)
                val movie = mediaMap(tmdbId, isTv = true) ?: return null
                val stream = withContext(Dispatchers.IO) {
                    runCatching {
                        lampa.resolveEpisode(
                            userSettings.lampaPluginUrl,
                            userSettings.lampaBalancer,
                            movie,
                            season = episode.season,
                            episode = episode.episode,
                        )
                    }.onFailure { Timber.w(it, "Lampa episode resolve failed for %s", base) }
                        .getOrNull()
                } ?: return null
                val variants = stream.qualities
                    .map { StreamVariant(it.first, it.second) }
                    .distinctBy { it.url }
                    .ifEmpty { listOf(StreamVariant("Auto", stream.url)) }
                return ContentResult(
                    location = variants.first().url,
                    quality = variants.first().quality,
                    source = "Lampa/${userSettings.lampaBalancer}",
                    variants = variants,
                )
            }
            return null
        }

        val tmdbId = base.removePrefix(MOVIE_PREFIX)
        val movie = mediaMap(tmdbId, isTv = false) ?: return null
        val streams = withContext(Dispatchers.IO) {
            runCatching {
                lampa.resolve(
                    userSettings.lampaPluginUrl,
                    userSettings.lampaBalancer,
                    movie,
                    season = if (isTv) seriesEpisodes(base)
                        .getOrNull((segment - 1).coerceAtLeast(0))?.season ?: 0 else 0,
                )
            }.onFailure { Timber.w(it, "Lampa resolve failed for %s", base) }
                .getOrDefault(emptyList())
        }
        if (streams.isEmpty()) return null

        val preferredIndex = requestedTranslation
            ?: preferredVoiceoverIndex(
                streams.map { it.title },
                preferredVoiceover,
                userSettings.voiceoverPriorityEnabled,
            )
            ?: 0
        val selectedIndex = preferredIndex.coerceIn(0, streams.lastIndex)
        val selected = streams[selectedIndex]
        val variants = selected.qualities
            .map { StreamVariant(it.first, it.second) }
            .distinctBy { it.url }
            .ifEmpty { listOf(StreamVariant("Auto", selected.url)) }
        return ContentResult(
            location = variants.first().url,
            quality = variants.first().quality,
            source = "Lampa/${userSettings.lampaBalancer}",
            variants = variants,
            translations = streams.mapIndexed { index, stream ->
                Translation(index, stream.title.ifBlank { "Озвучка ${index + 1}" })
            }.takeIf { it.size > 1 },
            translationId = selectedIndex,
        )
    }

    private fun owns(contentId: String): Boolean =
        contentId.startsWith(MOVIE_PREFIX) || contentId.startsWith(TV_PREFIX)

    /** Loads a synopsis only for the currently opened title, never for each card. */
    suspend fun description(contentId: String): String? {
        val base = contentId.substringBefore(TRANSLATION_SUFFIX)
        val isTv = base.startsWith(TV_PREFIX)
        val tmdbId = when {
            isTv -> base.removePrefix(TV_PREFIX)
            base.startsWith(MOVIE_PREFIX) -> base.removePrefix(MOVIE_PREFIX)
            else -> return null
        }.takeIf { it.isNotBlank() } ?: return null

        suspend fun overview(language: String): String? = get(
            "$API/${if (isTv) "tv" else "movie"}/$tmdbId?$KEY&language=$language",
        )?.let { raw ->
            runCatching { json.decodeFromString<TmdbDetails>(raw) }.getOrNull()
                ?.overview?.trim()?.takeIf { it.isNotBlank() }
        }
        return overview("ru-RU") ?: overview("en-US")
    }

    suspend fun similar(contentId: String): List<Anime> {
        val target = cinemaTarget(contentId) ?: return emptyList()
        suspend fun load(language: String) = parse(
            get("$API/${target.kind}/${target.id}/similar?$KEY&language=$language&page=1"),
            tv = target.isTv,
        ).filter { it.id != target.base }
        return load("ru-RU").ifEmpty { load("en-US") }.take(20)
    }

    suspend fun comments(contentId: String, page: Int): List<com.aniblaze.aggregator.model.TitleComment> {
        val target = cinemaTarget(contentId) ?: return emptyList()
        // Русских рецензий на TMDB почти ни у кого нет — без английского
        // фолбэка у большинства фильмов раздел комментариев был бы вечно пуст.
        return parseReviews(
            get("$API/${target.kind}/${target.id}/reviews?$KEY&language=ru-RU&page=${page + 1}"),
        ).ifEmpty {
            parseReviews(
                get("$API/${target.kind}/${target.id}/reviews?$KEY&language=en-US&page=${page + 1}"),
            )
        }
    }

    /** Exact cinema identity for the optional final torrent fallback. */
    suspend fun torrentIdentity(contentId: String, segment: Int): CinemaTorrentIdentity? {
        val target = cinemaTarget(contentId) ?: return null
        val movie = mediaMap(target.id, target.isTv) ?: return null
        val imdb = movie["imdb_id"]?.toString().orEmpty()
            .takeIf { it.matches(Regex("tt\\d{5,12}", RegexOption.IGNORE_CASE)) } ?: return null
        if (!target.isTv) return CinemaTorrentIdentity(imdbId = imdb, isSeries = false, contentId = contentId)
        val episode = seriesEpisodes(target.base).getOrNull((segment - 1).coerceAtLeast(0)) ?: return null
        return CinemaTorrentIdentity(
            imdbId = imdb,
            isSeries = true,
            season = episode.season,
            episode = episode.episode,
            contentId = contentId,
        )
    }

    private data class CinemaTarget(val base: String, val id: String, val isTv: Boolean) {
        val kind: String get() = if (isTv) "tv" else "movie"
    }

    private fun cinemaTarget(contentId: String): CinemaTarget? {
        val base = contentId.substringBefore(TRANSLATION_SUFFIX)
        val isTv = base.startsWith(TV_PREFIX)
        val id = when {
            isTv -> base.removePrefix(TV_PREFIX)
            base.startsWith(MOVIE_PREFIX) -> base.removePrefix(MOVIE_PREFIX)
            else -> return null
        }.takeIf { it.isNotBlank() } ?: return null
        return CinemaTarget(base, id, isTv)
    }

    private suspend fun mediaMap(tmdbId: String, isTv: Boolean): Map<String, Any?>? {
        val cacheKey = "${if (isTv) "tv" else "movie"}:$tmdbId"
        mediaCache[cacheKey]?.let { return it }
        return mediaMutex.withLock {
            mediaCache[cacheKey]?.let { return@withLock it }
            loadMediaMap(tmdbId, isTv)?.also { mediaCache[cacheKey] = it }
        }
    }

    private suspend fun loadMediaMap(tmdbId: String, isTv: Boolean): Map<String, Any?>? {
        val kind = if (isTv) "tv" else "movie"
        val raw = get("$API/$kind/$tmdbId?$KEY&language=ru-RU&append_to_response=external_ids") ?: return null
        val details = runCatching { json.decodeFromString<TmdbDetails>(raw) }.getOrNull() ?: return null
        val title = details.title.ifBlank { details.name }
        val original = details.original_title.ifBlank { details.original_name }
        val imdbId = details.imdb_id?.takeIf(String::isNotBlank)
            ?: details.external_ids?.imdb_id.orEmpty()
        val kinopoiskId = details.external_ids?.kinopoisk_id?.takeIf(String::isNotBlank)
            ?: imdbId.takeIf(String::isNotBlank)?.let { imdb ->
                withContext(Dispatchers.IO) { lampa.kinopoiskIdForImdb(imdb) }
            }
        if (title.isBlank()) return null
        return buildMap {
            // Lampa balancers use movie.id as their lookup/cache key. A TMDB id
            // must never occupy that field when the balancer expects KinoPoisk.
            // A TMDB id is not a KinoPoisk id. Passing it as a plain numeric id
            // makes KinoPoisk-based balancers return an unrelated title.
            put("id", kinopoiskId ?: imdbId.ifBlank { "tmdb-$tmdbId" })
            put("tmdb_id", tmdbId)
            put("imdb_id", imdbId)
            put("kinopoisk_id", kinopoiskId.orEmpty())
            put("title", title)
            put("name", title)
            put("original_title", original)
            put("original_name", original)
            put("release_date", details.release_date.ifBlank { details.first_air_date })
            put("first_air_date", details.first_air_date)
            if (isTv) {
                put("number_of_seasons", details.number_of_seasons)
                put("seasons", details.number_of_seasons)
            }
        }
    }

    /**
     * Build one stable list from TMDB's season counts and the playable CDNVideoHub
     * entries. This keeps a newly announced season visible even when its streams
     * have not appeared in the balancer yet, while existing episodes retain dubs.
     */
    private suspend fun seriesEpisodes(base: String): List<SeriesEpisode> {
        seriesCache[base]?.let { return it }
        val tmdbId = base.removePrefix(TV_PREFIX)
        val details = tvDetails(tmdbId)
        val movie = mediaMap(tmdbId, isTv = true) ?: return emptyList()
        val userSettings = settings.settings.first()
        val playlistRaw = withContext(Dispatchers.IO) {
            runCatching {
                lampa.seriesPlaylist(userSettings.lampaPluginUrl, userSettings.lampaBalancer, movie)
            }.onFailure { Timber.w(it, "Lampa series playlist failed for %s", base) }.getOrNull()
        }
        val playlist = playlistRaw?.let {
            runCatching { json.decodeFromString<CvhPlaylist>(it) }.getOrNull()
        }
        val playable = playlist?.items.orEmpty()
            .filter { it.vkId.isNotBlank() && it.season > 0 && it.episode > 0 }
            .groupBy { it.season to it.episode }
            .map { (key, items) ->
                SeriesEpisode(
                    season = key.first,
                    episode = key.second,
                    name = items.firstNotNullOfOrNull { it.name.takeIf(String::isNotBlank) }.orEmpty(),
                    dubs = items.map { item ->
                        val label = listOf(item.voiceStudio, item.voiceType)
                            .filter(String::isNotBlank)
                            .joinToString(" · ")
                            .ifBlank { "Озвучка" }
                        SeriesDub(label, item.vkId)
                    }.distinctBy { it.videoId },
                )
            }

        // episode_count includes announced future episodes. Load their actual air
        // dates and expose only episodes released by today. A playable entry with
        // no TMDB metadata is retained, but a known future entry is hidden.
        val tmdbEpisodes = details?.seasons.orEmpty()
            .filter { it.season_number > 0 }
            .flatMap { season -> seasonDetails(tmdbId, season.season_number)?.episodes.orEmpty() }
        val today = LocalDate.now()
        val metadata = tmdbEpisodes.associateBy { it.season_number to it.episode_number }
        val merged = LinkedHashMap<Pair<Int, Int>, SeriesEpisode>()
        tmdbEpisodes.filter { isReleasedEpisode(it.air_date, today) }.forEach { episode ->
            merged[episode.season_number to episode.episode_number] = SeriesEpisode(
                season = episode.season_number,
                episode = episode.episode_number,
                name = episode.name,
                releaseDate = episode.air_date,
            )
        }
        playable.forEach { episode ->
            val key = episode.season to episode.episode
            val info = metadata[key]
            if (info == null || isReleasedEpisode(info.air_date, today)) {
                val existing = merged[key]
                merged[key] = episode.copy(
                    name = episode.name.ifBlank { existing?.name.orEmpty() },
                    releaseDate = info?.air_date.orEmpty(),
                )
            }
        }
        val result = merged.values.sortedWith(compareBy({ it.season }, { it.episode }))
        if (result.isNotEmpty()) seriesCache[base] = result
        return result
    }

    private suspend fun tvDetails(tmdbId: String): TmdbDetails? =
        get("$API/tv/$tmdbId?$KEY&language=ru-RU&append_to_response=external_ids")?.let {
            runCatching { json.decodeFromString<TmdbDetails>(it) }.getOrNull()
        }

    private suspend fun seasonDetails(tmdbId: String, season: Int): TmdbSeasonDetails? =
        get("$API/tv/$tmdbId/season/$season?$KEY&language=ru-RU")?.let {
            runCatching { json.decodeFromString<TmdbSeasonDetails>(it) }.getOrNull()
        }

    private suspend fun fetchVideoVariants(videoId: String): List<StreamVariant> {
        val raw = http.getHtml(
            "$CVH_API/player/sv/video/$videoId?pub=$CVH_PUBLISHER",
            referer = CVH_REF,
            headers = mapOf("Origin" to CVH_ORIGIN),
        ) ?: return emptyList()
        val sources = runCatching { json.decodeFromString<CvhVideo>(raw) }.getOrNull()?.sources
            ?: return emptyList()
        val progressive = listOf(
            "2160p" to sources.mpeg4kUrl,
            "1440p" to sources.mpeg2kUrl,
            "1080p" to sources.mpegFullHdUrl,
            "720p" to sources.mpegHighUrl,
            "480p" to sources.mpegMediumUrl,
            "360p" to sources.mpegLowUrl,
            "240p" to sources.mpegLowestUrl,
        ).filter { it.second.isNotBlank() }.map { StreamVariant(it.first, it.second) }
        return progressive.ifEmpty {
            sources.hlsUrl.takeIf(String::isNotBlank)?.let { listOf(StreamVariant("Auto", it)) }.orEmpty()
        }
    }

    private suspend fun get(url: String): String? = http.getHtml(url, referer = TMDB_REF)

    private fun parse(raw: String?, tv: Boolean): List<Anime> {
        val response = raw?.let { runCatching { json.decodeFromString<TmdbResponse>(it) }.getOrNull() }
            ?: return emptyList()
        val prefix = if (tv) TV_PREFIX else MOVIE_PREFIX
        return response.results.mapNotNull { media ->
            val title = media.title.ifBlank { media.name }.ifBlank { return@mapNotNull null }
            val date = media.release_date.ifBlank { media.first_air_date }.take(10)
            Anime(
                id = "$prefix${media.id}",
                title = title,
                poster = media.poster_path?.let { "$IMAGE_BASE$it" }.orEmpty(),
                description = media.overview,
                rating = media.vote_average,
                ratingMax = 10.0,
                contentType = if (tv) "Сериал" else "Фильм",
                genres = media.genre_ids.mapNotNull(GENRES::get).joinToString(", "),
                year = date.take(4).toIntOrNull() ?: 0,
                releaseDate = date,
            )
        }.distinctBy { it.id }
    }


    private fun parseReviews(raw: String?): List<com.aniblaze.aggregator.model.TitleComment> {
        val reviews = raw?.let { runCatching { json.decodeFromString<TmdbReviews>(it) }.getOrNull() } ?: return emptyList()
        return reviews.results.mapNotNull { review ->
            review.content.trim().takeIf { it.isNotBlank() }?.let { message ->
                com.aniblaze.aggregator.model.TitleComment(
                    id = review.id.hashCode().toLong() and 0xffffffffL,
                    author = review.author.ifBlank { "Пользователь TMDB" },
                    avatar = review.author_details.avatar_path.orEmpty().let { path ->
                        if (path.startsWith("http")) path else path.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w92$it" }.orEmpty()
                    },
                    message = message,
                    timestamp = runCatching { java.time.OffsetDateTime.parse(review.created_at).toEpochSecond() }.getOrDefault(0L),
                    votes = review.author_details.rating?.toInt() ?: 0,
                )
            }
        }
    }

    override suspend fun validateSource(contentId: String): Boolean = true

    private val json = Json { ignoreUnknownKeys = true }
    private val seriesCache = ConcurrentHashMap<String, List<SeriesEpisode>>()

    private data class SeriesEpisode(
        val season: Int,
        val episode: Int,
        val name: String = "",
        val dubs: List<SeriesDub> = emptyList(),
        val releaseDate: String = "",
    )

    private data class SeriesDub(val label: String, val videoId: String)

    private companion object {
        const val MOVIE_PREFIX = "tmdb:"
        // Do not use "tmdb:tv:" because ":t" is the translation suffix delimiter.
        const val TV_PREFIX = "tmdbtv:"
        const val TRANSLATION_SUFFIX = ":t"
        const val API = "https://api.themoviedb.org/3"
        const val KEY = "api_key=4ef0d7355d9ffb5151e987764708ce96"
        const val TMDB_REF = "https://www.themoviedb.org/"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w342"
        const val CVH_API = "https://plapi.cdnvideohub.com/api/v1"
        const val CVH_REF = "https://player.cdnvideohub.com/"
        const val CVH_ORIGIN = "https://player.cdnvideohub.com"
        const val CVH_PUBLISHER = "12"
        const val KINOZAPAS = "https://kinozapas.net/"
        const val QUALITY_CACHE_MS = 30 * 60 * 1000L
        // Heuristic fallback when a confirmed release tag is missing.
        // CAM/TS usually appear near theatrical window; WEB/BD quality usually later.
        const val CAM_TO_SCREENER_WINDOW_DAYS = 21
        const val WEB_DL_WINDOW_DAYS = 95

        val GENRES = mapOf(
            12 to "Приключения", 14 to "Фэнтези", 16 to "Мультфильм", 18 to "Драма",
            27 to "Ужасы", 28 to "Боевик", 35 to "Комедия", 36 to "История",
            37 to "Вестерн", 53 to "Триллер", 80 to "Криминал", 99 to "Документальный",
            878 to "Фантастика", 9648 to "Детектив", 10402 to "Музыка",
            10749 to "Мелодрама", 10751 to "Семейный", 10752 to "Военный",
            10759 to "Боевик и приключения", 10762 to "Детский", 10765 to "Фантастика и фэнтези",
        )
    }
}

@Serializable
private data class TmdbResponse(val results: List<TmdbMedia> = emptyList())

@Serializable
private data class TmdbMedia(
    val id: Int = 0,
    val title: String = "",
    val name: String = "",
    val poster_path: String? = null,
    val overview: String = "",
    val vote_average: Double = 0.0,
    val release_date: String = "",
    val first_air_date: String = "",
    val genre_ids: List<Int> = emptyList(),
)

@Serializable
private data class TmdbDetails(
    val title: String = "",
    val name: String = "",
    val original_title: String = "",
    val original_name: String = "",
    val overview: String = "",
    val release_date: String = "",
    val first_air_date: String = "",
    val number_of_seasons: Int = 0,
    val imdb_id: String? = null,
    val external_ids: TmdbExternalIds? = null,
    val seasons: List<TmdbSeason> = emptyList(),
)

@Serializable
private data class TmdbReviews(val results: List<TmdbReview> = emptyList())

@Serializable
private data class TmdbReview(
    val id: String = "",
    val author: String = "",
    val content: String = "",
    val created_at: String = "",
    val author_details: TmdbReviewAuthor = TmdbReviewAuthor(),
)

@Serializable
private data class TmdbReviewAuthor(val avatar_path: String? = null, val rating: Double? = null)

@Serializable
private data class TmdbExternalIds(
    val imdb_id: String? = null,
    val kinopoisk_id: String? = null,
)

@Serializable
private data class TmdbSeason(
    val season_number: Int = 0,
    val episode_count: Int = 0,
)

@Serializable
private data class TmdbSeasonDetails(
    val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
private data class TmdbEpisode(
    val season_number: Int = 0,
    val episode_number: Int = 0,
    val name: String = "",
    val air_date: String = "",
)

@Serializable
private data class CvhPlaylist(val items: List<CvhItem> = emptyList())

@Serializable
private data class CvhItem(
    val vkId: String = "",
    val voiceType: String = "",
    val voiceStudio: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val name: String = "",
)

@Serializable
private data class CvhVideo(val sources: CvhSources? = null)

@Serializable
private data class CvhSources(
    val hlsUrl: String = "",
    val mpeg4kUrl: String = "",
    val mpeg2kUrl: String = "",
    val mpegFullHdUrl: String = "",
    val mpegHighUrl: String = "",
    val mpegMediumUrl: String = "",
    val mpegLowUrl: String = "",
    val mpegLowestUrl: String = "",
)

internal fun isReleasedEpisode(airDate: String, today: LocalDate): Boolean {
    val date = runCatching { LocalDate.parse(airDate) }.getOrNull() ?: return false
    return !date.isAfter(today)
}

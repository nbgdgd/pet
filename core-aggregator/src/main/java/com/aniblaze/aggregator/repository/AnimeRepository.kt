package com.aniblaze.aggregator.repository

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.StreamMode
import kotlinx.coroutines.flow.Flow

/** The last position the user left off at for a title (for "Continue"). */
data class ResumePoint(
    val segmentId: String,
    val positionMs: Long,
    val durationMs: Long,
)

/** A title plus the position the user left off at. */
data class ContinueWatchingItem(
    val anime: Anime,
    val segment: Segment,
    val positionMs: Long,
    val durationMs: Long,
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Сводка сохранённого просмотра одного сезона без сетевых запросов. */
data class TitleWatchState(
    val completedEpisodes: Int = 0,
    val hasInProgress: Boolean = false,
    val lastUpdatedAt: Long = 0L,
)

/**
 * Single point of truth the feature layer talks to. Hides the aggregator
 * engine, the resolver and the Room cache behind a clean domain surface.
 */
interface AnimeRepository {

    suspend fun search(query: String): List<Anime>

    /** TMDB/Lampa cinema browse for the "Кино" tab. [kind] = FILM|SERIES|CARTOON. */
    suspend fun cinemaBrowse(kind: String, page: Int): List<Anime>

    /** TMDB movie/series free-text search for the "Кино" tab. */
    suspend fun cinemaSearch(query: String): List<Anime>

    /** The cinemar.cc player embed URL for a kinogo title — played in a WebView. */
    suspend fun cinemaEmbed(pageUrl: String): String?

    fun trending(limit: Int = 20): Flow<List<Anime>>

    /** Forces a network refresh of the trending list into the local cache. */
    suspend fun refreshTrending()

    /** Loads a named Home section (e.g. "ongoing", "recent", "popular"). */
    suspend fun catalog(category: String): List<Anime>

    /** A page of the browseable catalog (sort 1..4) for the Catalog screen. */
    suspend fun catalogPage(sort: Int, page: Int): List<Anime>

    /**
     * Страница каталога под выбранным фильтром.
     *
     * Условия уходят серверу там, где он их понимает, и в любом случае перепроверяются
     * на нашей стороне ([CatalogFilter.matches]): часть источников фильтров не умеет
     * вовсе, и без проверки в отфильтрованную ленту попадало бы чужое.
     */
    suspend fun catalogFiltered(filter: com.aniblaze.aggregator.model.CatalogFilter, page: Int): List<Anime>

    /** Titles from a given anime season (1=winter..4=autumn) of [year]. */
    suspend fun seasonalCatalog(season: Int, year: Int, page: Int): List<Anime>

    /** New titles of [season]/[year] ranked by how many are watching right now. */
    suspend fun watchingNow(season: Int, year: Int, page: Int): List<Anime>

    /** Other seasons / related titles of the same franchise. */
    suspend fun seasons(contentId: String): List<Anime>

    /** "Similar / you might like" titles for the detail screen. */
    suspend fun similarTitles(contentId: String): List<Anime>

    /** Air dates, release status and next-episode countdown for a title (AniList by
     *  MAL id — no playback source carries any of it). [altTitle] = original name,
     *  the fallback lookup key when the Russian one isn't found. */
    suspend fun titleSchedule(
        title: String,
        altTitle: String = "",
    ): com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule

    /** Полное описание тайтла (каталожные ленты его режут), или null. */
    suspend fun fullDescription(contentId: String): String?

    /** Комментарии сообщества под тайтлом (топовые первыми, 25 на страницу). */
    suspend fun titleComments(contentId: String, page: Int): List<com.aniblaze.aggregator.model.TitleComment>

    /** Ветка ответов под комментарием. */
    suspend fun commentReplies(commentId: Long): List<com.aniblaze.aggregator.model.TitleComment>

    /** A random title for "surprise me". */
    suspend fun randomAnime(): Anime?

    /** Genre-based picks from the user's favourites; empty when fewer than 3 favourites. */
    suspend fun recommendations(): List<Anime>

    /** Segment ids, просмотр которых подтверждён и завершён. */
    suspend fun watchedSegments(contentId: String): Set<String>

    suspend fun titleWatchState(contentId: String): TitleWatchState

    /** Where the user last stopped for this title, if any. */
    suspend fun resumePoint(contentId: String): ResumePoint?

    suspend fun detail(contentId: String): Anime?

    suspend fun segments(contentId: String): List<Segment>

    /** Resolves a playable stream via the multi-source resolver. */
    suspend fun resolveStream(
        contentId: String,
        segment: Int,
        mode: StreamMode = StreamMode.AUTO,
    ): ContentResult

    /** Preloads stable cinema metadata and the configured parser while details are visible. */
    suspend fun prepareCinemaPlayback(contentId: String)

    /** Anime source names for the player's «Источник» picker. */
    fun playerSources(): List<String>

    /** Resolve через КОНКРЕТНЫЙ источник: напрямую по id, иначе поиском по названию
     *  в его собственном каталоге (с проверкой совпадения названия и года). */
    suspend fun resolveStreamVia(
        source: String,
        contentId: String,
        segment: Int,
        title: String,
        year: Int = 0,
    ): ContentResult?

    /** AniLibria's ≥1080p renditions for an episode another source capped at 720p. */
    suspend fun hiResVariants(title: String, segment: Int, year: Int = 0): List<StreamVariant>

    /** Random anime for the «после последней серии» hop: trending / watching-now /
     *  true random, excluding the current title and everything already watched. */
    suspend fun randomOngoingPick(excludeId: String): Anime?

    /** Точные границы опенинга и эндинга серии (AniSkip по MAL id). */
    suspend fun skipTimings(
        title: String,
        episode: Int,
        altTitle: String = "",
    ): com.aniblaze.aggregator.source.AniskipTimings.SkipTimings

    // Continue watching / progress
    fun continueWatching(limit: Int = 20): Flow<List<ContinueWatchingItem>>
    suspend fun saveProgress(
        contentId: String,
        segmentId: String,
        positionMs: Long,
        durationMs: Long,
        measuredPlaybackMs: Long = 0L,
        ended: Boolean = false,
    )
    suspend fun progressFor(contentId: String, segmentId: String): Long

    // Favorites
    fun favorites(): Flow<List<Anime>>
    fun isFavorite(contentId: String): Flow<Boolean>
    suspend fun toggleFavorite(anime: Anime)

    // History
    fun history(limit: Int = 50): Flow<List<Anime>>
    suspend fun recordHistory(contentId: String, segmentId: String)
    suspend fun clearHistory()
}

package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.EpisodeSchedule

/**
 * A "Кино" (film/series) catalog source the user can switch between in Settings.
 *
 * All current sources share the same ortified/Collaps HLS backend for *playback*
 * (a signed master m3u8 that VLC plays worldwide); they differ only in the catalog
 * site — its domain, whether listings paginate, and its card markup. Extends
 * [ContentAggregator] so the repository can resolve a stream through the same
 * `getContentSegments`/`extractContent` path once a source owns an id.
 */
interface CinemaSource : ContentAggregator {
    /** Stable key persisted in settings (e.g. "lordfilm"). */
    val key: String

    /** Human name shown in Settings. */
    val displayName: String

    /** Known limitations — shown to the user under the option in Settings. */
    val drawbacks: String

    /** Category tabs for this source, `path to label`; the first is the default. */
    val categories: List<Pair<String, String>>

    /** True if catalog listings paginate (i.e. real infinite scroll works). */
    val paginates: Boolean

    /** True if this source supports rich server-side filtering (genre/year/sort/
     *  rating applied by the backend, not just client-side on loaded pages). When
     *  true, the UI shows the advanced filter panel and browses via `discover:` paths. */
    val supportsServerFilters: Boolean get() = false

    /** Genres this source can filter by (`id to label`) — only meaningful when
     *  [supportsServerFilters] is true. */
    val genres: List<Pair<String, String>> get() = emptyList()

    /** True if this source owns [contentId] — used to route playback of an id back
     *  to the site it came from, regardless of which source is currently active. */
    fun owns(contentId: String): Boolean

    /** Browse a listing page (`path` from [categories], 1-based `page`). */
    suspend fun browsePath(path: String, page: Int): List<Anime>

    /** Какие фильтры этот источник реально умеет. null = никаких. */
    fun filterFacets(cartoons: Boolean): com.aniblaze.aggregator.model.FilterFacets? = null

    /** Страница каталога, суженная фильтром, вместе с числом найденного. */
    suspend fun browseFiltered(
        filter: com.aniblaze.aggregator.model.CatalogFilter,
        page: Int,
        cartoons: Boolean,
    ): com.aniblaze.aggregator.model.CatalogPage = com.aniblaze.aggregator.model.CatalogPage(emptyList())

    /** Search the catalog. */
    suspend fun searchCinema(query: String): List<Anime>

    /** Full synopsis is loaded only for the title the viewer opened. Catalog cards
     * keep their already available text and never trigger one request per row. */
    suspend fun description(contentId: String): String? = null

    /** Source-native related films and series, fetched only on the detail page. */
    suspend fun similar(contentId: String): List<Anime> = emptyList()

    /** Source-native viewer reviews, fetched only on the detail page. */
    suspend fun comments(contentId: String, page: Int): List<com.aniblaze.aggregator.model.TitleComment> = emptyList()

    /** The balancer's clean embed-player URL for a title (opened in the browser). */
    suspend fun balancerEmbedFor(pageUrl: String): String?

    /** The KinoPoisk id of a title (from its detail page) — needed to resolve the
     *  title through Lampa plugin balancers, which key on kinopoisk_id. Null if the
     *  page doesn't expose one. */
    suspend fun kinopoiskId(contentId: String): String? = null

    /** Exact next episode when the catalog backend exposes scheduling metadata. */
    suspend fun nextEpisodeSchedule(contentId: String): EpisodeSchedule? = null
}

/** Pull a KinoPoisk id out of a detail page: a kinopoisk.ru/film/<id> link, or the
 *  `data-title-id` / `data-kinopoisk` attributes some players embed. */
fun kpIdFromHtml(html: String?): String? {
    html ?: return null
    return Regex("""kinopoisk\.ru/(?:film|series)/(\d+)""").find(html)?.groupValues?.get(1)
        ?: Regex("""data-(?:title-id|kinopoisk|kp)="(\d{3,})"""").find(html)?.groupValues?.get(1)
        ?: Regex("""["'](?:kinopoisk_id|kp_id|kpId)["']\s*[:=]\s*["']?(\d{3,})""").find(html)?.groupValues?.get(1)
}

/** Maps a release tag printed by a cinema parser to the compact poster badge. */
fun cinemaQualityBadge(text: String): String = when {
    Regex("(?i)CAMRIP|HDTS|TELESYNC|\\bTS\\b").containsMatchIn(text) -> "Плохое"
    Regex("(?i)HDRIP|WEB[ .-]?RIP|DVDRIP").containsMatchIn(text) -> "Нормальное"
    Regex("(?i)WEB[ .-]?DL|WEBDL|BDRIP|BLU[ .-]?RAY|REMUX|UHD|4K").containsMatchIn(text) -> "Лучшее"
    else -> ""
}

package com.aniblaze.aggregator

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment

/**
 * Contract implemented by every web source. Implementations are responsible
 * for turning a stable [contentId] into catalog data and, ultimately, a
 * playable [ContentResult]. They must be side-effect free and return null
 * (rather than throwing) when a page simply does not yield a result, reserving
 * exceptions for genuine I/O failures.
 */
interface ContentAggregator {

    /** Stable identifier used in settings, caching and logs. */
    val name: String

    /**
     * When true, this source is always queried for id-routing (segments/stream)
     * regardless of the user's enabled-source toggles. Used by the cinema source
     * whose content lives outside the toggleable anime source list.
     */
    val alwaysActive: Boolean get() = false

    /** Resolves a playable stream for a given segment, or null if unavailable. */
    suspend fun extractContent(contentId: String, segment: Int): ContentResult?

    /** Lists the segments (episodes) available for a title. */
    suspend fun getContentSegments(contentId: String): List<Segment>

    /** Free-text catalog search. */
    suspend fun search(query: String): List<Anime>

    /** Trending / popular titles for the home screen. */
    suspend fun trending(): List<Anime>

    /** Titles for a named Home section (e.g. "ongoing", "recent"). */
    suspend fun catalog(category: String): List<Anime> = emptyList()

    /** A page of the browseable catalog for a given sort (1..4). */
    suspend fun catalogPage(sort: Int, page: Int): List<Anime> = emptyList()

    /** Titles airing in a given season (1=winter..4=autumn) of [year]. */
    suspend fun seasonal(season: Int, year: Int, page: Int): List<Anime> = emptyList()

    /** New titles of [season]/[year] that are most-watched right now. */
    suspend fun watchingNow(season: Int, year: Int, page: Int): List<Anime> = emptyList()

    /** Related titles / other seasons of the same franchise. */
    suspend fun related(contentId: String): List<Anime> = emptyList()

    /** "Similar / you might like" titles (not same-franchise sequels). */
    suspend fun recommended(contentId: String): List<Anime> = emptyList()

    /** A random title for the "surprise me" button. */
    suspend fun random(): Anime? = null

    /** Cheap liveness probe used by the background sync worker. */
    suspend fun validateSource(contentId: String): Boolean
}

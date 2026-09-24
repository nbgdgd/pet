package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * AnimeVost — the one source in the app that serves PROGRESSIVE MP4 instead of HLS.
 *
 * That is the whole point of having it. Every other anime source (Anixart→Kodik,
 * AniLibria, the DLE sites) hands libVLC an HLS playlist, and seeking in HLS means
 * refetching and re-decoding a segment: measured at 1.4–6s of black screen per jump.
 * AnimeVost returns plain files on a CDN with `Accept-Ranges: bytes`, so a seek is a
 * single HTTP range request — verified at ~240ms, with ~9x real-time bandwidth.
 *
 * Trade-offs, deliberately accepted: one in-house dub (not a studio list), a smaller
 * catalog than Anixart, and 720p as the ceiling. It is a *second* source for smooth
 * navigation, not a replacement.
 *
 * API (open, no keys):
 *   GET  /v1/last?page=&quantity=      catalog page
 *   POST /v1/search      name=<query>  search
 *   POST /v1/playlist    id=<id>       episodes, each with `hd` and `std` MP4 urls
 */
class AnimeVostSource(
    private val http: HttpClient,
) : ContentAggregator {

    override val name: String = "AnimeVost"
    override fun ownsContentId(contentId: String): Boolean = contentId.startsWith(PREFIX)

    override suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        val body = http.postFormRaw("$API/search", mapOf("name" to query)) ?: return@withContext emptyList()
        parseCatalog(body)
    }

    override suspend fun trending(): List<Anime> = catalogPage(0, 0)

    override suspend fun catalog(category: String): List<Anime> = catalogPage(0, 0)

    /** /last IS the "latest releases" feed, so it maps straight onto that row. */
    override suspend fun latestReleases(page: Int): List<Anime> = catalogPage(0, page)

    override suspend fun catalogPage(sort: Int, page: Int): List<Anime> = withContext(Dispatchers.IO) {
        // The API pages from 1; the app's pages start at 0.
        val body = http.getHtml("$API/last?page=${page + 1}&quantity=$PAGE_SIZE") ?: return@withContext emptyList()
        parseCatalog(body)
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> = withContext(Dispatchers.IO) {
        episodes(contentId).map { ep ->
            Segment(id = "$contentId#${ep.number}", contentId = contentId, number = ep.number, title = ep.name)
        }
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? = withContext(Dispatchers.IO) {
        val episode = episodes(contentId).firstOrNull { it.number == segment } ?: return@withContext null
        // Highest first — the player's quality picker shows them in this order.
        val variants = buildList {
            episode.hd.takeIf { it.isNotBlank() }?.let { add(StreamVariant("720p", it.https())) }
            episode.std.takeIf { it.isNotBlank() }?.let { add(StreamVariant("480p", it.https())) }
        }
        if (variants.isEmpty()) {
            Timber.w("[AnimeVost] no mp4 for %s ep %d", contentId, segment)
            return@withContext null
        }
        ContentResult(
            location = variants.first().url,
            quality = variants.first().quality,
            source = name,
            variants = variants,
        )
    }

    override suspend fun validateSource(contentId: String): Boolean =
        http.isReachable("$API/last?page=1&quantity=1")

    // ---- episodes ----

    private data class VostEpisode(val number: Int, val name: String, val hd: String, val std: String)

    private suspend fun episodes(contentId: String): List<VostEpisode> {
        // Only answer for OUR ids. Without this the id of another source ("ax:2706")
        // was posted to /playlist as-is, and the bogus reply shadowed the real
        // episode list ("Серии пока недоступны").
        if (!contentId.startsWith(PREFIX)) return emptyList()
        val id = contentId.removePrefix(PREFIX).substringBefore(':')
        if (id.isBlank() || id.toIntOrNull() == null) return emptyList()
        val body = http.postFormRaw("$API/playlist", mapOf("id" to id)) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = e.optString("name")            // "12 серия"
                val number = Regex("\\d+").find(label)?.value?.toIntOrNull() ?: return@mapNotNull null
                VostEpisode(
                    number = number,
                    name = label.ifBlank { "Серия $number" },
                    hd = e.optString("hd"),
                    std = e.optString("std"),
                )
            }.sortedBy { it.number }
        }.onFailure { Timber.w(it, "[AnimeVost] playlist parse failed for %s", contentId) }
            .getOrDefault(emptyList())
    }

    // ---- catalog parsing ----

    private fun parseCatalog(raw: String): List<Anime> = runCatching {
        val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
        (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.toAnime() }.distinctBy { it.id }
    }.onFailure { Timber.w(it, "[AnimeVost] catalog parse failed") }.getOrDefault(emptyList())

    private fun JSONObject.toAnime(): Anime? {
        val id = optInt("id", 0).takeIf { it > 0 } ?: return null
        val rawTitle = optString("title")
        if (rawTitle.isBlank()) return null
        val title = cleanTitle(rawTitle)
        if (BannedContent.isBanned(title, rawTitle)) return null
        return Anime(
            id = "$PREFIX$id",
            title = title,
            poster = optString("urlImagePreview").https(),
            description = optString("description").replace(Regex("<[^>]+>"), " ").trim(),
            rating = optString("rating").toDoubleOrNull() ?: 0.0,
            ratingMax = 10.0,
            ratingVotes = optString("votes").toIntOrNull() ?: 0,
            genres = optString("genre"),
            year = optString("year").toIntOrNull() ?: 0,
            status = optString("type"),
        )
    }

    /** "Наруто / Naruto [1-220 из 220]" → "Наруто". */
    private fun cleanTitle(raw: String): String =
        raw.substringBefore(" / ").substringBefore(" [").trim().ifBlank { raw.trim() }

    /** The API hands out http:// urls; use TLS where the CDN supports it. */
    private fun String.https(): String =
        if (startsWith("http://")) "https://" + removePrefix("http://") else this

    private companion object {
        const val API = "https://api.animevost.org/v1"
        const val PREFIX = "av:"
        const val PAGE_SIZE = 30
    }
}

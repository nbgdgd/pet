package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.network.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

class AniLibriaSource @Inject constructor(
    private val http: HttpClient,
) : ContentAggregator {

    override val name: String = "AniLibria"

    companion object {
        private const val API_BASE = "https://aniliberty.top/api/v1"
        private const val SITE_BASE = "https://aniliberty.top"
        private const val PAGE_LIMIT = 30
    }

    override suspend fun search(query: String): List<Anime> {
        val body = http.getHtml("$API_BASE/app/search/releases?query=${query.urlEncoded()}")
            ?: return emptyList()
        return try {
            parseReleases(JSONArray(body))
        } catch (e: Exception) {
            Timber.w("[AniLibria] search parse error", e)
            emptyList()
        }
    }

    override suspend fun trending(): List<Anime> {
        val body = http.getHtml("$API_BASE/anime/catalog/releases?f[sorting]=RATING_DESC&limit=$PAGE_LIMIT")
            ?: return emptyList()
        return try {
            val obj = JSONObject(body)
            parseReleases(obj.optJSONArray("data") ?: return emptyList())
        } catch (e: Exception) {
            Timber.w("[AniLibria] trending parse error", e)
            emptyList()
        }
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val body = http.getHtml("$API_BASE/anime/releases/$contentId")
            ?: return emptyList()
        return try {
            val release = JSONObject(body)
            val episodes = release.optJSONArray("episodes") ?: return emptyList()
            (0 until episodes.length()).mapNotNull { i ->
                val ep = episodes.optJSONObject(i) ?: return@mapNotNull null
                val id = ep.optString("id", "")
                if (id.isBlank()) return@mapNotNull null
                val ordinal = ep.optDouble("ordinal", 0.0)
                val number = ordinal.toInt()
                val name = ep.optString("name", "").ifBlank { "Episode $number" }
                Segment(id = id, contentId = contentId, number = number, title = name)
            }.sortedBy { it.number }
        } catch (e: Exception) {
            Timber.w("[AniLibria] segments parse error", e)
            emptyList()
        }
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val body = http.getHtml("$API_BASE/anime/releases/$contentId")
            ?: return null
        return try {
            val release = JSONObject(body)
            val episodes = release.optJSONArray("episodes") ?: return null
            for (i in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(i) ?: continue
                val ordinal = ep.optDouble("ordinal", 0.0)
                if (ordinal.toInt() == segment) {
                    val variants = collectVariants(ep)
                    val best = variants.firstOrNull() ?: continue
                    return ContentResult(
                        location = best.url,
                        quality = best.quality,
                        source = name,
                        variants = variants,
                    )
                }
            }
            null
        } catch (e: Exception) {
            Timber.w("[AniLibria] extract error", e)
            null
        }
    }

    override suspend fun validateSource(contentId: String): Boolean =
        http.isReachable("$API_BASE/app/status")

    /** Builds the quality list from the per-episode HLS fields, best first. */
    private fun collectVariants(ep: JSONObject): List<StreamVariant> = buildList {
        ep.optString("hls_1080", "").takeIf { it.isNotBlank() }?.let { add(StreamVariant("1080p", it)) }
        ep.optString("hls_720", "").takeIf { it.isNotBlank() }?.let { add(StreamVariant("720p", it)) }
        ep.optString("hls_480", "").takeIf { it.isNotBlank() }?.let { add(StreamVariant("480p", it)) }
    }

    private fun parseReleases(arr: JSONArray): List<Anime> {
        val seen = mutableSetOf<String>()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val alias = item.optString("alias", "")
            if (alias.isBlank() || !seen.add(alias)) return@mapNotNull null
            val names = item.optJSONObject("name")
            // Hide titles banned for distribution in Russia (RuStore moderation).
            if (BannedContent.isBanned(
                    names?.optString("main"),
                    names?.optString("english"),
                    names?.optString("alternative"),
                )
            ) return@mapNotNull null
            item.toAnime()
        }
    }

    private fun JSONObject.toAnime(): Anime {
        val alias = optString("alias", "")
        val names = optJSONObject("name")
        val title = names?.optString("main", "")?.ifBlank { null }
            ?: names?.optString("english", "")?.ifBlank { null }
            ?: names?.optString("alternative", "") ?: ""
        val poster = optJSONObject("poster")
        val optimized = poster?.optJSONObject("optimized")
        // Prefer full-resolution renditions; thumbnail is a tiny blurry placeholder.
        val posterPath = listOfNotNull(
            optimized?.optString("src", "")?.ifBlank { null },
            poster?.optString("src", "")?.ifBlank { null },
            poster?.optString("preview", "")?.ifBlank { null },
            poster?.optString("thumbnail", "")?.ifBlank { null },
        ).firstOrNull() ?: ""
        val fullPoster = if (posterPath.startsWith("http")) posterPath else "$SITE_BASE$posterPath"
        return Anime(
            id = alias,
            title = title,
            poster = fullPoster,
            description = optString("description", ""),
            // Год ОБЯЗАТЕЛЕН: он единственный гейт, отделяющий сезон от сезона при
            // сопоставлении между источниками. Пока он был 0, гейт "год != год"
            // всегда пропускал, и AniLibria могла подсунуть другой сезон.
            year = optInt("year", 0),
            status = when {
                optBoolean("is_ongoing") -> "Ongoing"
                optBoolean("is_in_production") -> "In Production"
                else -> ""
            },
        )
    }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")
}

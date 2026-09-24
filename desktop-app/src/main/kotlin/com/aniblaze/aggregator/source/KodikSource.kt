package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.network.HttpClient
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

class KodikSource @Inject constructor(
    private val http: HttpClient,
) : ContentAggregator {

    override val name: String = "Kodik"
    override fun ownsContentId(contentId: String): Boolean = contentId.isNotBlank() && contentId.all(Char::isDigit)

    companion object {
        private const val API_DOMAIN = "https://kodikapi.com"
        private const val GH_TOKENS =
            "https://raw.githubusercontent.com/YaNesyTortiK/AnimeParsers/refs/heads/main/kdk_tokns/tokens.json"
    }

    private var token: String? = null

    /**
     * Token obfuscation in YaNesyTortiK's tokens.json is `reverse(b64(h1) + b64(h2))`,
     * i.e. once reversed the string is two (or more) base64 segments concatenated —
     * each padded with `==`. Decoding the whole reversed string in one call fails
     * because of the mid-string padding, so decode each segment and join them.
     */
    private fun decryptToken(encrypted: String): String? = try {
        val reversed = encrypted.reversed()
        val decoded = Regex("[A-Za-z0-9+/]+={0,2}")
            .findAll(reversed)
            .joinToString("") { m -> String(java.util.Base64.getDecoder().decode(m.value)) }
        decoded.ifBlank { null }
    } catch (_: Exception) { null }

    private suspend fun obtainToken(): String? {
        token?.let { return it }

        val body = http.getHtml(GH_TOKENS) ?: return null
        val candidates = mutableListOf<String>()
        try {
            val json = JSONObject(body)
            for (key in arrayOf("stable", "unstable", "legacy")) {
                val arr = json.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val enc = arr.getJSONObject(i).optString("tokn", "")
                    if (enc.isBlank()) continue
                    decryptToken(enc)?.let { candidates.add(it) }
                }
            }
        } catch (_: Exception) { return null }

        for (t in candidates) {
            val test = get("$API_DOMAIN/search", mapOf("token" to t, "title" to "a", "limit" to "1"))
            if (test != null && !test.has("error") && test.optInt("total", -1) > 0) {
                token = t
                Timber.d("[Kodik] token ok: %s…", t.take(8))
                return t
            }
        }
        Timber.w("[Kodik] no working token")
        return null
    }

    private suspend fun get(endpoint: String, params: Map<String, String>): JSONObject? {
        val qs = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        // Сеть вызывается СНАРУЖИ parse-catch: транспортная ошибка должна дойти до
        // репозитория, а не превратиться в честный пустой результат.
        val body = http.getHtml("$endpoint?$qs") ?: return null
        return try { JSONObject(body) } catch (parse: org.json.JSONException) {
            Timber.w(parse, "[Kodik] invalid JSON from %s", endpoint)
            throw parse
        }
    }

    private suspend fun post(endpoint: String, params: Map<String, String>): JSONObject? =
        http.postForm(endpoint, params)

    override suspend fun search(query: String): List<Anime> {
        val t = obtainToken() ?: return emptyList()
        val resp = get("$API_DOMAIN/search", mapOf(
            "token" to t, "title" to query,
            "with_material_data" to "true", "limit" to "30",
            "types" to "anime,anime-serial",
        )) ?: return emptyList()
        return parseResults(resp)
    }

    override suspend fun trending(): List<Anime> {
        val t = obtainToken() ?: return emptyList()
        val resp = get("$API_DOMAIN/list", mapOf(
            "token" to t,
            "with_material_data" to "true", "limit" to "30",
            "types" to "anime,anime-serial",
        )) ?: return emptyList()
        return parseResults(resp)
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val t = obtainToken() ?: return emptyList()
        val rawId = contentId.substringAfterLast('/').ifBlank { contentId }
        val resp = get("$API_DOMAIN/search", mapOf(
            "token" to t, "shikimori_id" to rawId,
            "with_episodes" to "true", "limit" to "1",
        )) ?: return emptyList()

        val results = resp.optJSONArray("results") ?: return emptyList()
        if (results.length() == 0) return emptyList()

        val item = results.getJSONObject(0)
        val link = item.optString("link", "").let { if (it.startsWith("//")) "https:$it" else it }

        val segments = mutableListOf<Segment>()
        val seasons = item.optJSONObject("seasons")
        if (seasons != null && seasons.length() > 0) {
            for (s in seasons.keys()) {
                val episodes = seasons.optJSONObject(s)?.optJSONObject("episodes") ?: continue
                for (e in episodes.keys()) {
                    val epNum = e.toIntOrNull() ?: continue
                    segments.add(Segment(id = "$link|$epNum", contentId = contentId, number = epNum, title = "Эпизод $epNum"))
                }
            }
        }
        if (segments.isEmpty() && link.isNotBlank()) {
            val count = item.optInt("episodes_count", 0)
            for (n in 1..count) {
                segments.add(Segment(id = "$link|$n", contentId = contentId, number = n, title = "Эпизод $n"))
            }
        }
        return segments.sortedBy { it.number }
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val t = obtainToken() ?: return null
        val rawId = contentId.substringAfterLast('/').ifBlank { contentId }

        // 1. Search to get the embed link and first translation
        val resp = get("$API_DOMAIN/search", mapOf(
            "token" to t, "shikimori_id" to rawId,
            "with_episodes" to "true", "limit" to "1",
        )) ?: return null

        val results = resp.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val item = results.getJSONObject(0)
        val link = item.optString("link", "").ifBlank { return null }
        val embedUrl = if (link.startsWith("//")) "https:$link" else link
        val translationId = item.optJSONObject("translation")?.optString("id", "")

        // 2. Get stream link via /get-link
        val params = mutableMapOf(
            "token" to t,
            "shikimori_id" to rawId,
            "episode" to segment.toString(),
            "type" to "anime",
        )
        if (!translationId.isNullOrBlank()) params["translation_id"] = translationId

        val linkResp = get("$API_DOMAIN/get-link", params) ?: return null

        val fileUrl = linkResp.optString("url", "").ifBlank {
            linkResp.optString("link", "").ifBlank {
                linkResp.optJSONObject("data")?.optString("url", "") ?: ""
            }
        }.ifBlank { return null }

        val streamUrl = when {
            fileUrl.startsWith("//") -> "https:$fileUrl"
            fileUrl.startsWith("/") -> "https://kodikapi.com$fileUrl"
            else -> fileUrl
        }
        val quality = item.optString("quality", "auto").ifBlank { "auto" }

        Timber.d("[Kodik] stream: %s (q=%s)", streamUrl.take(80), quality)
        return ContentResult(
            location = streamUrl,
            quality = quality,
            source = name,
            referer = embedUrl,
            metadata = mapOf("contentId" to contentId, "segment" to segment.toString()),
        )
    }

    override suspend fun validateSource(contentId: String): Boolean =
        http.isReachable(API_DOMAIN)

    // ---- Response parsing ----

    private fun parseResults(json: JSONObject): List<Anime> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val seen = mutableSetOf<String>()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id", "").ifBlank { return@mapNotNull null }
            if (!seen.add(id)) return@mapNotNull null
            item.toAnime()
        }
    }

    private fun JSONObject.toAnime(): Anime {
        val id = optString("id", "")
        val title = optString("title", "")
        val originalTitle = optString("title_orig", "")
        val md = optJSONObject("material_data")
        val poster = md?.optString("poster_url", "")?.ifBlank { null }
            ?: md?.optString("anime_poster_url", "") ?: ""
        val description = md?.optString("description", "")?.ifBlank { null }
            ?: md?.optString("anime_description", "") ?: ""
        val sRating = md?.optDouble("shikimori_rating", -1.0) ?: -1.0
        val kpRating = md?.optDouble("kinopoisk_rating", -1.0) ?: -1.0
        val rating = when { sRating > 0 -> sRating; kpRating > 0 -> kpRating; else -> 0.0 }
        val status = md?.optString("all_status", "")?.ifBlank { null }
            ?: md?.optString("anime_status", "") ?: ""
        val genres = buildString {
            md?.optJSONArray("anime_genres")?.let { arr ->
                if (arr.length() > 0) append((0 until arr.length()).joinToString(", ") { arr.optString(it, "") })
            }
            if (isEmpty()) {
                md?.optJSONArray("genres")?.let { arr ->
                    if (arr.length() > 0) append((0 until arr.length()).joinToString(", ") { arr.optString(it, "") })
                }
            }
        }
        val fullDesc = buildString {
            if (genres.isNotBlank()) append("$genres\n\n")
            if (description.isNotBlank()) append(description)
            if (originalTitle.isNotBlank()) append("\n\nОригинал: $originalTitle")
        }
        // И shikimori_rating, и kinopoisk_rating — десятибалльные.
        return Anime(id = id, title = title, poster = poster,
            description = fullDesc.ifBlank { "" }, rating = rating, ratingMax = 10.0,
            ratingVotes = md?.optInt("shikimori_votes", 0) ?: 0, status = status)
    }
}

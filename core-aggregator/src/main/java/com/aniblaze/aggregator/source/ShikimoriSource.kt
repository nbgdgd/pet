package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

class ShikimoriSource @Inject constructor(
    private val http: HttpClient,
    private val okHttpClient: OkHttpClient,
) : ContentAggregator {

    override val name: String = "Shikimori"

    companion object {
        private const val GRAPHQL_URL = "https://shikimori.me/api/graphql"
        private const val REST_API = "https://shikimori.me/api"
        private const val IMAGE_BASE = "https://shikimori.me"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        Timber.d("[Shikimori] search query='%s'", query)
        try {
            val body = graphql("""
                query {
                  animes(search: "${query.replace("\"", "\\\"")}", limit: 30) {
                    id
                    name
                    russian
                    score
                    status
                    episodes
                    kind
                    airedOn { year }
                    poster { id originalUrl }
                  }
                }
            """.trimIndent()) ?: return@withContext emptyList()

            val animes = body.optJSONObject("data")?.optJSONObject("animes")?.let {
                if (it is JSONArray) it else null
            } ?: body.optJSONObject("data")?.optJSONArray("animes")

            if (animes == null || animes.length() == 0) {
                Timber.w("[Shikimori] search empty response")
                return@withContext emptyList()
            }

            val results = (0 until animes.length()).mapNotNull { i ->
                animes.optJSONObject(i)?.toAnime()
            }
            Timber.d("[Shikimori] search returned %d results", results.size)
            results
        } catch (e: Exception) {
            Timber.w(e, "[Shikimori] search error")
            emptyList()
        }
    }

    override suspend fun trending(): List<Anime> = withContext(Dispatchers.IO) {
        Timber.d("[Shikimori] trending")
        try {
            val body = graphql("""
                query {
                  animes(limit: 30, order: popularity) {
                    id
                    name
                    russian
                    score
                    status
                    episodes
                    kind
                    airedOn { year }
                    poster { id originalUrl }
                  }
                }
            """.trimIndent()) ?: return@withContext emptyList()

            val animes = body.optJSONObject("data")?.optJSONArray("animes")
            if (animes == null || animes.length() == 0) {
                Timber.w("[Shikimori] trending empty, falling back to REST API")
                return@withContext trendingRest()
            }

            val results = (0 until animes.length()).mapNotNull { i ->
                animes.optJSONObject(i)?.toAnime()
            }
            Timber.d("[Shikimori] trending returned %d results", results.size)
            results
        } catch (e: Exception) {
            Timber.w(e, "[Shikimori] trending error, fallback to REST")
            trendingRest()
        }
    }

    private suspend fun trendingRest(): List<Anime> = withContext(Dispatchers.IO) {
        try {
            val url = "$REST_API/animes?page=1&limit=30&order=popularity&status=ongoing"
            val body = http.getHtml(url) ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.toAnimeRest()
            }
        } catch (e: Exception) {
            Timber.w(e, "[Shikimori] trending REST fallback error")
            emptyList()
        }
    }

    override suspend fun catalog(category: String): List<Anime> = withContext(Dispatchers.IO) {
        if (category != "anons") return@withContext emptyList()
        Timber.d("[Shikimori] catalog category='%s'", category)
        try {
            val body = graphql("""
                query {
                  animes(limit: 30, status: "anons", order: popularity) {
                    id
                    name
                    russian
                    score
                    status
                    episodes
                    kind
                    airedOn { year }
                    poster { id originalUrl }
                  }
                }
            """.trimIndent()) ?: return@withContext emptyList()

            val animes = body.optJSONObject("data")?.optJSONArray("animes")
            if (animes == null || animes.length() == 0) {
                Timber.w("[Shikimori] catalog %s empty", category)
                return@withContext emptyList()
            }

            (0 until animes.length()).mapNotNull { i ->
                animes.optJSONObject(i)?.toAnime()
            }
        } catch (e: Exception) {
            Timber.w(e, "[Shikimori] catalog %s error", category)
            emptyList()
        }
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        return emptyList()
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        return null
    }

    override suspend fun validateSource(contentId: String): Boolean =
        http.isReachable("https://shikimori.me")

    // ---- GraphQL ----

    private suspend fun graphql(query: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject().apply { put("query", query) }
            val requestBody = json.toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(GRAPHQL_URL)
                .header("User-Agent", "Mozilla/5.0 (Android 14) AniBlaze/1.0")
                .header("Accept", "application/json")
                .post(requestBody)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("[Shikimori] GraphQL HTTP %d: %s", response.code, response.body?.string())
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                JSONObject(body)
            }
        }.onFailure { Timber.w(it, "[Shikimori] GraphQL error") }.getOrNull()
    }

    // ---- Mappers ----

    private fun JSONObject.toAnime(): Anime? {
        val id = optString("id", "").ifBlank { return null }
        val name = optString("name", "")
        val russian = optString("russian", "")
        val title = russian.ifBlank { name }.ifBlank { return null }
        val score = optDouble("score", 0.0)
        val status = optString("status", "").let { s ->
            when (s) {
                "anons" -> "Анонс"
                "ongoing" -> "Онгоинг"
                "released" -> "Вышел"
                else -> s
            }
        }
        val poster = optJSONObject("poster")?.optString("originalUrl", "")
            ?.let { if (it.startsWith("/")) "$IMAGE_BASE$it" else it } ?: ""
        val kind = optString("kind", "")

        return Anime(
            id = id,
            title = title,
            poster = poster,
            description = "Тип: ${kindRu(kind)}",
            rating = score,
            ratingMax = 10.0,
            status = status,
        )
    }

    private fun JSONObject.toAnimeRest(): Anime? {
        val id = optInt("id", 0).takeIf { it > 0 }?.toString() ?: return null
        val name = optString("name", "")
        val russian = optString("russian", "")
        val title = russian.ifBlank { name }.ifBlank { return null }
        val score = optString("score", "0.0").toDoubleOrNull() ?: 0.0
        val status = optString("status", "")
        val poster = optJSONObject("image")?.optString("original", "")
            ?.let { if (it.startsWith("/")) "$IMAGE_BASE$it" else it } ?: ""
        val kind = optString("kind", "")

        return Anime(
            id = id,
            title = title,
            poster = poster,
            description = "Тип: ${kindRu(kind)}",
            rating = score,
            ratingMax = 10.0,
            status = status,
        )
    }

    private fun kindRu(kind: String): String = when (kind) {
        "tv" -> "ТВ Сериал"
        "movie" -> "Фильм"
        "ova" -> "OVA"
        "ona" -> "ONA"
        "special" -> "Спецвыпуск"
        "tv_special" -> "TV Спецвыпуск"
        "music" -> "Клип"
        "pv" -> "Проморолик"
        "cm" -> "Реклама"
        else -> kind
    }
}

// force rebuild

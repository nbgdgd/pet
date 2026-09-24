package com.aniblaze.aggregator.source

import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Список тайтлов AniList по признаку, которого нет ни у одного нашего каталога, —
 * по первоисточнику (`MediaSource`: манга, ранобэ, оригинал, игра).
 *
 * AniList отдаёт только свои записи: MAL id, ромадзи/английское название, год и
 * формат — без русского названия и без постера. Карточку каталога по ним находит
 * репозиторий (см. DesktopRepository.sourceMaterialPage) и сверяет по MAL id, а не
 * по первому похожему названию.
 */
class AniListCatalog(private val http: HttpClient) {

    data class Entry(val idMal: Int, val romaji: String, val english: String, val year: Int, val format: String)

    /**
     * Одна страница по 50 записей. [sort] — AniList `MediaSort` (POPULARITY_DESC,
     * SCORE_DESC, START_DATE_DESC…). Взрослое и музыкальные клипы отсеяны сразу.
     */
    suspend fun bySource(source: String, page: Int, sort: String = "POPULARITY_DESC"): List<Entry> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("query", QUERY)
            .put(
                "variables",
                JSONObject().put("page", page + 1).put("source", source).put("sort", org.json.JSONArray().put(sort)),
            )
            .toString()
        val resp = http.postJson(ANILIST_API, body) ?: return@withContext emptyList()
        runCatching { parse(resp) }
            .onFailure { Timber.w(it, "[AniListCatalog] parse failed for %s/%d", source, page) }
            .getOrDefault(emptyList())
    }

    /**
     * Страница по тегам AniList (`tag_in`, любой из списка), 50 записей. Теги у
     * AniList — с рангом уместности 0..100; берём только те, где тег стоит не ниже
     * [minRank]: «Anti-Hero» с рангом 20 — это эпизодический персонаж, не герой.
     */
    suspend fun byTags(tags: List<String>, page: Int, sort: String = "POPULARITY_DESC", minRank: Int = 60): List<Entry> = withContext(Dispatchers.IO) {
        if (tags.isEmpty()) return@withContext emptyList()
        val body = JSONObject()
            .put("query", TAG_QUERY)
            .put(
                "variables",
                JSONObject().put("page", page + 1).put("tags", org.json.JSONArray(tags)).put("sort", org.json.JSONArray().put(sort)),
            )
            .toString()
        val resp = http.postJson(ANILIST_API, body) ?: return@withContext emptyList()
        runCatching { parseWithTags(resp, tags.toSet(), minRank) }
            .onFailure { Timber.w(it, "[AniListCatalog] tag parse failed for %s/%d", tags, page) }
            .getOrDefault(emptyList())
    }

    companion object {
        private const val ANILIST_API = "https://graphql.anilist.co"

        private const val TAG_QUERY =
            "query(\$page:Int,\$tags:[String],\$sort:[MediaSort]){" +
                "Page(page:\$page,perPage:50){media(type:ANIME,tag_in:\$tags,sort:\$sort,isAdult:false," +
                "format_in:[TV,TV_SHORT,MOVIE,OVA,ONA,SPECIAL]){idMal title{romaji english} seasonYear startDate{year} format " +
                "tags{name rank}}}}"

        /** Как [parse], но оставляет только записи, где нужный тег стоит с рангом ≥ minRank. */
        fun parseWithTags(json: String, wanted: Set<String>, minRank: Int): List<Entry> {
            val media = JSONObject(json).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
                ?: return emptyList()
            val keep = (0 until media.length()).mapNotNull { i ->
                val m = media.optJSONObject(i) ?: return@mapNotNull null
                val tags = m.optJSONArray("tags") ?: return@mapNotNull null
                val strong = (0 until tags.length()).any { t ->
                    val tag = tags.optJSONObject(t) ?: return@any false
                    tag.optString("name") in wanted && tag.optInt("rank", 0) >= minRank
                }
                if (strong) m.optInt("idMal", 0) else null
            }.filter { it > 0 }.toSet()
            return parse(json).filter { it.idMal in keep }
        }

        private const val QUERY =
            "query(\$page:Int,\$source:MediaSource,\$sort:[MediaSort]){" +
                "Page(page:\$page,perPage:50){media(type:ANIME,source:\$source,sort:\$sort,isAdult:false," +
                "format_in:[TV,TV_SHORT,MOVIE,OVA,ONA,SPECIAL]){idMal title{romaji english} seasonYear startDate{year} format}}}"

        /** Разбор ответа — чистая функция ради теста. */
        fun parse(json: String): List<Entry> {
            val media = JSONObject(json).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
                ?: return emptyList()
            return (0 until media.length()).mapNotNull { i ->
                val m = media.optJSONObject(i) ?: return@mapNotNull null
                val idMal = m.optInt("idMal", 0).takeIf { it > 0 } ?: return@mapNotNull null
                val title = m.optJSONObject("title")
                val romaji = title?.optString("romaji").orEmpty().takeIf { it != "null" }.orEmpty()
                val english = title?.optString("english").orEmpty().takeIf { it != "null" }.orEmpty()
                if (romaji.isBlank() && english.isBlank()) return@mapNotNull null
                val year = m.optInt("seasonYear", 0).takeIf { it > 0 }
                    ?: m.optJSONObject("startDate")?.optInt("year", 0) ?: 0
                Entry(idMal, romaji, english, year, m.optString("format").orEmpty())
            }
        }
    }
}

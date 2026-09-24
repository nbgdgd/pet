package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.network.HttpClient
import org.json.JSONObject
import timber.log.Timber

/**
 * animeon.cc — витрина с ОТКРЫТЫМ JSON-API, ключ которого совпадает с id Shikimori.
 *
 * Зачем он нужен рядом с Anixart, у которого каталог шире: у одного и того же
 * эпизода тут бывает заметно больше озвучек, и каждая — отдельный файл. Замерено на
 * «Блич [ТВ-2, часть 4]» (shikimori 60636): девять озвучек одним запросом, тогда как
 * у Anixart набор другой. Когда у Anixart серия «недоступна ни в одной озвучке»,
 * шанс, что она доступна здесь, вполне реальный.
 *
 * Потоки при этом ТЕ ЖЕ: ссылки ведут на kodikplayer.com, который разбирает
 * [KodikExtractor]. Никакой новой устойчивости этот источник не приносит и не должен
 * — он про ПОКРЫТИЕ, а не про качество соединения.
 *
 * Авторизации нет: проверено голым запросом без User-Agent, HTTP 200.
 */
class AnimeOnSource(
    private val http: HttpClient,
    private val kodik: KodikExtractor,
) : ContentAggregator {

    override val name: String = "AnimeOn"
    override fun ownsContentId(contentId: String): Boolean = contentId.startsWith(PREFIX)

    override suspend fun search(query: String): List<Anime> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val resp = http.getHtml("$API/search?query=$encoded&limit=30") ?: return emptyList()
        return parseList(resp, "results")
    }

    override suspend fun trending(): List<Anime> = page(1)

    override suspend fun catalog(category: String): List<Anime> = page(1)

    override suspend fun catalogPage(sort: Int, page: Int): List<Anime> = page(page)

    private suspend fun page(page: Int): List<Anime> {
        val resp = http.getHtml("$API/anime?page=${page.coerceAtLeast(1)}&limit=30") ?: return emptyList()
        return parseList(resp, "items")
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val title = details(contentId) ?: return emptyList()
        val dubs = dubsOf(title)
        val chosen = explicitDub(contentId)?.let { index -> dubs.getOrNull(index) }
            ?: dubs.maxByOrNull { it.episodes.size }
            ?: return emptyList()
        return chosen.episodes.keys.sorted().map { number ->
            Segment(
                id = chosen.episodes.getValue(number),
                contentId = contentId,
                number = number,
                title = "Серия $number",
            )
        }
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val title = details(contentId) ?: return null
        val dubs = dubsOf(title)
        if (dubs.isEmpty()) return null
        // Выбранная озвучка первой, остальные — по числу зрителей. Ровно как у
        // Anixart: одна озвучка может жить на хостинге, который мы не разбираем, и
        // ронять из-за неё весь тайтл нельзя.
        val preferred = explicitDub(contentId)
        val order = buildList {
            preferred?.let { index -> dubs.getOrNull(index)?.let { add(it) } }
            dubs.filter { it !== dubs.getOrNull(preferred ?: -1) }
                .sortedByDescending { it.viewers }
                .forEach { add(it) }
        }
        val translations = dubs.mapIndexed { index, dub -> Translation(index, dub.name, dub.isSub, dub.viewers) }
        for (dub in order) {
            val link = dub.episodes[segment] ?: continue
            val variants = kodik.extract(link)
            if (variants.isEmpty()) {
                Timber.w("[AnimeOn] no stream for %s ep %d via %s", contentId, segment, dub.name)
                continue
            }
            return ContentResult(
                location = variants.first().url,
                quality = variants.first().quality,
                variants = variants,
                translations = translations,
                translationId = dubs.indexOfFirst { it === dub },
                source = name,
                referer = KODIK_REF,
                metadata = mapOf("contentId" to contentId, "segment" to segment.toString()),
            )
        }
        return null
    }

    override suspend fun related(contentId: String): List<Anime> {
        val id = shikimoriId(contentId) ?: return emptyList()
        val resp = http.getHtml("$API/anime/$id") ?: return emptyList()
        val root = runCatching { JSONObject(resp) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Anime>()
        for (key in listOf("franchise", "related")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { animeFrom(it) }?.let { out.add(it) }
            }
        }
        return out.distinctBy { it.id }.sortedBy { if (it.year > 0) it.year else Int.MAX_VALUE }
    }

    override suspend fun validateSource(contentId: String): Boolean =
        http.getHtml("$API/anime?page=1&limit=1") != null

    // ---- разбор ----

    private data class Dub(
        val name: String,
        val isSub: Boolean,
        val viewers: Long,
        /** номер серии → ссылка на эмбед kodikplayer */
        val episodes: Map<Int, String>,
    )

    private suspend fun details(contentId: String): JSONObject? {
        val id = shikimoriId(contentId) ?: return null
        val resp = http.getHtml("$API/anime/$id") ?: return null
        return runCatching { JSONObject(resp) }.getOrNull()
    }

    /** Озвучки в СТАБИЛЬНОМ порядке (по имени): их номер уезжает в id тайтла как
     *  `:tN` и должен означать одно и то же между запусками. */
    private fun dubsOf(title: JSONObject): List<Dub> {
        val translations = title.optJSONObject("translations") ?: return emptyList()
        return translations.keys().asSequence().sorted().mapNotNull { dubName ->
            val node = translations.optJSONObject(dubName) ?: return@mapNotNull null
            val episodes = node.optJSONObject("episodes") ?: return@mapNotNull null
            val links = buildMap {
                episodes.keys().forEach { key ->
                    val number = key.toIntOrNull() ?: return@forEach
                    val link = episodes.optJSONObject(key)?.optString("link").orEmpty()
                    if (link.isNotBlank()) put(number, link)
                }
            }
            if (links.isEmpty()) return@mapNotNull null
            Dub(
                name = dubName,
                isSub = dubName.contains("sub", ignoreCase = true) ||
                    dubName.contains("субтитр", ignoreCase = true),
                viewers = node.optLong("viewers", 0),
                episodes = links,
            )
        }.toList()
    }

    private fun parseList(resp: String, field: String): List<Anime> {
        val arr = runCatching { JSONObject(resp).optJSONArray(field) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { animeFrom(it) }
        }.distinctBy { it.id }
    }

    private fun animeFrom(o: JSONObject): Anime? {
        val shikimori = o.optInt("shikimori_id", 0).takeIf { it > 0 } ?: return null
        val title = o.optString("title").ifBlank { o.optString("title_orig") }.ifBlank { return null }
        if (BannedContent.isBanned(o.optString("title"), o.optString("title_orig"))) return null
        // Постер отдаётся путём от корня сайта; «shiki»-вариант крупнее и чище.
        val poster = listOf(o.optString("anime_poster"), o.optString("poster"))
            .firstOrNull { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "$SITE$it" }
            .orEmpty()
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).joinToString(", ") { arr.optString(it) }
        }.orEmpty()
        return Anime(
            id = "$PREFIX$shikimori",
            title = title,
            poster = poster,
            description = o.optString("description"),
            // Это оценка Shikimori (то же число лежит в material_data.shikimori_rating),
            // то есть десятибалльная, — а не пятибалльный «grade» Anixart.
            rating = o.optDouble("rating", 0.0),
            ratingMax = 10.0,
            ratingVotes = o.optInt("votes_count", 0),
            status = o.optString("title_orig"),
            genres = genres,
            year = o.optInt("year", 0),
            // Поля для фильтра каталога. Сам AnimeOn фильтровать не умеет, но эти
            // значения позволяют проверке отсеять его карточки на нашей стороне —
            // иначе под фильтром он подмешивал бы в ленту что попало.
            episodesTotal = o.optJSONObject("material_data")?.optInt("episodes_total", 0) ?: 0,
            contentType = when (o.optString("kind")) {
                "movie" -> "Фильм"
                "ova", "ona" -> "OVA"
                "special" -> "Спешл"
                "tv" -> "Сериал"
                else -> ""
            },
            airingStatus = when (o.optString("status")) {
                "released" -> 1
                "ongoing" -> 2
                "anons" -> 3
                else -> 0
            },
        )
    }

    // ---- id ----

    /** `ao:60636` или `ao:60636:t3` → 60636. */
    private fun shikimoriId(contentId: String): String? =
        contentId.removePrefix(PREFIX).substringBefore(':').takeIf { it.isNotBlank() && it.all(Char::isDigit) }

    private fun explicitDub(contentId: String): Int? =
        Regex(""":t(\d+)""").find(contentId)?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        const val SITE = "https://animeon.cc"
        const val API = "$SITE/api"
        const val PREFIX = "ao:"
        const val KODIK_REF = "https://kodikplayer.com/"
    }
}

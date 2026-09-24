package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.network.HttpClient
import org.jsoup.Jsoup
import timber.log.Timber

/**
 * Animedia (amd.online) — студия со СВОИМ CDN: `aser.pro` раздаёт HLS напрямую,
 * без Kodik. Это единственный здесь источник, чей поток не зависит от
 * kodikplayer.com, — ради этого он и заведён как запасной.
 *
 * Сайт на DLE, API нет — разбор HTML (Jsoup). Проверено 17.09.2026:
 *   • каталог: `/` и `/page/N/`, карточки — ссылки `/{newsId}-{slug}.html`;
 *   • поиск: `GET /index.php?do=search&subaction=search&story=<query>`;
 *   • серии: на странице тайтла `a.nav_video_links[data-vid][data-vlnk]`, где
 *     `data-vlnk` = `https://aser.pro/vod/{n}` (не вышедшие серии ведут на
 *     `/index.php?do=nz…` — они помечаются `playable=false`);
 *   • поток: страница `vod` содержит `file: "https://aser.pro/content/stream/…/hls/index.m3u8"`
 *     — master-плейлист 720/360, Referer CDN не проверяет.
 *
 * Идентификаторы: `am:{newsId}`; slug нужен для адреса страницы, поэтому id хранится
 * как `am:{newsId}:{slug}`.
 */
class AnimediaSource(
    private val http: HttpClient,
) : ContentAggregator {

    override val name: String = "Animedia"
    override fun ownsContentId(contentId: String): Boolean = contentId.startsWith(PREFIX)

    override suspend fun search(query: String): List<Anime> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val html = http.getHtml("$SITE/index.php?do=search&subaction=search&story=$encoded") ?: return emptyList()
        return parseCards(html)
    }

    override suspend fun trending(): List<Anime> = catalogPage(0, 0)

    override suspend fun catalog(category: String): List<Anime> = catalogPage(0, 0)

    override suspend fun catalogPage(sort: Int, page: Int): List<Anime> {
        val url = if (page <= 0) "$SITE/" else "$SITE/page/${page + 1}/"
        val html = http.getHtml(url) ?: return emptyList()
        return parseCards(html)
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> =
        episodes(contentId).map { ep ->
            Segment(
                id = ep.vod.ifBlank { "$contentId#${ep.number}" },
                contentId = contentId,
                number = ep.number,
                title = "Серия ${ep.number}",
                playable = ep.vod.isNotBlank(),
            )
        }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val ep = episodes(contentId).firstOrNull { it.number == segment } ?: return null
        if (ep.vod.isBlank()) return null
        val html = http.getHtml(ep.vod, referer = "$SITE/") ?: return null
        val master = MASTER.find(html)?.groupValues?.get(1) ?: run {
            Timber.w("[Animedia] vod page without m3u8: %s", ep.vod)
            return null
        }
        val variants = variantsOf(master, http.getHtml(master, referer = ep.vod))
        return ContentResult(
            location = master,
            quality = variants.firstOrNull()?.quality ?: "auto",
            variants = variants,
            source = name,
            referer = ep.vod,
            metadata = mapOf("contentId" to contentId, "segment" to segment.toString()),
        )
    }

    override suspend fun validateSource(contentId: String): Boolean = http.isReachable("$SITE/")

    // ---- серии ----

    data class Episode(val number: Int, val vod: String)

    private suspend fun episodes(contentId: String): List<Episode> {
        val page = pageUrl(contentId) ?: return emptyList()
        val html = http.getHtml(page) ?: return emptyList()
        return parseEpisodes(html)
    }

    companion object {
        const val SITE = "https://amd.online"
        private const val PREFIX = "am:"
        val DETAIL = Regex("""^https?://[^/]+/(\d+)-([a-z0-9\-]+)\.html$""")
        val MASTER = Regex("""file:\s*"(https?://[^"]+\.m3u8)"""")

        /** `am:5736:slug` → страница тайтла. */
        fun pageUrl(contentId: String): String? {
            val parts = contentId.removePrefix(PREFIX).split(':', limit = 2)
            val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
            val slug = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            return "$SITE/$id-$slug.html"
        }

        /** Разбор списка серий страницы тайтла — открыт для теста на фикстуре. */
        fun parseEpisodes(html: String): List<Episode> {
            val doc = Jsoup.parse(html, SITE)
            return doc.select("a.nav_video_links[data-vid]").mapNotNull { a ->
                val number = a.attr("data-vid").toIntOrNull() ?: return@mapNotNull null
                val link = a.attr("data-vlnk").let { if (it.startsWith("//")) "https:$it" else it }
                Episode(number, vod = if (link.startsWith("https://aser.pro/vod/")) link else "")
            }.distinctBy { it.number }.sortedBy { it.number }
        }

        /**
         * Варианты качества из master-плейлиста: `RESOLUTION=1280x720` + относительный
         * адрес рядом с master. Лучшее первым. Без тела — только «auto».
         */
        fun variantsOf(master: String, body: String?): List<StreamVariant> {
            body ?: return listOf(StreamVariant("auto", master))
            val base = master.substringBeforeLast('/') + "/"
            val lines = body.lines().map(String::trim)
            val out = mutableListOf<StreamVariant>()
            for (i in lines.indices) {
                val info = lines[i]
                if (!info.startsWith("#EXT-X-STREAM-INF")) continue
                val height = Regex("""RESOLUTION=\d+x(\d+)""").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val uri = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
                val url = when {
                    uri.startsWith("http") -> uri
                    uri.startsWith("./") -> base + uri.removePrefix("./")
                    else -> base + uri
                }
                out.add(StreamVariant("${height}p", url))
            }
            return out.distinctBy { it.quality }.sortedByDescending { it.quality.dropLast(1).toIntOrNull() ?: 0 }
                .ifEmpty { listOf(StreamVariant("auto", master)) }
        }
    }

    // ---- каталог ----

    /**
     * Только настоящие карточки `div.poster` (главная, /page/N/, поиск — одна
     * разметка). Просто `a[href]` на страницу тайтла ловил и текстовые ссылки из
     * боковых списков — карточки без постера и с названием вроде «1999».
     */
    private fun parseCards(html: String): List<Anime> {
        val doc = Jsoup.parse(html, SITE)
        return doc.select("div.poster").mapNotNull { card ->
            val a = card.select("a[href]").firstOrNull { DETAIL.containsMatchIn(it.absUrl("href")) }
                ?: return@mapNotNull null
            val href = a.absUrl("href")
            val m = DETAIL.find(href) ?: return@mapNotNull null
            if (!href.startsWith(SITE)) return@mapNotNull null
            val title = card.selectFirst(".poster__title, h3, h2")?.text().orEmpty()
                .ifBlank { a.attr("title") }.ifBlank { a.text() }.trim()
            if (title.length < 2) return@mapNotNull null
            if (BannedContent.isBanned(title)) return@mapNotNull null
            val img = card.selectFirst("img[src*=/uploads/], img[data-src*=/uploads/], img")
            val poster = img?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }.orEmpty()
            if (poster.isBlank()) return@mapNotNull null
            val year = Regex("""(19|20)\d{2}""").find(card.text())?.value?.toIntOrNull() ?: 0
            val rating = card.selectFirst(".item__rating")?.text()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            // «2 из 12+» — вышло/всего.
            val counter = Regex("""(\d+)\s+из\s+(\d+)""").find(card.selectFirst(".vysser")?.text().orEmpty())
            Anime(
                id = "$PREFIX${m.groupValues[1]}:${m.groupValues[2]}",
                title = title.substringBefore(" / ").trim(),
                poster = poster,
                year = year,
                // Плашка `.item__rating` — десятибалльная оценка сайта.
                rating = rating,
                ratingMax = 10.0,
                episodesAvailable = counter?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                episodesTotal = counter?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            )
        }.distinctBy { it.id }
    }
}

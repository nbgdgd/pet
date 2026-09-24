package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.network.HttpClient
import org.json.JSONArray
import org.jsoup.Jsoup
import timber.log.Timber

/**
 * SameBand (sameband.studio) — студия дубляжа со СВОИМ CDN: HLS 480/720/1080 лежит
 * на самом сайте, без Kodik. Каталог маленький (94 тайтла на 17.09.2026), зато
 * поток независим от всех остальных хостингов приложения.
 *
 * Сайт на DLE, API нет — разбор HTML (Jsoup). Проверено:
 *   • каталог: `/anime/` — все тайтлы одной страницей, `article.shortstory`
 *     с `a.image[href=/anime/{newsId}-{slug}.html]` и `img`;
 *   • поиск: `GET /index.php?do=search&subaction=search&story=<query>`;
 *   • страница тайтла: `iframe[src^=/v/play/]` → страница плеера с
 *     `file: "/v/list/<name>_list.txt"`;
 *   • плейлист — JSON playerjs: `[{title: "<img…>Серия 01", file:
 *     "[480p]/v/…/index.m3u8,[720p]…,[1080p]…"}]`. Один файл даёт и список серий,
 *     и все качества.
 *
 * Идентификаторы: `sb:{newsId}:{slug}`.
 */
class SameBandSource(
    private val http: HttpClient,
) : ContentAggregator {

    override val name: String = "SameBand"
    override fun ownsContentId(contentId: String): Boolean = contentId.startsWith(PREFIX)

    override suspend fun search(query: String): List<Anime> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val html = http.getHtml("$SITE/index.php?do=search&subaction=search&story=$encoded") ?: return emptyList()
        return parseCards(html)
    }

    override suspend fun trending(): List<Anime> = catalogPage(0, 0)

    override suspend fun catalog(category: String): List<Anime> = catalogPage(0, 0)

    /** Каталог — одна страница; дальше первой отдаём пусто, чтобы лента не зациклилась. */
    override suspend fun catalogPage(sort: Int, page: Int): List<Anime> {
        if (page > 0) return emptyList()
        val html = http.getHtml("$SITE/anime/") ?: return emptyList()
        return parseCards(html)
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> =
        playlist(contentId).map { ep ->
            Segment(
                id = ep.variants.first().url,
                contentId = contentId,
                number = ep.number,
                title = "Серия ${ep.number}",
            )
        }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val ep = playlist(contentId).firstOrNull { it.number == segment } ?: return null
        val best = ep.variants.first()
        return ContentResult(
            location = best.url,
            quality = best.quality,
            variants = ep.variants,
            source = name,
            referer = "$SITE/",
            metadata = mapOf("contentId" to contentId, "segment" to segment.toString()),
        )
    }

    override suspend fun validateSource(contentId: String): Boolean = http.isReachable("$SITE/")

    // ---- плейлист ----

    data class PlaylistEpisode(val number: Int, val variants: List<StreamVariant>)

    private suspend fun playlist(contentId: String): List<PlaylistEpisode> {
        val page = pageUrl(contentId) ?: return emptyList()
        val html = http.getHtml(page) ?: return emptyList()
        val player = Jsoup.parse(html, SITE).selectFirst("iframe[src*=/v/play/]")?.absUrl("src")
            ?: return emptyList<PlaylistEpisode>().also { Timber.w("[SameBand] no player iframe: %s", page) }
        val playerHtml = http.getHtml(player, referer = page) ?: return emptyList()
        val listPath = LIST.find(playerHtml)?.groupValues?.get(1)
            ?: return emptyList<PlaylistEpisode>().also { Timber.w("[SameBand] player without list: %s", player) }
        val listUrl = absolute(listPath).let { url ->
            // Имена файлов с пробелами — кодируем только их, остальное уже валидно.
            url.replace(" ", "%20")
        }
        val json = http.getHtml(listUrl, referer = player) ?: return emptyList()
        return parsePlaylist(json)
    }

    companion object {
        const val SITE = "https://sameband.studio"
        private const val PREFIX = "sb:"
        private val DETAIL = Regex("""^https?://[^/]+/anime/(\d+)-([a-z0-9\-]+)\.html$""")
        private val LIST = Regex("""file:\s*"([^"]+\.txt)"""")
        private val QUALITY_ITEM = Regex("""\[(\d+)p\]\s*([^,\[]+)""")

        fun pageUrl(contentId: String): String? {
            val parts = contentId.removePrefix(PREFIX).split(':', limit = 2)
            val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
            val slug = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            return "$SITE/anime/$id-$slug.html"
        }

        /**
         * Разбор playerjs-плейлиста. Номер серии — число из `title` после снятия HTML
         * («<img …>Серия 01» → 1); качества — `[480p]url,[720p]url,…`, лучшее первым.
         * Открыт для теста на фикстуре.
         */
        fun parsePlaylist(json: String): List<PlaylistEpisode> {
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                // В title кроме «Серия 01» сидит длительность «25:31» — берём число
                // после слова «Серия», иначе последнее число.
                val title = Jsoup.parse(item.optString("title")).text()
                val number = Regex("""Серия\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\d+""").findAll(title).lastOrNull()?.value?.toIntOrNull()
                    ?: (i + 1)
                val variants = QUALITY_ITEM.findAll(item.optString("file")).map { m ->
                    StreamVariant("${m.groupValues[1]}p", absolute(m.groupValues[2].trim()).replace(" ", "%20"))
                }.toList().distinctBy { it.quality }
                    .sortedByDescending { it.quality.dropLast(1).toIntOrNull() ?: 0 }
                if (variants.isEmpty()) return@mapNotNull null
                PlaylistEpisode(number, variants)
            }.distinctBy { it.number }.sortedBy { it.number }
        }

        private fun absolute(path: String): String = when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> SITE + path
            else -> "$SITE/$path"
        }
    }

    // ---- каталог ----

    /** Только карточки `div.poster` (каталог и поиск) — без текстовых ссылок из меню. */
    private fun parseCards(html: String): List<Anime> {
        val doc = Jsoup.parse(html, SITE)
        return doc.select("div.poster").mapNotNull { card ->
            val a = card.select("a[href]").firstOrNull { DETAIL.containsMatchIn(it.absUrl("href")) }
                ?: return@mapNotNull null
            val href = a.absUrl("href")
            val m = DETAIL.find(href) ?: return@mapNotNull null
            if (!href.startsWith(SITE)) return@mapNotNull null
            val title = card.attr("title").ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
                .ifBlank { a.text() }.trim()
            if (title.length < 2) return@mapNotNull null
            if (BannedContent.isBanned(title)) return@mapNotNull null
            val img = card.selectFirst("img")
            val poster = img?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }.orEmpty()
            if (poster.isBlank()) return@mapNotNull null
            val episodes = Regex("""Ep\.?\s*(\d+)""").find(card.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Anime(
                id = "$PREFIX${m.groupValues[1]}:${m.groupValues[2]}",
                title = title,
                poster = poster,
                episodesAvailable = episodes,
            )
        }.distinctBy { it.id }
    }
}

package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.network.HttpClient
import org.jsoup.Jsoup
import timber.log.Timber
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kinozapas (kinozapas.net) source for the "Кино" area — films & series.
 *
 * Serves plain markup (no Cloudflare wall) and embeds the same `api.ortified.ws`
 * (Collaps) balancer as [LordfilmSource] / [ZetflixSource], so playback is
 * identical — a signed HLS master that plays worldwide with dub audio tracks and
 * quality renditions inline. It differs only in catalog markup: cards appear in
 * two layouts (home carousels `a.tc-img` + `.tc-title`; genre/listing grids
 * `article.movie-box`), so we parse them generically by anchoring on the poster
 * image and climbing to its detail link.
 */
@Singleton
class KinozapasSource @Inject constructor(
    private val http: HttpClient,
) : CinemaSource {

    override val name: String = "Kinozapas"
    override val alwaysActive: Boolean = true

    override val key: String = "kinozapas"
    override val displayName: String = "Kinozapas"
    override val drawbacks: String =
        "Пагинация по жанрам работает. Каталог в двух вёрстках — иногда карточка без года/" +
            "постера. Озвучки только языковые (без имён студий). Сайт менее стабилен, чем Lordfilm."
    override val paginates: Boolean = true
    override val categories: List<Pair<String, String>> = listOf(
        "" to "Новинки",
        "filmo/boeviky" to "Боевики",
        "filmo/komedian" to "Комедии",
        "filmo/drama" to "Драмы",
        "filmo/melodrama" to "Мелодрамы",
        "filmo/fantastic" to "Фантастика",
        "filmo/triller" to "Триллеры",
        "filmo/horrors" to "Ужасы",
        "filmo/detektiv" to "Детективы",
        "filmo/priklyucheniya" to "Приключения",
    )

    override fun owns(contentId: String): Boolean = isKinozapas(contentId.substringBefore(":t"))

    private val base = "https://kinozapas.net"

    override suspend fun search(query: String): List<Anime> = emptyList()
    override suspend fun trending(): List<Anime> = emptyList()

    // --- cinema public API ---

    /** Browse a listing: "" = home (newest), or a `filmo/<genre>` path. Pagination
     *  is `/<path>/page/N/` (or `/page/N/` on the home feed). */
    override suspend fun browsePath(path: String, page: Int): List<Anime> {
        val p = page.coerceAtLeast(1)
        val clean = path.trim('/')
        val url = buildString {
            append(base)
            if (clean.isNotEmpty()) append('/').append(clean)
            if (p > 1) append("/page/").append(p)
            append('/')
        }
        return parseCards(http.getHtml(url, referer = "$base/"))
    }

    override suspend fun searchCinema(query: String): List<Anime> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$base/index.php?do=search&subaction=search&search_start=1&full_search=0&story=$q"
        return parseCards(http.getHtml(url, referer = "$base/"))
    }

    // --- routing methods ---

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val url = contentId.substringBefore(":t")
        if (!isKinozapas(url)) return emptyList()
        return listOf(Segment(id = "$url#1", contentId = contentId, number = 1, title = "Смотреть"))
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val url = contentId.substringBefore(":t")
        if (!isKinozapas(url)) return null
        val embed = balancerEmbedFor(url) ?: run {
            Timber.w("[Kinozapas] no balancer embed for %s", url); return null
        }
        val player = http.getHtml(embed, referer = url) ?: return null
        val raw = M3U8.find(player)?.value?.replace("\\/", "/") ?: run {
            Timber.w("[Kinozapas] no m3u8 in embed %s", embed); return null
        }
        val master = unwrapProxy(raw)
        val ref = authorityOf(embed)
        return ContentResult(
            location = master,
            quality = "Auto",
            source = name,
            referer = ref,
            variants = qualityVariants(master, ref),
        )
    }

    override suspend fun kinopoiskId(contentId: String): String? {
        val url = contentId.substringBefore(":t")
        if (!isKinozapas(url)) return null
        return kpIdFromHtml(http.getHtml(url, referer = "$base/"))
    }

    override suspend fun validateSource(contentId: String): Boolean = http.isReachable(base)

    // --- helpers ---

    private fun isKinozapas(id: String): Boolean = id.startsWith("http") && id.contains("kinozapas")

    /** The Collaps/ortified embed URL on a detail page (usually a `data-iframe`). */
    override suspend fun balancerEmbedFor(pageUrl: String): String? {
        val url = pageUrl.substringBefore(":t")
        if (!isKinozapas(url)) return null
        val html = http.getHtml(url, referer = "$base/") ?: return null
        return ORTIFIED_EMBED.find(html)?.value?.let(::withScheme)
            ?: EMBED_IFRAME.find(html)?.groupValues?.get(1)?.let(::withScheme)
    }

    private suspend fun qualityVariants(master: String, referer: String): List<StreamVariant> {
        val auto = StreamVariant("Auto", master)
        val txt = http.getHtml(master, referer = referer) ?: return listOf(auto)
        if (!txt.contains("#EXT-X-STREAM-INF")) return listOf(auto)
        val res = Regex("""RESOLUTION=(\d+)x(\d+)""").findAll(txt)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .distinct().sortedByDescending { it.first }.toList()
        if (res.isEmpty()) return listOf(auto)
        return buildList {
            add(auto)
            res.forEach { (w, h) -> add(StreamVariant(qualityLabel(w), "$master#h=$h")) }
        }.distinctBy { it.quality }
    }

    private fun qualityLabel(width: Int): String = when {
        width >= 1900 -> "1080p"
        width >= 1200 -> "720p"
        width >= 800 -> "480p"
        else -> "360p"
    }

    /**
     * Cards in both layouts wrap a poster `img` (`/uploads/`) inside a detail
     * `a[href*=.html]`. Anchor on those images and climb to the link; take the
     * title from the anchor text, the image `alt`, or a `.tc-title` sibling.
     */
    private fun parseCards(html: String?): List<Anime> {
        html ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        return doc.select("img[src*=/uploads/], img[data-src*=/uploads/]").mapNotNull { img ->
            val a = img.parents().firstOrNull { it.tagName() == "a" && it.attr("href").contains(".html") }
                ?: return@mapNotNull null
            val id = a.absUrl("href").ifBlank { return@mapNotNull null }
            if (!isKinozapas(id) || !DETAIL_URL.containsMatchIn(id)) return@mapNotNull null
            val card = a.closest("article, li, div.movie-box, div") ?: a
            val title = listOf(
                a.text(),
                img.attr("alt"),
                card.selectFirst(".tc-title, .movie-box__title, .title")?.text().orEmpty(),
            ).map { it.substringBefore("(").trim() }.firstOrNull { it.length >= 2 } ?: return@mapNotNull null
            val poster = img.absUrl("data-src").ifBlank { img.absUrl("src") }
            val year = Regex("(19|20)\\d{2}").find(card.text())?.value?.toIntOrNull() ?: 0
            Anime(id = id, title = title, poster = poster, year = year, status = cinemaQualityBadge(card.text()))
        }.distinctBy { it.id }
    }

    /** Unwrap a `…?m=<url-encoded real url>` balancer proxy to the inner URL. */
    private fun unwrapProxy(url: String): String {
        val m = Regex("""[?&]m=([^&]+)""").find(url)?.groupValues?.get(1) ?: return url
        val decoded = runCatching { java.net.URLDecoder.decode(m, "UTF-8") }.getOrDefault(m)
        return if (decoded.startsWith("http") && decoded.contains(".m3u8")) decoded else url
    }

    private fun withScheme(url: String): String = if (url.startsWith("//")) "https:$url" else url

    private fun authorityOf(url: String): String =
        runCatching { URI(withScheme(url)).let { "${it.scheme}://${it.host}/" } }.getOrDefault(url)

    companion object {
        private val DETAIL_URL = Regex("""/\d+-[^/]*\.html""")
        private val M3U8 = Regex("""https?://[^"'\s\\]+?\.m3u8[^"'\s\\]*""")
        private val ORTIFIED_EMBED =
            Regex("""(https?:)?//[^"'\s]*ortified[^"'\s]*/embed/(?:movie|serial|tv)/\d+[^"'\s]*""")
        private val EMBED_IFRAME =
            Regex("""(?:src|data-iframe)="((?:https?:)?//[^"]+/embed/[^"]+)"""")
    }
}

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
 * Lordfilm (lordfilm.org) source for the "Кино" area — films & series.
 *
 * Chosen over zetflix because its `/films/` and `/serialy/` listings **paginate**
 * (`/films/page/N/`, 10+ pages) — so the catalog supports real infinite scroll,
 * unlike zetflix which serves one fixed page. Serves plain markup to an ordinary
 * OkHttp client (no Cloudflare wall) and embeds the `api.ortified.ws` (Collaps)
 * balancer whose `/embed` page carries a ready, signed HLS master URL. The master
 * bundles the dub audio tracks and quality renditions, which the player surfaces.
 *
 * [ZetflixSource] is kept as a fallback source (not wired by default).
 */
@Singleton
class LordfilmSource @Inject constructor(
    private val http: HttpClient,
) : CinemaSource {

    override val name: String = "Lordfilm"
    override val alwaysActive: Boolean = true

    override val key: String = "lordfilm"
    override val displayName: String = "Lordfilm"
    override val drawbacks: String =
        "Пагинация есть — большой каталог с бесконечной прокруткой. Озвучки только языковые " +
            "(без имён студий). Рейтинг KP есть не у всех карточек. Мастер иногда завёрнут в " +
            "прокси (обходится автоматически)."
    override val paginates: Boolean = true
    override val categories: List<Pair<String, String>> = listOf(
        "films" to "Фильмы",
        "serialy" to "Сериалы",
        "films/boevik" to "Боевики",
        "films/komediya" to "Комедии",
        "films/drama" to "Драмы",
        "films/melodrama" to "Мелодрамы",
        "films/fantastika" to "Фантастика",
        "films/uzhasy" to "Ужасы",
        "films/trillery" to "Триллеры",
        "films/detektiv" to "Детективы",
        "films/priklyucheniya" to "Приключения",
    )

    override fun owns(contentId: String): Boolean = isLordfilm(contentId.substringBefore(":t"))

    private val base = "https://lordfilm.org"

    // Cinema never merges into the anime catalog.
    override suspend fun search(query: String): List<Anime> = emptyList()
    override suspend fun trending(): List<Anime> = emptyList()

    // --- cinema public API (repository → "Кино" tab) ---

    /** Browse a listing: "films" = all films (paginated), "serialy" = all series,
     *  or a genre path. Pagination is `/<path>/page/N/`. */
    override suspend fun browsePath(path: String, page: Int): List<Anime> {
        val p = page.coerceAtLeast(1)
        val clean = path.trim('/').ifBlank { "films" }
        val url = buildString {
            append(base).append('/').append(clean).append('/')
            if (p > 1) append("page/").append(p).append('/')
        }
        return parseCards(http.getHtml(url, referer = "$base/"))
    }

    override suspend fun searchCinema(query: String): List<Anime> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$base/index.php?do=search&subaction=search&search_start=1&full_search=0&story=$q"
        return parseCards(http.getHtml(url, referer = "$base/"))
    }

    // --- routing methods (recognise lordfilm page URLs) ---

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val url = contentId.substringBefore(":t")
        if (!isLordfilm(url)) return emptyList()
        return listOf(Segment(id = "$url#1", contentId = contentId, number = 1, title = "Смотреть"))
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val url = contentId.substringBefore(":t")
        if (!isLordfilm(url)) return null
        val embed = balancerEmbedFor(url) ?: run {
            Timber.w("[Lordfilm] no balancer embed for %s", url); return null
        }
        val player = http.getHtml(embed, referer = url) ?: return null
        val raw = M3U8.find(player)?.value?.replace("\\/", "/") ?: run {
            Timber.w("[Lordfilm] no m3u8 in embed %s", embed); return null
        }
        // Lordfilm's balancer wraps the real master in a proxy that 403s a direct
        // client: https://dl.showvid.ws/x-px?m=<url-encoded interkh master.m3u8…>.
        // Unwrap the `m=` param to the playable interkh URL (which the CDN serves).
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
        if (!isLordfilm(url)) return null
        return kpIdFromHtml(http.getHtml(url, referer = "$base/"))
    }

    override suspend fun validateSource(contentId: String): Boolean = http.isReachable(base)

    // --- helpers ---

    private fun isLordfilm(id: String): Boolean = id.startsWith("http") && id.contains("lordfilm")

    /** The Collaps/ortified embed URL on a detail page — a ready m3u8. */
    override suspend fun balancerEmbedFor(pageUrl: String): String? {
        val url = pageUrl.substringBefore(":t")
        if (!isLordfilm(url)) return null
        val html = http.getHtml(url, referer = "$base/") ?: return null
        return ORTIFIED_EMBED.find(html)?.value?.let(::withScheme)
            ?: EMBED_IFRAME.find(html)?.groupValues?.get(1)?.let(::withScheme)
    }

    /** The HLS master's quality renditions, newest-first, as picker entries. Each
     *  points back at the master with a `#h=<height>` marker the player turns into
     *  a libVLC `adaptive-maxheight` cap (audio/dub tracks stay intact). */
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
     * Cards: `div.item` grids and search results alike wrap a poster `img` and a
     * detail link `a.item__title` (title as text). Anchor on those titles and climb
     * to the nearest card ancestor for the poster.
     */
    private fun parseCards(html: String?): List<Anime> {
        html ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        // Anchor on the title links (browse + search both use `item__title`) and
        // climb to the card container for the poster / year / KP rating.
        return doc.select("a.item__title, a.item-card__title").mapNotNull { a ->
            val id = a.absUrl("href").ifBlank { return@mapNotNull null }
            if (!isLordfilm(id) || !DETAIL_URL.containsMatchIn(id)) return@mapNotNull null
            val title = a.text().substringBefore("(").trim()
            if (title.length < 2) return@mapNotNull null
            val card = a.closest("div.item, div.item-card, li, article")
            val img = card?.selectFirst("img[src*=/uploads/], img[data-src*=/uploads/], img")
            val poster = img?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }.orEmpty()
            val year = Regex("(19|20)\\d{2}").find(card?.selectFirst(".item__year")?.text() ?: a.text())
                ?.value?.toIntOrNull() ?: 0
            val rating = card?.selectFirst(".item__rates-item.kp")?.text()
                ?.replace(',', '.')?.let { Regex("""\d+\.?\d*""").find(it)?.value?.toDoubleOrNull() } ?: 0.0
            // Оценка снята с плашки КиноПоиска — она десятибалльная.
            Anime(
                id = id, title = title, poster = poster, year = year, rating = rating, ratingMax = 10.0,
                status = cinemaQualityBadge(card?.text().orEmpty()),
            )
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
            Regex("""<iframe[^>]+(?:src|data-src)="((?:https?:)?//[^"]+/embed/[^"]+)"""")
    }
}

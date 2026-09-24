package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.network.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import timber.log.Timber
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zetflix (zetflix.club) source for the "Кино" area — films & series.
 *
 * Serves plain markup to an ordinary OkHttp client (no Cloudflare wall) and, on a
 * detail page, embeds the `api.ortified.ws` (Collaps) balancer whose `/embed` page
 * carries a ready, freshly-signed HLS **master** URL as a plain literal — no cipher
 * to undo, and its CDN plays worldwide without a VPN. The master bundles the dub
 * audio tracks (`EXT-X-MEDIA:TYPE=AUDIO`) and quality renditions; we surface the
 * qualities as a picker (via `#h=<height>` → libVLC `adaptive-maxheight`) and let
 * the player switch dubs through the audio tracks.
 */
@Singleton
class ZetflixSource @Inject constructor(
    private val http: HttpClient,
) : CinemaSource {

    override val name: String = "Zetflix"
    override val alwaysActive: Boolean = true

    override val key: String = "zetflix"
    override val displayName: String = "Zetflix"
    override val drawbacks: String =
        "Рейтинг не парсится. Озвучки только языковые (без имён студий)."
    override val paginates: Boolean = true
    override val categories: List<Pair<String, String>> = listOf(
        "" to "Новинки",
        "boevik" to "Боевики",
        "komedija" to "Комедии",
        "drama" to "Драмы",
        "melodramy" to "Мелодрамы",
        "fantasticheskie" to "Фантастика",
        "fentezi" to "Фэнтези",
        "trillery" to "Триллеры",
        "uzhasy" to "Ужасы",
        "detektivy" to "Детективы",
        "prikljuchenija" to "Приключения",
    )

    override fun owns(contentId: String): Boolean = isZetflix(contentId.substringBefore(":t"))

    private val base = "https://www.zetflix.club"
    private val lumexJson = Json { ignoreUnknownKeys = true }

    // Cinema never merges into the anime catalog.
    override suspend fun search(query: String): List<Anime> = emptyList()
    override suspend fun trending(): List<Anime> = emptyList()

    // --- cinema public API (repository → "Кино" tab) ---

    /** Browse a listing: "" = home (newest), a genre slug like "boevik", … .
     *  Pagination is `/<path>/page/N/` (or `/page/N/` on the home feed). */
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

    // --- routing methods (recognise zetflix page URLs) ---

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val url = contentId.substringBefore(":t")
        if (!isZetflix(url)) return emptyList()
        // A title plays as one unit — the balancer's HLS master carries every dub
        // and quality inline, so there are no per-episode segments to route.
        return listOf(Segment(id = "$url#1", contentId = contentId, number = 1, title = "Смотреть"))
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val url = contentId.substringBefore(":t")
        if (!isZetflix(url)) return null
        // Prefer Lumex (cdnvideohub): it names dubs by STUDIO (Дубляж/Многоголосый/
        // студии) and streams from OK.ru. Fall back to the ortified balancer
        // (language-only dubs) when the page has no Lumex component or it returns nothing.
        return runCatching { lumexContent(contentId, url) }.getOrNull() ?: ortifiedContent(url)
    }

    /** Ortified/Collaps balancer — one HLS master with language-coded audio tracks. */
    private suspend fun ortifiedContent(url: String): ContentResult? {
        val embed = balancerEmbedFor(url) ?: run {
            Timber.w("[Zetflix] no balancer embed for %s", url); return null
        }
        val player = http.getHtml(embed, referer = url) ?: return null
        val raw = M3U8.find(player)?.value?.replace("\\/", "/") ?: run {
            Timber.w("[Zetflix] no m3u8 in embed %s", embed); return null
        }
        // Some balancer variants wrap the master in a `…?m=<url-encoded url>` proxy
        // that 403s a direct client — unwrap it to the playable CDN URL.
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

    /**
     * Lumex (cdnvideohub) — the detail page embeds a `<video-player
     * data-title-id=<kpId> data-publisher-id=…>` component. Its API returns dubs
     * named by STUDIO, each a separate OK.ru HLS stream, so we surface them as
     * [Translation]s and the player's озвучка picker switches between them via the
     * `:t<index>` id (each pick re-resolves that studio's fresh signed stream).
     */
    private suspend fun lumexContent(contentId: String, url: String): ContentResult? {
        val html = http.getHtml(url, referer = "$base/") ?: return null
        val titleId = LUMEX_TITLE.find(html)?.groupValues?.get(1) ?: return null
        val pub = LUMEX_PUB.find(html)?.groupValues?.get(1) ?: DEFAULT_PUB
        val plRaw = http.getHtml("$LUMEX_API/player/sv/playlist?pub=$pub&aggr=kp&id=$titleId", referer = LUMEX_REF) ?: return null
        val items = runCatching { lumexJson.decodeFromString<LumexPlaylist>(plRaw) }.getOrNull()
            ?.items?.filter { it.vkId.isNotBlank() }.orEmpty()
        if (items.isEmpty()) return null
        val translations = items.mapIndexed { i, item ->
            val label = listOf(item.voiceStudio, item.voiceType).filter(String::isNotBlank).joinToString(" · ")
            Translation(i, label.ifBlank { "Озвучка ${i + 1}" })
        }
        val idx = (contentId.substringAfter(":t", "").toIntOrNull() ?: 0).coerceIn(0, items.lastIndex)
        val vidRaw = http.getHtml("$LUMEX_API/player/sv/video/${items[idx].vkId}?pub=$pub", referer = LUMEX_REF) ?: return null
        val src = runCatching { lumexJson.decodeFromString<LumexVideo>(vidRaw) }.getOrNull()?.sources ?: return null
        // Real quality ladder from OK.ru's per-quality MP4s (best first). The bare
        // MP4 urls play directly in VLC; each pick just re-plays that url.
        val variants = listOf(
            "2160p" to src.mpeg4kUrl,
            "1440p" to src.mpeg2kUrl,
            "1080p" to src.mpegFullHdUrl,
            "720p" to src.mpegHighUrl,
            "480p" to src.mpegMediumUrl,
            "360p" to src.mpegLowUrl,
            "240p" to src.mpegLowestUrl,
        ).filter { it.second.isNotBlank() }.map { StreamVariant(it.first, it.second) }
        val location = variants.firstOrNull()?.url
            ?: src.hlsUrl.takeIf { it.contains(".m3u8") }
            ?: return null
        return ContentResult(
            location = location,
            quality = variants.firstOrNull()?.quality ?: "Auto",
            source = name,
            referer = LUMEX_REF,
            variants = variants.ifEmpty { listOf(StreamVariant("Auto", location)) },
            translations = translations,
            translationId = idx,
        )
    }

    override suspend fun kinopoiskId(contentId: String): String? {
        val url = contentId.substringBefore(":t")
        if (!isZetflix(url)) return null
        return kpIdFromHtml(http.getHtml(url, referer = "$base/"))
    }

    override suspend fun validateSource(contentId: String): Boolean = http.isReachable(base)

    // --- helpers ---

    private fun isZetflix(id: String): Boolean = id.startsWith("http") && id.contains("zetflix")

    /** The Collaps/ortified embed URL on a detail page — a ready m3u8, plays
     *  worldwide. Falls back to any other `/embed/` iframe if ortified is absent. */
    override suspend fun balancerEmbedFor(pageUrl: String): String? {
        val url = pageUrl.substringBefore(":t")
        if (!isZetflix(url)) return null
        val html = http.getHtml(url, referer = "$base/") ?: return null
        return ORTIFIED_EMBED.find(html)?.value?.let(::withScheme)
            ?: EMBED_IFRAME.find(html)?.groupValues?.get(1)?.let(::withScheme)
    }

    /** The HLS master's quality renditions, newest-first, as picker entries. Each
     *  points back at the master with a `#h=<height>` marker the player turns into
     *  a libVLC `adaptive-maxheight` cap (so audio/dub tracks stay intact); "Auto"
     *  keeps full adaptive streaming. */
    private suspend fun qualityVariants(master: String, referer: String): List<StreamVariant> {
        val auto = StreamVariant("Auto", master)
        val txt = http.getHtml(master, referer = referer) ?: return listOf(auto)
        if (!txt.contains("#EXT-X-STREAM-INF")) return listOf(auto)
        val res = Regex("""RESOLUTION=(\d+)x(\d+)""").findAll(txt)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() } // (width, height)
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
     * Cards come in two markups: catalog grids use `a.item-poster__title`, search
     * results use `a.item-card__title`. Both are detail links (`/NNNN-…​.html`) with
     * the film title as their text, so we anchor on every such link (skipping the
     * text-less poster-image links) and climb to the nearest card ancestor for the
     * `/uploads/` poster.
     */
    private fun parseCards(html: String?): List<Anime> {
        html ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        return doc.select("a[href*=.html]").mapNotNull { a ->
            val id = a.absUrl("href").ifBlank { return@mapNotNull null }
            if (!isZetflix(id) || !DETAIL_URL.containsMatchIn(id)) return@mapNotNull null
            // "Интерстеллар (Фильм)" / "Матрица (1999)" → drop the trailing tag.
            val title = a.text().substringBefore("(").trim()
            if (title.length < 2) return@mapNotNull null // text-less poster/overlay links
            val img = a.parents().firstNotNullOfOrNull { p ->
                p.selectFirst("img[src*=/uploads/], img[data-src*=/uploads/]")
            }
            val poster = img?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }.orEmpty()
            val year = Regex("(19|20)\\d{2}").find(a.text())?.value?.toIntOrNull() ?: 0
            Anime(
                id = id, title = title, poster = poster, year = year,
                status = cinemaQualityBadge(a.closest("article, li, div")?.text().orEmpty()),
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

    /** `https://host/` of a URL — the Referer the balancer CDN expects. */
    private fun authorityOf(url: String): String =
        runCatching { URI(withScheme(url)).let { "${it.scheme}://${it.host}/" } }.getOrDefault(url)

    companion object {
        private val DETAIL_URL = Regex("""/\d+-[^/]*\.html""")
        private val M3U8 = Regex("""https?://[^"'\s\\]+?\.m3u8[^"'\s\\]*""")
        private val ORTIFIED_EMBED =
            Regex("""(https?:)?//[^"'\s]*ortified[^"'\s]*/embed/(?:movie|serial|tv)/\d+[^"'\s]*""")
        private val EMBED_IFRAME =
            Regex("""<iframe[^>]+src="((?:https?:)?//[^"]+/embed/[^"]+)"""")

        // Lumex (cdnvideohub) — the `<video-player>` component + its playlist/video API.
        private const val LUMEX_API = "https://plapi.cdnvideohub.com/api/v1"
        private const val LUMEX_REF = "https://player.cdnvideohub.com/"
        private const val DEFAULT_PUB = "2932"
        private val LUMEX_TITLE = Regex("""data-title-id="(\d+)"""")
        private val LUMEX_PUB = Regex("""data-publisher-id="(\d+)"""")
    }
}

@Serializable
private data class LumexPlaylist(val items: List<LumexItem> = emptyList())

@Serializable
private data class LumexItem(val vkId: String = "", val voiceStudio: String = "", val voiceType: String = "")

@Serializable
private data class LumexVideo(val sources: LumexSources? = null)

@Serializable
private data class LumexSources(
    val hlsUrl: String = "",
    // OK.ru progressive single-quality MP4s. The adaptive hlsUrl caps at a low
    // resolution, so we prefer these for real 1080p/720p (and proper audio).
    val mpeg4kUrl: String = "",
    val mpeg2kUrl: String = "",
    val mpegQhdUrl: String = "",
    val mpegFullHdUrl: String = "",
    val mpegHighUrl: String = "",
    val mpegMediumUrl: String = "",
    val mpegLowUrl: String = "",
    val mpegLowestUrl: String = "",
)

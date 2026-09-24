package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.flow.first
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import timber.log.Timber
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HDrezka (rezka.ag) source for the "Кино" area — films, series and cartoons.
 *
 * It is kept separate from the anime catalog: the anime-merge methods
 * ([search], [trending], catalog…) return nothing, so movies never leak into
 * the anime home/search. Cinema browsing goes through [browse]/[searchCinema]
 * (called by the repository for the Кино tab), while playback routing
 * ([getContentSegments]/[extractContent]) recognises Rezka page URLs by id.
 *
 * Stream extraction mirrors the site's own flow:
 *   1. load the film/series page → parse `initCDN{Movies|Series}Events(id, translator_id, …)`
 *   2. POST /ajax/get_cdn_series/ (action=get_movie | get_stream)
 *   3. de-obfuscate the returned `url` ("clearTrash") → `[quality]url or url2,…`
 */
@Singleton
class RezkaSource @Inject constructor(
    private val http: HttpClient,
    private val settings: SettingsDataStore,
) : ContentAggregator {

    override val name: String = "Rezka"

    /** Always available for id-routing (segments/stream) even when only anime
     *  sources are toggled on — cinema ids are Rezka page URLs. */
    override val alwaysActive: Boolean = true

    /**
     * Mirror-aware base URL: rezka.ag is RKN-blocked, mirrors rotate — the
     * user can point it at any working mirror in Settings → Кино.
     */
    private suspend fun baseUrl(): String =
        settings.settings.first().rezkaBaseUrl.trimEnd('/')

    private suspend fun baseHost(): String =
        runCatching { URI(baseUrl()).host.orEmpty().lowercase() }.getOrDefault("")

    enum class Kind(val path: String, val label: String) {
        FILM("films", "Фильмы"),
        SERIES("series", "Сериалы"),
        CARTOON("cartoons", "Мультфильмы"),
    }

    // --- anime-merge methods stay empty so cinema never pollutes the anime catalog ---
    override suspend fun search(query: String): List<Anime> = emptyList()
    override suspend fun trending(): List<Anime> = emptyList()

    // --- cinema public API (used by the repository for the "Кино" tab) ---
    suspend fun browse(kind: Kind, page: Int): List<Anime> {
        val base = baseUrl()
        return parseCards(
            http.getHtml("$base/${kind.path}/page/${page.coerceAtLeast(1)}/", referer = base),
            base,
            baseHost(),
        )
    }

    suspend fun searchCinema(query: String): List<Anime> {
        val base = baseUrl()
        val q = URLEncoder.encode(query, "UTF-8")
        return parseCards(
            http.getHtml("$base/search/?do=search&subaction=search&q=$q", referer = base),
            base,
            baseHost(),
        )
    }

    // --- routing methods (real work; recognise Rezka page URLs) ---
    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val url = contentId.substringBefore(":t")
        if (!isRezka(url)) return emptyList()
        val base = baseUrl()
        val html = http.getHtml(url, referer = base) ?: return emptyList()
        if (!html.contains("initCDNSeriesEvents")) {
            // A film → a single playable segment.
            return listOf(Segment(id = "$url#m", contentId = contentId, number = 1, title = "Смотреть"))
        }
        val eps = parseEpisodes(Jsoup.parse(html, base))
        episodeCache[url] = eps
        val multiSeason = eps.mapTo(HashSet()) { it.first }.size > 1
        return eps.mapIndexed { i, (s, e) ->
            Segment(
                id = "$url#s${s}e$e",
                contentId = contentId,
                number = i + 1,
                // Same shape the desktop app groups seasons by ("С2 · Серия 5"), so a
                // multi-season series reads as its own seasons instead of one long run.
                title = if (multiSeason) "С$s · Серия $e" else "Серия $e",
            )
        }
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val url = contentId.substringBefore(":t")
        if (!isRezka(url)) return null
        val base = baseUrl()
        val overrideTr = contentId.substringAfter(":t", "").toIntOrNull()
        val html = http.getHtml(url, referer = base) ?: return null
        val doc = Jsoup.parse(html, base)
        val ev = parseEvents(html) ?: return null
        val translators = parseTranslators(doc, ev.translatorId)
        val trId = overrideTr ?: ev.translatorId
        val opt = translatorOptions(doc, trId)

        val params: Map<String, String> = if (!ev.isSeries) {
            mapOf(
                "id" to ev.id, "translator_id" to trId.toString(),
                "is_camrip" to opt.camrip, "is_ads" to opt.ads, "is_director" to opt.director,
                "action" to "get_movie",
            )
        } else {
            val eps = episodeCache[url] ?: parseEpisodes(doc).also { episodeCache[url] = it }
            if (eps.isEmpty()) return null
            val (season, episode) = eps[(segment - 1).coerceIn(0, eps.size - 1)]
            mapOf(
                "id" to ev.id, "translator_id" to trId.toString(),
                "season" to season, "episode" to episode, "action" to "get_stream",
            )
        }

        val json = http.postForm(
            "$base/ajax/get_cdn_series/?t=${System.currentTimeMillis()}",
            params, referer = url,
        ) ?: return null
        if (!json.optBoolean("success", false)) {
            Timber.w("[Rezka] cdn success=false for %s", url); return null
        }
        val raw = json.optString("url", "")
        if (raw.isBlank()) { Timber.w("[Rezka] empty stream url for %s", url); return null }

        val variants = parseStreams(decodeTrash(raw).ifBlank { raw })
        val best = variants.firstOrNull() ?: run {
            Timber.w("[Rezka] no parseable variants for %s", url); return null
        }
        return ContentResult(
            location = best.url,
            quality = best.quality,
            source = name,
            referer = base,
            variants = variants,
            translations = translators.takeIf { it.size > 1 },
            translationId = trId,
        )
    }

    override suspend fun validateSource(contentId: String): Boolean = http.isReachable(baseUrl())

    // --- parsing helpers ---

    private suspend fun isRezka(id: String): Boolean {
        if (!id.startsWith("http")) return false
        if (id.contains("rezka")) return true
        val mirror = baseHost()
        return mirror.isNotBlank() &&
            (id.startsWith(baseUrl()) || runCatching { URI(id).host.orEmpty().lowercase() }
                .getOrDefault("") == mirror)
    }

    /** Sync host check for card parsing (mirror already resolved by the caller). */
    private fun isRezkaHost(id: String, base: String, mirrorHost: String): Boolean {
        if (!id.startsWith("http")) return false
        if (id.contains("rezka")) return true
        if (mirrorHost.isBlank()) return id.startsWith(base)
        return id.startsWith(base) ||
            runCatching { URI(id).host.orEmpty().lowercase() }.getOrDefault("") == mirrorHost
    }

    private fun parseCards(html: String?, base: String, mirrorHost: String): List<Anime> {
        html ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        return doc.select(".b-content__inline_item").mapNotNull { card ->
            val link = card.selectFirst(".b-content__inline_item-link a")
                ?: card.selectFirst("a[href]") ?: return@mapNotNull null
            val id = link.absUrl("href").ifBlank { return@mapNotNull null }
            if (!isRezkaHost(id, base, mirrorHost)) return@mapNotNull null
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val poster = card.selectFirst("img")?.let {
                it.absUrl("src").ifBlank { it.absUrl("data-src") }
            }.orEmpty()
            val info = card.selectFirst(".b-content__inline_item-link div")?.text().orEmpty()
            val year = Regex("(19|20)\\d{2}").find(info)?.value?.toIntOrNull() ?: 0
            Anime(id = id, title = title, poster = poster, year = year, genres = info)
        }.distinctBy { it.id }
    }

    /**
     * Season/episode pairs for a series page.
     *
     * Rezka renders an episode list per TRANSLATOR, so the same episode appears in the
     * markup several times. Without de-duplication the list inflated (a 20-episode
     * season showing an "episode 24") and the duplicate entries pointed at positions
     * that never resolved to a stream. Sorted numerically so the order matches what
     * the season/episode labels say.
     */
    private fun parseEpisodes(doc: Document): List<Pair<String, String>> =
        doc.select(".b-simple_episode__item").mapNotNull { el ->
            val s = el.attr("data-season_id").ifBlank { return@mapNotNull null }
            val e = el.attr("data-episode_id").ifBlank { return@mapNotNull null }
            s to e
        }
            .distinct()
            .sortedWith(compareBy({ it.first.toIntOrNull() ?: 0 }, { it.second.toIntOrNull() ?: 0 }))

    private data class Events(val id: String, val translatorId: Int, val isSeries: Boolean)

    private fun parseEvents(html: String): Events? {
        val m = Regex("initCDN(Movies|Series)Events\\((\\d+),\\s*(\\d+)").find(html) ?: return null
        return Events(
            id = m.groupValues[2],
            translatorId = m.groupValues[3].toIntOrNull() ?: return null,
            isSeries = m.groupValues[1] == "Series",
        )
    }

    private fun parseTranslators(doc: Document, defaultId: Int): List<Translation> {
        val items = doc.select(".b-translator__item")
        if (items.isEmpty()) return listOf(Translation(defaultId, "Стандарт"))
        return items.mapNotNull { el ->
            val id = el.attr("data-translator_id").toIntOrNull() ?: return@mapNotNull null
            val nm = el.attr("title").ifBlank { el.text() }.trim().ifBlank { "Озвучка $id" }
            Translation(id, nm)
        }.ifEmpty { listOf(Translation(defaultId, "Стандарт")) }
    }

    private data class TrOpts(val camrip: String, val ads: String, val director: String)

    private fun translatorOptions(doc: Document, translatorId: Int): TrOpts {
        val el = doc.selectFirst(".b-translator__item[data-translator_id=$translatorId]")
            ?: return TrOpts("0", "0", "0")
        fun attr(name: String) = el.attr(name).ifBlank { "0" }
        return TrOpts(attr("data-camrip"), attr("data-ads"), attr("data-director"))
    }

    private fun parseStreams(decoded: String): List<StreamVariant> {
        if (!decoded.contains("http")) return emptyList()
        return decoded.split(",").mapNotNull { part ->
            val q = Regex("\\[([^\\]]+)]").find(part)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val alts = part.substringAfter("]").split(" or ").map { it.trim() }
            val url = alts.firstOrNull { it.startsWith("http") && it.contains(".mp4") }
                ?: alts.firstOrNull { it.startsWith("http") }
                ?: return@mapNotNull null
            StreamVariant(q, url)
        }.distinctBy { it.quality }
            .sortedByDescending { it.quality.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }

    private val episodeCache = ConcurrentHashMap<String, List<Pair<String, String>>>()

    companion object {
        private val TRASH_CHARS = listOf("@", "#", "!", "^", "$")

        /** base64 of every 2- and 3-char combination of the trash chars,
         *  longest first so longer sequences strip before their prefixes. */
        val TRASH_CODES: List<String> = buildList {
            for (len in 2..3) {
                var combos = listOf("")
                repeat(len) { combos = combos.flatMap { p -> TRASH_CHARS.map { p + it } } }
                combos.forEach { add(Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))) }
            }
        }.sortedByDescending { it.length }

        /** HDrezka stream-url de-obfuscation. Strips the `#h` prefix and `//_//`
         *  separators, removes the trash tokens, then base64-decodes. */
        fun decodeTrash(data: String): String {
            if (data.isBlank()) return ""
            var s = data.replaceFirst("#h", "").split("//_//").joinToString("")
            for (code in TRASH_CODES) s = s.replace(code, "")
            val pad = (4 - s.length % 4) % 4
            if (pad == 3) return "" // 1 leftover char = not valid base64
            return runCatching {
                String(Base64.getDecoder().decode(s + "=".repeat(pad)), Charsets.UTF_8)
            }.getOrDefault("")
        }
    }
}

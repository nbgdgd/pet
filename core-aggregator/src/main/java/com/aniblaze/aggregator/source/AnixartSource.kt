package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

/**
 * Anixart — free public API, large catalog, and (crucially) many voiceovers
 * (озвучки) per title. Streams are kodikplayer.com embeds resolved by
 * [KodikExtractor].
 *
 * Content ids are namespaced `ax:{releaseId}` and may carry a chosen voiceover
 * as `ax:{releaseId}:t{typeId}`; without one the most-watched dub is used.
 */
class AnixartSource @Inject constructor(
    private val http: HttpClient,
    private val kodik: KodikExtractor,
    private val settings: SettingsDataStore,
) : ContentAggregator {

    override val name: String = "Anixart"

    companion object {
        private const val API = "https://api.anixart.tv"
        private const val PREFIX = "ax:"

        /** Franchise pages are 25 entries; nothing real is longer than this. */
        private const val FRANCHISE_PAGE_LIMIT = 8
    }

    override suspend fun search(query: String): List<Anime> {
        val body = JSONObject().put("query", query).put("searchBy", 0).toString()
        val resp = http.postJson("$API/search/releases/0?perPage=30", body) ?: return emptyList()
        return parseCatalog(resp)
    }

    override suspend fun trending(): List<Anime> = catalog("trending")

    override suspend fun catalog(category: String): List<Anime> {
        if (category == "ongoing") return scheduleOngoing()
        val sort = when (category) {
            "recent" -> 2   // recently updated
            "popular" -> 4  // most rated
            else -> 3       // trending / by rating
        }
        val resp = http.postJson("$API/filter/0?perPage=30", JSONObject().put("sort", sort).toString())
            ?: return emptyList()
        return parseCatalog(resp)
    }

    /** Currently-airing titles from the weekday schedule, today's first. */
    private suspend fun scheduleOngoing(): List<Anime> {
        val resp = http.getHtml("$API/schedule") ?: return emptyList()
        val obj = JSONObject(resp)
        val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        val out = mutableListOf<Anime>()
        days.forEachIndexed { idx, day ->
            val arr = obj.optJSONArray(day) ?: return@forEachIndexed
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { animeFrom(it) }?.let { out.add(it.copy(broadcast = idx + 1)) }
            }
        }
        val today = java.time.LocalDate.now().dayOfWeek.value
        return out.distinctBy { it.id }.sortedBy { ((it.broadcast - today) % 7 + 7) % 7 }
    }

    override suspend fun catalogPage(sort: Int, page: Int): List<Anime> {
        val resp = http.postJson("$API/filter/$page?perPage=30", JSONObject().put("sort", sort).toString())
            ?: return emptyList()
        return parseCatalog(resp)
    }

    /**
     * Страница каталога с фильтром. Тело запроса собрано по тому, что сервер реально
     * понимает (проверено на живом API):
     *
     *  • `genres` — массив СТРОК, точные имена жанров каталога, между собой И.
     *    Идентификаторы жанров не принимаются: с числами ответ пустой.
     *  • `status_id` — ЧИСЛО. Именно `status_id`: `status`, `statuses`, `status_ids` и
     *    `release_status` сервер молча игнорирует, и ответ приходит нефильтрованным.
     *  • `episodes_to` в одиночку не работает; вместе с `episodes_from` — работает.
     *    Поэтому нижняя граница ставится всегда, хотя бы единицей.
     *  • `perPage` игнорируется: страница всегда 25 карточек.
     *
     * Оценка серверу не передаётся вовсе — такого условия у API нет; её отсекает
     * [CatalogFilter.matches] уже на нашей стороне.
     */
    suspend fun filteredPage(filter: com.aniblaze.aggregator.model.CatalogFilter, page: Int): List<Anime> {
        val body = JSONObject().put("sort", filter.sort.anixartId)
        val genres = filter.tags.mapNotNull {
            com.aniblaze.aggregator.model.CatalogTag.byKey(it)?.anixart?.ifBlank { null }
        }
        if (genres.isNotEmpty()) body.put("genres", JSONArray(genres))
        filter.status?.let { body.put("status_id", it.id) }
        filter.ageRating?.let { body.put("age_ratings", JSONArray(listOf(it.id))) }
        if (filter.yearFrom > 0) body.put("start_year", filter.yearFrom)
        if (filter.yearTo > 0) body.put("end_year", filter.yearTo)
        filter.episodes?.let {
            body.put("episodes_from", it.from.coerceAtLeast(1))
            if (it.to > 0) body.put("episodes_to", it.to)
        }
        filter.contentType?.let { body.put("category_id", it.anixartId) }
        val resp = http.postJson("$API/filter/$page?perPage=30", body.toString()) ?: return emptyList()
        // Ответ всё равно перепроверяется: сервер выполняет не все условия, а часть
        // источников в общей ленте про фильтры не слышала вовсе.
        return parseCatalog(resp).filter(filter::matches)
    }

    override suspend fun seasonal(season: Int, year: Int, page: Int): List<Anime> =
        filterSeason(season, year, page, sort = 4)

    override suspend fun watchingNow(season: Int, year: Int, page: Int): List<Anime> =
        filterSeason(season, year, page, sort = 1)

    private suspend fun filterSeason(season: Int, year: Int, page: Int, sort: Int): List<Anime> {
        val body = JSONObject()
            .put("sort", sort)
            .put("season", season)
            .put("start_year", year)
            .put("end_year", year)
            .toString()
        val resp = http.postJson("$API/filter/$page?perPage=30", body) ?: return emptyList()
        return parseCatalog(resp)
    }

    override suspend fun random(): Anime? {
        val resp = http.getHtml("$API/release/random") ?: return null
        val release = JSONObject(resp).optJSONObject("release") ?: return null
        return animeFrom(release)
    }

    /** Full release info. The catalog/search feeds truncate the description to a
     *  couple hundred characters — /release/{id} carries it whole. */
    suspend fun details(contentId: String): Anime? {
        val rid = releaseId(contentId) ?: return null
        val resp = http.getHtml("$API/release/$rid") ?: return null
        val release = JSONObject(resp).optJSONObject("release") ?: return null
        return animeFrom(release)
    }

    /** One page of user comments under a release (25 per page, top-rated first). */
    suspend fun comments(contentId: String, page: Int): List<com.aniblaze.aggregator.model.TitleComment> {
        val rid = releaseId(contentId) ?: return emptyList()
        val resp = http.getHtml("$API/release/comment/all/$rid/$page?sort=3") ?: return emptyList()
        return parseComments(resp, dropReplies = true)
    }

    /** The reply thread under one comment (usually a single page holds it all). */
    suspend fun commentReplies(commentId: Long): List<com.aniblaze.aggregator.model.TitleComment> {
        val resp = http.getHtml("$API/release/comment/replies/$commentId/0?sort=1") ?: return emptyList()
        return parseComments(resp, dropReplies = false)
    }

    private fun parseComments(resp: String, dropReplies: Boolean): List<com.aniblaze.aggregator.model.TitleComment> =
        runCatching {
            val content = JSONObject(resp).optJSONArray("content") ?: return emptyList()
            (0 until content.length()).mapNotNull { i ->
                val c = content.optJSONObject(i) ?: return@mapNotNull null
                if (c.optBoolean("is_deleted")) return@mapNotNull null
                if (dropReplies && c.optBoolean("is_reply")) return@mapNotNull null
                val message = c.optString("message").trim()
                if (message.isBlank()) return@mapNotNull null
                val profile = c.optJSONObject("profile")
                com.aniblaze.aggregator.model.TitleComment(
                    id = c.optLong("id"),
                    author = profile?.optString("login").orEmpty().ifBlank { "аноним" },
                    avatar = profile?.optString("avatar").orEmpty(),
                    message = message,
                    timestamp = c.optLong("timestamp"),
                    votes = c.optInt("vote_count"),
                    isSpoiler = c.optBoolean("is_spoiler"),
                    replyCount = c.optInt("reply_count"),
                    // Серия, на которой это написали. Сервер параметр `?episode=` не
                    // слушает (проверено: выдача с ним и без него побайтово та же),
                    // зато у каждой записи есть своё поле — фильтруем у себя.
                    episode = c.optInt("posted_at_episode", 0),
                )
            }
        }.onFailure { Timber.w(it, "[Anixart] comments parse failed") }.getOrDefault(emptyList())

    /**
     * Every entry of the title's franchise, in release order.
     *
     * `related_releases` inside `/release/{id}` is only a THREE-item teaser — that is
     * why the season menu showed four options for «Клинок, рассекающий демонов» while
     * the franchise has twelve. The full list lives behind `/related/{franchiseId}`,
     * paginated 25 at a time; `total_page_count` is always 0 there, so the walk stops
     * on an empty page (or once `total_count` entries are in hand).
     */
    override suspend fun related(contentId: String): List<Anime> {
        val rid = releaseId(contentId) ?: return emptyList()
        val resp = http.getHtml("$API/release/$rid") ?: return emptyList()
        val release = JSONObject(resp).optJSONObject("release") ?: return emptyList()
        val franchiseId = release.optJSONObject("related")?.optInt("id", 0) ?: 0
        val full = if (franchiseId > 0) franchise(franchiseId, release.optInt("related_count", 0)) else emptyList()
        if (full.isNotEmpty()) return full
        // No franchise record (a standalone title, or the field went away): fall back
        // to the teaser rather than showing nothing.
        val teaser = release.optJSONArray("related_releases") ?: return emptyList()
        return (0 until teaser.length()).mapNotNull { i ->
            teaser.optJSONObject(i)?.let { animeFrom(it) }
        }
    }

    private suspend fun franchise(franchiseId: Int, expected: Int): List<Anime> {
        val out = mutableListOf<Anime>()
        var page = 0
        while (page < FRANCHISE_PAGE_LIMIT) {
            val resp = http.getHtml("$API/related/$franchiseId/$page") ?: break
            val content = runCatching { JSONObject(resp).optJSONArray("content") }.getOrNull() ?: break
            if (content.length() == 0) break
            for (i in 0 until content.length()) {
                content.optJSONObject(i)?.let { animeFrom(it) }?.let { out.add(it) }
            }
            if (expected in 1..out.size) break
            page++
        }
        // Release order, not the API's own ordering: unaired entries (year = 0) are
        // sequels and belong at the end, not at the top next to the first season.
        return out.distinctBy { it.id }
            .sortedWith(compareBy({ if (it.year > 0) 0 else 1 }, { it.year }, { it.title }))
    }

    override suspend fun recommended(contentId: String): List<Anime> {
        val rid = releaseId(contentId) ?: return emptyList()
        val resp = http.getHtml("$API/release/$rid") ?: return emptyList()
        val root = JSONObject(resp)
        val arr = root.optJSONObject("release")?.optJSONArray("recommended_releases")
            ?: root.optJSONArray("recommended_releases")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { animeFrom(it) }
        }
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val rid = releaseId(contentId) ?: return emptyList()
        val typeId = explicitTypeId(contentId) ?: bestVoiceover(rid, contentId) ?: return emptyList()
        return episodesFor(rid, typeId).map { ep ->
            Segment(id = ep.url, contentId = contentId, number = ep.position, title = ep.name)
        }.sortedBy { it.number }
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val rid = releaseId(contentId) ?: return null
        val voiceovers = voiceovers(rid)
        val (preferredName, priorityEnabled) = voiceoverSelection(contentId)
        val preferred = explicitTypeId(contentId)
            ?: chooseBest(voiceovers, preferredName, priorityEnabled)
            ?: return null

        // The chosen dub first, then the REST by popularity. A dub often lives only
        // on a hosting we can't extract (AniDUB on old titles = Sibnet-only) — one
        // bad dub used to fail the whole title, while another dub of the same
        // episode played fine via Kodik.
        val order = buildList {
            add(preferred)
            voiceovers.filter { it.episodes > 0 && it.id != preferred }
                .sortedByDescending { it.views }
                .forEach { add(it.id) }
        }
        for (typeId in order) {
            val episode = episodesFor(rid, typeId).firstOrNull { it.position == segment } ?: continue
            val variants = streamsFor(episode.url)
            val best = variants.firstOrNull() ?: run {
                Timber.w("[Anixart] no playable stream for %s (type %d), trying next dub", episode.url, typeId)
                null
            } ?: continue

            return ContentResult(
                location = best.url,
                quality = best.quality,
                variants = variants,
                translations = voiceovers.map { Translation(it.id, it.name, it.isSub, it.views) },
                translationId = typeId,
                source = name,
                referer = refererFor(episode.url),
                metadata = mapOf("contentId" to contentId, "segment" to segment.toString()),
            )
        }
        Timber.w("[Anixart] no dub yielded a stream for release %s ep %d", rid, segment)
        return null
    }

    /** The stream CDNs check Referer per hosting. */
    private fun refererFor(url: String): String = when {
        url.contains("sibnet", ignoreCase = true) -> "https://video.sibnet.ru/"
        else -> "https://kodikplayer.com/"
    }

    override suspend fun validateSource(contentId: String): Boolean =
        http.isReachable("$API/release/random")

    // ---- voiceovers / episodes ----

    private data class Voiceover(val id: Int, val name: String, val isSub: Boolean, val views: Long, val episodes: Int)
    private data class Episode(val position: Int, val name: String, val url: String)

    private suspend fun voiceovers(rid: String): List<Voiceover> {
        val resp = http.getHtml("$API/episode/$rid") ?: return emptyList()
        val types = JSONObject(resp).optJSONArray("types") ?: return emptyList()
        return (0 until types.length()).mapNotNull { i ->
            val t = types.optJSONObject(i) ?: return@mapNotNull null
            Voiceover(
                id = t.optInt("id"),
                name = t.optString("name").ifBlank { "Озвучка" },
                isSub = t.optBoolean("is_sub", false),
                views = t.optLong("view_count", 0),
                episodes = t.optInt("episodes_count", 0),
            )
        }
    }

    private suspend fun bestVoiceover(rid: String, contentId: String): Int? {
        val (preferredName, priorityEnabled) = voiceoverSelection(contentId)
        return chooseBest(voiceovers(rid), preferredName, priorityEnabled)
    }

    private suspend fun voiceoverSelection(contentId: String): Pair<String, Boolean> {
        val current = runCatching { settings.settings.first() }.getOrNull() ?: return "" to false
        val base = contentId.substringBefore(":t")
        val manual = current.manualVoiceovers[base].orEmpty()
        val preferred = manual.ifBlank {
            if (current.voiceoverPriorityEnabled) "" else current.preferredVoiceover
        }
        return preferred to current.voiceoverPriorityEnabled
    }

    /**
     * Honours the user's preferred dub (by name) when present; otherwise the
     * most-watched dub with real episodes, subtitles only as a last resort.
     */
    private fun chooseBest(all: List<Voiceover>, preferred: String, priorityEnabled: Boolean): Int? {
        val vs = all.filter { it.episodes > 0 }
        if (vs.isEmpty()) return null
        preferredVoiceoverIndex(vs.map { it.name }, preferred, priorityEnabled)
            ?.let { return vs[it].id }
        return (vs.filter { !it.isSub }.ifEmpty { vs }).maxByOrNull { it.views }?.id
    }

    private suspend fun episodesFor(rid: String, typeId: Int): List<Episode> {
        // A voiceover may expose several providers (sources); prefer Kodik.
        val srcResp = http.getHtml("$API/episode/$rid/$typeId") ?: return emptyList()
        val sources = JSONObject(srcResp).optJSONArray("sources") ?: return emptyList()
        val sourceId = pickSource(sources) ?: return emptyList()

        val epResp = http.getHtml("$API/episode/$rid/$typeId/$sourceId") ?: return emptyList()
        val eps = JSONObject(epResp).optJSONArray("episodes") ?: return emptyList()
        return (0 until eps.length()).mapNotNull { i ->
            val e = eps.optJSONObject(i) ?: return@mapNotNull null
            val url = e.optString("url").ifBlank { return@mapNotNull null }
            Episode(position = e.optInt("position"), name = e.optString("name").ifBlank { "Серия" }, url = url)
        }
    }

    private fun pickSource(sources: JSONArray): Int? {
        var first: Int? = null
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val id = s.optInt("id")
            if (first == null) first = id
            if (s.optString("name").equals("Kodik", ignoreCase = true)) return id
        }
        return first
    }

    private suspend fun streamsFor(url: String): List<com.aniblaze.aggregator.model.StreamVariant> = when {
        url.contains("kodik", ignoreCase = true) -> kodik.extract(url)
        // Sibnet: the only hosting AniDUB uses on older titles. shell.php carries a
        // plain progressive MP4 — src: "/v/….mp4"; the CDN checks Referer, passed
        // to the player via ContentResult.referer.
        url.contains("sibnet", ignoreCase = true) -> extractSibnet(url)
        url.contains(".m3u8") || url.contains(".mp4") ->
            listOf(com.aniblaze.aggregator.model.StreamVariant("auto", if (url.startsWith("//")) "https:$url" else url))
        else -> {
            Timber.d("[Anixart] unsupported provider url: %s", url.take(60))
            emptyList()
        }
    }

    private suspend fun extractSibnet(url: String): List<com.aniblaze.aggregator.model.StreamVariant> {
        val page = if (url.startsWith("//")) "https:$url" else url
        val html = http.getHtml(page, referer = "https://video.sibnet.ru/") ?: return emptyList()
        val path = Regex("""src:\s*"(/v/[^"]+\.mp4)"""").find(html)?.groupValues?.get(1)
            ?: return emptyList<com.aniblaze.aggregator.model.StreamVariant>().also {
                Timber.w("[Anixart] sibnet page without mp4: %s", page.take(80))
            }
        return listOf(com.aniblaze.aggregator.model.StreamVariant("auto", "https://video.sibnet.ru$path"))
    }

    // ---- catalog parsing ----

    private fun parseCatalog(resp: String): List<Anime> {
        val content = JSONObject(resp).optJSONArray("content") ?: return emptyList()
        return (0 until content.length()).mapNotNull { i ->
            content.optJSONObject(i)?.let { animeFrom(it) }
        }.distinctBy { it.id }
    }

    private fun animeFrom(r: JSONObject): Anime? {
        val id = r.optInt("id").takeIf { it > 0 } ?: return null
        val title = r.optString("title_ru").ifBlank { r.optString("title_original") }
            .ifBlank { return null }
        // Hide titles banned for distribution in Russia (RuStore moderation).
        if (BannedContent.isBanned(r.optString("title_ru"), r.optString("title_original"))) return null
        return Anime(
            id = "$PREFIX$id",
            title = title,
            poster = r.optString("image"),
            description = r.optString("description"),
            rating = r.optDouble("grade", 0.0),
            status = r.optString("title_original"),
            broadcast = r.optInt("broadcast", 0),
            genres = r.optString("genres"),
            year = r.optInt("year", 0),
            favoritesCount = r.optInt("favorites_count", 0),
            studio = r.optString("studio"),
            watchingCount = r.optInt("watching_count", 0),
            country = r.optString("country"),
            // Ниже — то, по чему фильтрует каталог. Читаем ВСЕГДА, а не только под
            // фильтром: этими же полями проверка перепроверяет ответ сервера, и без
            // них «количество серий» или «возраст» пришлось бы принимать на веру.
            episodesTotal = r.optInt("episodes_total", 0),
            ageRating = r.optInt("age_rating", 0),
            contentType = r.optJSONObject("category")?.optString("name").orEmpty(),
            airingStatus = r.optJSONObject("status")?.optInt("id", 0) ?: 0,
        )
    }

    // ---- id helpers ----

    private fun releaseId(contentId: String): String? =
        contentId.removePrefix(PREFIX).substringBefore(':').takeIf { it.isNotBlank() }

    private fun explicitTypeId(contentId: String): Int? =
        Regex(""":t(\d+)""").find(contentId)?.groupValues?.get(1)?.toIntOrNull()
}

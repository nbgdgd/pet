package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogSort
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.aggregator.model.CommentPage
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.HttpClient
import com.aniblaze.network.HttpStatusException
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

    private val sibnet = SibnetExtractor(http)

    override val name: String = "Anixart"
    override fun ownsContentId(contentId: String): Boolean = contentId.startsWith(PREFIX)

    companion object {
        private const val API = "https://api.anixart.tv"
        private const val PREFIX = "ax:"

        /** Franchise pages are 25 entries; nothing real is longer than this. */
        private const val FRANCHISE_PAGE_LIMIT = 8

        /**
         * Свежие первыми. Порядок по идентификатору вниз — ОДНОЗНАЧЕН, поэтому обход по
         * страницам не съезжает. Это рабочая лошадь полного обхода.
         */
        const val COMMENTS_FRESH = 1

        /**
         * Сначала лучшие. Порядок НЕОДНОЗНАЧЕН (равные голоса сервер тасует), поэтому
         * годится только на первые страницы — но они нужны, и вот почему.
         *
         * Замерено 23.08 на «Наруто», по шесть страниц каждой сортировкой:
         *
         *     sort=1  150 записей, БЕЗ ПОМЕТКИ СЕРИИ — 0,   с серией >141 — 150
         *     sort=3  150 записей, без пометки серии — 141, с серией >141 — 9
         *
         * Свежие записи оставляют те, кто смотрит тайтл СЕЙЧАС, то есть его конец: все
         * сто пятьдесят помечены сериями за двухсотую. Для зрителя 141-й серии это
         * сплошь спойлеры, и отбор справедливо выбрасывал всё — чат оставался пустым.
         * А топовые записи старые, пометки серии у них ещё не было, и они годятся любой
         * серии. Поэтому берём и то и другое: голову топовых для пула и полный обход по
         * свежим для полноты. Повторы между ними снимает отбор по идентичности.
         */
        const val COMMENTS_TOP = 3
    }

    override suspend fun search(query: String): List<Anime> {
        val body = JSONObject().put("query", query).put("searchBy", 0).toString()
        val resp = http.postJson("$API/search/releases/0?perPage=30", body) ?: return emptyList()
        return parseCatalog(resp)
    }

    override suspend fun trending(): List<Anime> = catalog("trending")

    override suspend fun catalog(category: String): List<Anime> {
        if (category == "ongoing") return scheduleOngoing()
        // "Сейчас смотрят" — currently-airing titles ordered by live viewer count,
        // i.e. what people are actually watching right now (not all-time popularity).
        if (category == "watching") return scheduleOngoing().sortedByDescending { it.watchingCount }
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

    override val supportsFilter: Boolean = true

    /**
     * Каталог, суженный фильтром, — целиком на стороне Anixart.
     *
     * Какие ключи он на самом деле слушает, выяснено перебором, а не по документации
     * (её нет). Что важно и неочевидно:
     *
     *  • `genres` — МАССИВ ТОЧНЫХ РУССКИХ ИМЁН в нижнем регистре, и между собой это И:
     *    `["исэкай","реинкарнация"]` даёт подмножество `["исэкай"]`. Идентификаторы
     *    жанров не принимаются — с числами ответ пустой.
     *  • `status_id` — ЧИСЛО. Именно `status_id`: `status`, `statuses`, `status_ids`,
     *    `release_status` и `statusId` сервер молча игнорирует (проверено — ответ на
     *    `status: 2` состоит из одних завершённых). Массив здесь тоже ломает запрос.
     *  • `episodes_to` в одиночку не работает; вместе с `episodes_from` — работает.
     *    Поэтому нижняя граница ставится всегда, хотя бы единицей.
     *  • `country` — строка и СРАВНЕНИЕ ТОЧНОЕ: «Корея» не находит «Южную Корею».
     *  • `perPage` игнорируется: страница всегда 25 карточек.
     *  • Общего числа найденного API не отдаёт: `total_count` равен размеру страницы,
     *    а `total_page_count` всегда ноль. Поэтому счётчик над лентой считает то, что
     *    уже загружено, и ставит «+», пока страницы не кончились.
     */
    override suspend fun filterPage(filter: CatalogFilter, page: Int): List<Anime> {
        val body = JSONObject().put("sort", sortIdOf(filter.sort))
        val genres = filter.tags.mapNotNull { CatalogTag.byKey(it)?.anixart?.ifBlank { null } }
        if (genres.isNotEmpty()) body.put("genres", JSONArray(genres))
        filter.status?.let { body.put("status_id", it.id) }
        filter.ageRating?.let { body.put("age_ratings", JSONArray(listOf(it.id))) }
        if (filter.country.isNotBlank()) body.put("country", filter.country)
        if (filter.yearFrom > 0) body.put("start_year", filter.yearFrom)
        if (filter.yearTo > 0) body.put("end_year", filter.yearTo)
        filter.episodes?.let {
            body.put("episodes_from", it.from.coerceAtLeast(1))
            if (it.to > 0) body.put("episodes_to", it.to)
        }
        filter.contentType?.takeIf { it.anixartId > 0 }?.let { body.put("category_id", it.anixartId) }
        val resp = http.postJson("$API/filter/$page?perPage=30", body.toString()) ?: return emptyList()
        return parseCatalog(resp)
    }

    /**
     * Сортировка каталога. Значения сняты замером первой страницы каждой:
     *
     *     0 — по обновлению (сверху то, что тронули сегодня)
     *     1 — по активности зрителей сейчас
     *     2 — по дате выхода, будущее сверху
     *     3 — по оценке
     *     4 — по популярности (поле `rating`, счётчик самого Anixart)
     *     5 — по оценке снизу вверх
     */
    private fun sortIdOf(sort: CatalogSort): Int = when (sort) {
        CatalogSort.POPULAR -> 4
        CatalogSort.RATING -> 3
        CatalogSort.FRESH -> 0
        CatalogSort.VIEWS -> 1
        CatalogSort.RELEASE_DATE -> 2
    }

    override suspend fun ongoingPage(page: Int): List<Anime> {
        // status 2 = ongoing in the filter API; sort 1 orders by live activity.
        val body = JSONObject().put("sort", 1).put("status_id", 2).toString()
        val resp = http.postJson("$API/filter/$page?perPage=30", body) ?: return emptyList()
        return parseCatalog(resp)
    }

    /**
     * "Последние поступления" — recently updated titles that actually have episodes.
     * sort 0 returns airing/released titles by latest activity; we drop status "Анонс"
     * (id 3) so every card is playable (≥1 episode), unlike the raw feed which is
     * mostly unreleased announces.
     */
    override suspend fun latestReleases(page: Int): List<Anime> {
        val resp = http.postJson("$API/filter/$page?perPage=30", JSONObject().put("sort", 0).toString())
            ?: return emptyList()
        val content = JSONObject(resp).optJSONArray("content") ?: return emptyList()
        return (0 until content.length()).mapNotNull { i ->
            val o = content.optJSONObject(i) ?: return@mapNotNull null
            if (o.optJSONObject("status")?.optInt("id") == 3) return@mapNotNull null // announce = no episodes
            animeFrom(o)
        }.distinctBy { it.id }
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
     *  couple hundred characters ("…обитают монс") — /release/{id} carries it whole. */
    suspend fun details(contentId: String): Anime? {
        val rid = releaseId(contentId) ?: return null
        val resp = http.getHtml("$API/release/$rid") ?: return null
        val release = JSONObject(resp).optJSONObject("release") ?: return null
        return animeFrom(release)
    }

    /**
     * Одна страница обсуждения под релизом (25 записей). Ответы и удалённое отсеяны —
     * остаются только самостоятельные мнения.
     *
     * СОРТИРОВКА `sort=1` (свежие первыми), И ЭТО НЕ ВКУСОВЩИНА. Здесь стоял `sort=3`
     * — «сначала лучшие», — и он ломал сам обход: порядок по числу голосов НЕ
     * ОДНОЗНАЧЕН, у Наруто тысячи записей с одинаковым счётом, и сервер расставляет их
     * при каждом запросе по-своему. Окно страницы съезжает, одни записи приходят
     * дважды, другие не приходят ВООБЩЕ. Замерено 23.08 на `ax:609` (Наруто):
     *
     *     sort=0  10 страниц  250 записей  250 разных   0 повторов
     *     sort=1  10 страниц  250 записей  250 разных   0 повторов
     *     sort=2  10 страниц  250 записей  250 разных   0 повторов
     *     sort=3  10 страниц  250 записей  240 разных  10 повторов   ← было
     *
     * На сорока страницах доля повторов у `sort=3` доходит до 23 % (1000 записей, 766
     * разных). То есть «комментарии повторяются, и их мало» — это одна поломка, а не
     * две: пропущенное и повторённое здесь одно и то же смещение окна.
     *
     * `sort=1` упорядочен по идентификатору вниз, то есть однозначен. Съехать он может
     * только от НОВОЙ записи, добавленной посреди обхода: она сдвинет всё на единицу,
     * и это стоит одного повтора (его снимет отбор по идентичности) и одной
     * пропущенной записи. Обход длится секунды, так что случай редкий — против
     * гарантированной четверти у `sort=3`.
     *
     * «Сначала лучшие» никуда не делось: порядок по голосам ставится у себя, после
     * загрузки, и теперь применяется к ПОЛНОМУ списку, а не к дырявому.
     */
    suspend fun comments(contentId: String, page: Int, sort: Int = COMMENTS_FRESH): CommentPage {
        val rid = releaseId(contentId) ?: return CommentPage.EMPTY
        val url = "$API/release/comment/all/$rid/$page?sort=$sort"
        val resp = http.getHtml(url) ?: throw HttpStatusException("GET", url, 404)
        val envelope = JSONObject(resp)
        val items = parseComments(envelope, dropReplies = true)
        // Сколько всего страниц и записей источник насчитал у себя. Раньше эти два поля
        // просто выбрасывались, и обход не знал, где конец: у Наруто их 258 и 6456, а
        // приложение брало двенадцать страниц — три процента обсуждения.
        return CommentPage(
            items = items,
            rawCount = envelope.optJSONArray("content")?.length() ?: items.size,
            totalPages = envelope.optInt("total_page_count", 0),
            totalCount = envelope.optInt("total_count", 0),
        )
    }

    /** The reply thread under one comment (usually a single page holds it all).
     *  [parentAuthor] — ник, к которому обращены ответы (в чате это «@ник»). */
    suspend fun commentReplies(commentId: Long, parentAuthor: String = ""): List<com.aniblaze.aggregator.model.TitleComment> {
        val url = "$API/release/comment/replies/$commentId/0?sort=1"
        val resp = http.getHtml(url) ?: throw HttpStatusException("GET", url, 404)
        return parseComments(JSONObject(resp), dropReplies = false, replyTo = parentAuthor)
    }

    private fun parseComments(
        envelope: JSONObject,
        dropReplies: Boolean,
        replyTo: String = "",
    ): List<com.aniblaze.aggregator.model.TitleComment> {
        val content = envelope.optJSONArray("content") ?: return emptyList()
        return (0 until content.length()).mapNotNull { i ->
                val c = content.optJSONObject(i) ?: return@mapNotNull null
                if (c.optBoolean("is_deleted")) return@mapNotNull null
                if (dropReplies && c.optBoolean("is_reply")) return@mapNotNull null
                val message = c.optString("message").trim()
                if (message.isBlank()) return@mapNotNull null
                val profile = c.optJSONObject("profile")
                com.aniblaze.aggregator.model.TitleComment(
                    id = c.optLong("id"),
                    source = "anixart",
                    authorId = profile?.optLong("id")?.takeIf { it > 0L }?.toString().orEmpty(),
                    author = profile?.optString("login").orEmpty().ifBlank { "аноним" },
                    avatar = profile?.optString("avatar").orEmpty(),
                    message = message,
                    timestamp = c.optLong("timestamp"),
                    votes = c.optInt("vote_count"),
                    isSpoiler = c.optBoolean("is_spoiler"),
                    replyCount = c.optInt("reply_count"),
                    parentId = c.optLong("parent_comment_id", 0L),
                    replyTo = if (c.optBoolean("is_reply")) replyTo else "",
                    // Серия, на которой это написали. Сервер параметр `?episode=` не
                    // слушает (проверено: выдача с ним и без него побайтово та же),
                    // зато у каждой записи есть своё поле — фильтруем у себя.
                    episode = c.optInt("posted_at_episode", 0),
                )
            }
    }

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
        val typeId = explicitTypeId(contentId) ?: bestVoiceover(rid) ?: return emptyList()
        val eps = episodesFor(rid, typeId)
        // API numbering is inconsistent per voiceover: some dubs number episodes
        // from 0. Shift a 0-based list so the UI stays 1-based and round-trips
        // with the extractContent lookup below.
        val shift = if (eps.isNotEmpty() && eps.minOf { it.position } == 0) 1 else 0
        return eps.map { ep ->
            Segment(id = ep.url, contentId = contentId, number = ep.position + shift, title = ep.name)
        }.sortedBy { it.number }
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val rid = releaseId(contentId) ?: return null
        val voiceovers = voiceovers(rid)
        val preferred = explicitTypeId(contentId) ?: chooseBest(voiceovers, preferredVoiceover()) ?: return null

        // The chosen dub first, then the REST by popularity. A dub often lives only
        // on a hosting we can't extract (AniDUB on old titles = Sibnet-only) — one
        // bad dub used to fail the whole title ("не работает"), while another dub of
        // the same episode played fine via Kodik. The result carries the dub that
        // actually resolved, so the picker and saved preference stay truthful.
        val order = buildList {
            add(preferred)
            voiceovers.filter { it.episodes > 0 && it.id != preferred }
                .sortedByDescending { it.views }
                .forEach { add(it.id) }
        }
        for (typeId in order) {
            val eps = episodesFor(rid, typeId)
            val episode = eps.firstOrNull { it.position == segment }
                // 0-based dub (e.g. a single-episode special at position 0): retry
                // shifted. Applied only when the WHOLE list is 0-based, so a genuinely
                // missing episode in a normally numbered dub still fails honestly.
                ?: eps.takeIf { it.isNotEmpty() && it.minOf { e -> e.position } == 0 }
                    ?.firstOrNull { it.position == segment - 1 }
                ?: continue

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

    /**
     * Per-dub view counts for a title, keyed by dub name. Powers the "какую озвучку
     * выбирают" percentages when playback resolved through a source that has no view
     * stats of its own (Kodik, balancer) — Anixart's stats for the same title are the
     * only per-release numbers that exist. Cached per title, misses included.
     */
    suspend fun voiceoverViewsByTitle(title: String): Map<String, Long> {
        if (title.isBlank()) return emptyMap()
        voiceViewsCache[title]?.let { return it }
        val stats = runCatching {
            val hit = search(title).firstOrNull() ?: return@runCatching emptyMap<String, Long>()
            val rid = releaseId(hit.id) ?: return@runCatching emptyMap<String, Long>()
            voiceovers(rid).filter { it.views > 0 }.associate { it.name to it.views }
        }.getOrDefault(emptyMap())
        voiceViewsCache[title] = stats
        return stats
    }

    private val voiceViewsCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Long>>()

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

    private suspend fun bestVoiceover(rid: String): Int? =
        chooseBest(voiceovers(rid), preferredVoiceover())

    private suspend fun preferredVoiceover(): String =
        runCatching { settings.settings.first().preferredVoiceover }.getOrDefault("")

    /**
     * Honours the user's preferred dub (by name) when present; otherwise the
     * most-watched dub with real episodes, subtitles only as a last resort.
     */
    private fun chooseBest(all: List<Voiceover>, preferred: String): Int? {
        val vs = all.filter { it.episodes > 0 }
        if (vs.isEmpty()) return null
        if (preferred.isNotBlank()) {
            vs.firstOrNull { it.name.contains(preferred, ignoreCase = true) }?.let { return it.id }
        }
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
        // plain progressive MP4 (range-seekable) — src: "/v/….mp4"; the CDN checks
        // Referer, which the player passes via ContentResult.referer.
        SibnetExtractor.owns(url) -> sibnet.extract(url)
        url.contains(".m3u8") || url.contains(".mp4") ->
            listOf(com.aniblaze.aggregator.model.StreamVariant("auto", if (url.startsWith("//")) "https:$url" else url))
        else -> {
            Timber.d("[Anixart] unsupported provider url: %s", url.take(60))
            emptyList()
        }
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
            country = r.optString("country"),
            watchingCount = r.optInt("watching_count", 0),
            // Ниже — то, по чему фильтрует каталог. Читаем ВСЕГДА, а не только под
            // фильтром: этими же полями проверка перепроверяет ответ сервера, и без
            // них «количество серий» или «возраст» пришлось бы принимать на веру.
            episodesTotal = r.optInt("episodes_total", 0),
            episodesAvailable = r.optInt("episodes_released", 0),
            firstAiredAt = r.optLong("aired_on_date", 0).coerceAtLeast(0) * 1000L,
            ratingVotes = r.optInt("vote_count", 0),
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

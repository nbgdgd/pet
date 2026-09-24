package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogSort
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.aggregator.model.ContentType
import com.aniblaze.aggregator.model.TitleStatus
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.network.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * YummyAnime (yummyani.me) — агрегатор с ОТКРЫТЫМ JSON-API `api.yani.tv`, без ключа.
 *
 * Зачем он рядом с Anixart/AnimeOn: это независимый каталог с теми же Kodik-эмбедами
 * плюс ВТОРОЙ хостинг — video.sibnet.ru с прогрессивным MP4. У «Наруто» замерено
 * 17.09.2026: 808 видео на kodikplayer, 440 на Sibnet, 545 на Alloha. Alloha
 * (`alloha.yani.tv`) не разбирается — её плеер обфусцирован, поэтому такие видео
 * просто пропускаются при переборе.
 *
 * Идентификаторы: `ya:{anime_id}`, с выбранной озвучкой — `ya:{anime_id}:t{index}`.
 * Индекс озвучки считается по отсортированному имени, как у AnimeOn: он уезжает
 * в настройки и должен означать одно и то же между запусками. В ответах есть
 * `remote_ids.shikimori_id` — им пользуется репозиторий для сопоставления.
 *
 * API (проверено голым GET):
 *   GET /search?q=<query>&limit=30
 *   GET /anime?limit=30&offset=N            каталог
 *   GET /anime/{id}                          карточка
 *   GET /anime/{id}/videos                   все видео всех озвучек, по одному на серию
 */
class YummyAnimeSource(
    private val http: HttpClient,
    private val kodik: KodikExtractor,
    private val sibnet: SibnetExtractor = SibnetExtractor(http),
) : ContentAggregator {

    override val name: String = "YummyAnime"
    override fun ownsContentId(contentId: String): Boolean = contentId.startsWith(PREFIX)

    override suspend fun search(query: String): List<Anime> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val resp = http.getHtml("$API/search?q=$encoded&limit=30") ?: return emptyList()
        return parseList(resp)
    }

    /** «В тренде» — редакторский `sort=top` Yummy; это НЕ порядок по оценке. */
    override suspend fun trending(): List<Anime> = list(0, "sort=top&sort_forward=false")

    /**
     * Именованные ряды главной — те же ключи, что слушает Anixart, чтобы
     * репозиторий не различал источники:
     *   "ongoing"/"watching" — расписание (`/anime/schedule`), сегодняшний день первым;
     *   "recent" — свежедобавленное; "popular" — по просмотрам; иначе — по рейтингу.
     */
    override suspend fun catalog(category: String): List<Anime> = when (category) {
        "ongoing" -> schedule()
        "watching" -> schedule().sortedByDescending { it.watchingCount }
        "recent" -> catalogPage(SORT_FRESH, 0)
        "popular" -> catalogPage(SORT_POPULAR, 0)
        else -> catalogPage(SORT_RATING, 0)
    }

    /**
     * Номера сортировок — Anixart'овские (репозиторий зовёт `catalogPage(3|4|6, …)`
     * буквально): 0 обновление, 1 активность, 2 дата выхода, 3 оценка, 4 популярность,
     * 5 оценка снизу, 6 анонсы. У Yummy своя шкала (`sort=` из
     * title|year|rating|rating_counters|views|top|random|id, направление —
     * `sort_forward=false`), сюда и переводим.
     */
    override suspend fun catalogPage(sort: Int, page: Int): List<Anime> = list(page, sortQuery(sort))

    override val supportsFilter: Boolean = true

    /**
     * Фильтр каталога на стороне Yummy, насколько он это умеет (проверено перебором
     * 18.09.2026): `genres[]` — ЧИСЛОВЫЕ id из `/anime/catalog` (по имени сопоставляем
     * с [CatalogTag]), `status=ongoing|released|announcement`, `types[]=tv|movie|…`,
     * `season=1..4`. Года сервер не фильтрует ни в каком написании — их и остальное
     * (возраст, число серий, страна) досеет репозиторий через `CatalogFilter.matches`.
     */
    override suspend fun filterPage(filter: CatalogFilter, page: Int): List<Anime> {
        val q = StringBuilder(sortQuery(when (filter.sort) {
            CatalogSort.POPULAR -> SORT_POPULAR
            CatalogSort.RATING -> SORT_RATING
            CatalogSort.FRESH -> SORT_FRESH
            CatalogSort.VIEWS -> SORT_VIEWS
            CatalogSort.RELEASE_DATE -> SORT_RELEASE_DATE
        }))
        val genreIds = genreIds()
        for (key in filter.tags) {
            val tag = CatalogTag.byKey(key) ?: continue
            val id = genreIds[norm(tag.anixart)] ?: genreIds[norm(tag.label)] ?: continue
            q.append("&genres[]=").append(id)
        }
        filter.status?.let {
            q.append("&status=").append(
                when (it) {
                    TitleStatus.ONGOING -> "ongoing"
                    TitleStatus.FINISHED -> "released"
                    TitleStatus.ANNOUNCED -> "announcement"
                },
            )
        }
        filter.contentType?.let { type ->
            when (type) {
                ContentType.SERIES -> q.append("&types[]=tv")
                ContentType.MOVIE -> q.append("&types[]=movie")
                ContentType.OVA -> q.append("&types[]=ova&types[]=ona")
                ContentType.SPECIAL -> q.append("&types[]=special")
            }
        }
        return list(page, q.toString())
    }

    /**
     * Все тайтлы студии озвучки — со страниц сайта `/catalog/dubbing/<id>`.
     *
     * API такого фильтра не имеет (перебраны `dubbing[]`, `dubbers[]`, `studios[]`,
     * `dubbing_id` — сервер их молча игнорирует, проверено 19.09.2026), а сайт
     * рендерит каталог студии на сервере, по 24 карточки на страницу, с пагинацией.
     * Карточка несёт id, название, постер, оценку, тип и просмотры — этого хватает на
     * плитку; остальное страница тайтла дотянет по id как обычно.
     *
     * Результат кэшируется на [DUBBING_CACHE_MS]: студия меняет каталог редко, а
     * пролистывать до 20 страниц при каждом клике по фильтру не нужно.
     */
    suspend fun dubbingCatalog(studioIds: List<Int>): List<Anime> {
        val now = System.currentTimeMillis()
        val key = studioIds.sorted().joinToString(",")
        dubbingCache[key]?.let { (at, items) -> if (now - at < DUBBING_CACHE_MS) return items }
        val out = LinkedHashMap<String, Anime>()
        for (studio in studioIds) {
            var page = 1
            var last = 1
            while (page <= last && page <= DUBBING_MAX_PAGES) {
                val html = http.getHtml("$SITE/catalog/dubbing/$studio?sort=popular&page=$page") ?: break
                if (page == 1) last = dubbingLastPage(html, studio)
                val cards = parseDubbingCards(html)
                if (cards.isEmpty()) break
                cards.forEach { out.putIfAbsent(it.id, it) }
                page++
            }
        }
        val items = out.values.toList()
        if (items.isNotEmpty()) dubbingCache[key] = now to items
        return items
    }

    private val dubbingCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Anime>>>()

    override suspend fun ongoingPage(page: Int): List<Anime> =
        list(page, "sort=views&sort_forward=false&status=ongoing")

    /** Свежедобавленное С СЕРИЯМИ: анонсы в «Последние поступления» не годятся. */
    override suspend fun latestReleases(page: Int): List<Anime> =
        list(page, "sort=id&sort_forward=false").filter { it.airingStatus != 3 }

    override suspend fun seasonal(season: Int, year: Int, page: Int): List<Anime> =
        seasonPage(season, year, page, "top")

    override suspend fun watchingNow(season: Int, year: Int, page: Int): List<Anime> =
        seasonPage(season, year, page, "views")

    /** Год сервер не фильтрует — режем у себя; сортировка по убыванию держит текущий
     *  год наверху, так что первые страницы почти целиком его. */
    private suspend fun seasonPage(season: Int, year: Int, page: Int, sort: String): List<Anime> =
        list(page, "sort=$sort&sort_forward=false&season=$season").filter { it.year == year }

    override suspend fun random(): Anime? =
        list(0, "sort=random", limit = 1).firstOrNull()

    /** Порядок просмотра из карточки — это и есть франшиза целиком. */
    override suspend fun related(contentId: String): List<Anime> {
        val id = animeId(contentId) ?: return emptyList()
        val resp = http.getHtml("$API/anime/$id") ?: return emptyList()
        val order = runCatching { JSONObject(resp).optJSONObject("response")?.optJSONArray("viewing_order") }
            .getOrNull() ?: return emptyList()
        return (0 until order.length()).mapNotNull { i -> order.optJSONObject(i)?.let { animeFrom(it) } }
            .distinctBy { it.id }
    }

    override suspend fun recommended(contentId: String): List<Anime> {
        val id = animeId(contentId) ?: return emptyList()
        val resp = http.getHtml("$API/anime/$id/recommendations") ?: return emptyList()
        return parseList(resp)
    }

    /** Полная карточка: описание целиком, жанры, оригинальное название, студия,
     *  серии — в лентах этого нет. */
    suspend fun details(contentId: String): Anime? {
        val id = animeId(contentId) ?: return null
        val resp = http.getHtml("$API/anime/$id") ?: return null
        return runCatching { JSONObject(resp).optJSONObject("response") }.getOrNull()?.let { animeFrom(it) }
    }

    // ---- обсуждение ----

    /**
     * Одна страница обсуждения тайтла на Yummy: `/comments/anime/{id}?skip=N&limit=50&sort=`.
     * Сортировки — `new` (свежие, однозначный порядок — для обхода), `old`, `nice`
     * (по лайкам, для головы пула, как COMMENTS_TOP у Anixart). Ответы (`parent_id > 0`)
     * и удалённое отсеиваются — как в Anixart. Номера серии и пометки спойлера у
     * Yummy нет: `[spoiler]…[/spoiler]` в BB-коде считается пометкой, остальные теги
     * снимаются. Ключ `source = "yummy"` — чтобы записи не путались с Anixart в
     * общем накопителе.
     */
    suspend fun comments(contentId: String, page: Int, sort: String = COMMENTS_FRESH): List<com.aniblaze.aggregator.model.TitleComment> {
        val id = animeId(contentId) ?: return emptyList()
        val skip = page.coerceAtLeast(0) * COMMENT_PAGE
        val resp = http.getHtml("$API/comments/anime/$id?skip=$skip&limit=$COMMENT_PAGE&sort=$sort") ?: return emptyList()
        val arr = runCatching { JSONObject(resp).optJSONObject("response")?.optJSONArray("comments") }.getOrNull()
            ?: return emptyList()
        return parseComments(arr, keepReplies = false)
    }

    private fun parseComments(arr: JSONArray, keepReplies: Boolean, replyTo: String = ""): List<com.aniblaze.aggregator.model.TitleComment> =
        (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            if (c.optLong("deleted_at", 0L) > 0L) return@mapNotNull null
            val parent = c.optLong("parent_id", 0L)
            if (!keepReplies && parent > 0L) return@mapNotNull null
            val raw = c.optString("text").trim()
            val spoiler = raw.contains("[spoiler", ignoreCase = true) || raw.contains("[спойлер", ignoreCase = true)
            val message = raw.replace(BB_TAG, "").replace(Regex("""[ \t]+\n"""), "\n").trim()
            if (message.isBlank()) return@mapNotNull null
            com.aniblaze.aggregator.model.TitleComment(
                id = c.optLong("id"),
                source = "yummy",
                authorId = c.optLong("user_id").takeIf { it > 0 }?.toString().orEmpty(),
                author = c.optString("name").ifBlank { "аноним" },
                avatar = withScheme(c.optJSONObject("avatars")?.optString("small").orEmpty()),
                message = message,
                timestamp = c.optLong("time"),
                votes = c.optInt("likes") - c.optInt("dislikes"),
                isSpoiler = spoiler,
                replyCount = c.optInt("children_count"),
                parentId = parent,
                replyTo = if (parent > 0L) replyTo else "",
            )
        }

    /** Ответы под записью: `/comments/{id}/children?skip=&limit=`. [parentAuthor] — «@ник» в чате. */
    suspend fun commentReplies(commentId: Long, parentAuthor: String = ""): List<com.aniblaze.aggregator.model.TitleComment> {
        val resp = http.getHtml("$API/comments/$commentId/children?skip=0&limit=$COMMENT_PAGE") ?: return emptyList()
        val arr = runCatching { JSONObject(resp).optJSONObject("response")?.optJSONArray("comments") }.getOrNull()
            ?: return emptyList()
        return parseComments(arr, keepReplies = true, replyTo = parentAuthor)
    }

    // ---- ленты ----

    private suspend fun list(page: Int, query: String, limit: Int = PAGE_SIZE): List<Anime> {
        val offset = page.coerceAtLeast(0) * limit
        val resp = http.getHtml("$API/anime?limit=$limit&offset=$offset&$query") ?: return emptyList()
        return parseList(resp)
    }

    private fun sortQuery(sort: Int): String = when (sort) {
        SORT_FRESH -> "sort=id&sort_forward=false"
        SORT_VIEWS -> "sort=views&sort_forward=false&status=ongoing"
        SORT_RELEASE_DATE -> "sort=year&sort_forward=false"
        // `top` у Yummy — редакторский список, а не оценка: под жанровым фильтром он
        // отдавал 5.2, 3.0, 5.6… первыми. По оценке — `sort=rating` (проверено 18.09.2026).
        SORT_RATING -> "sort=rating&sort_forward=false"
        SORT_POPULAR -> "sort=views&sort_forward=false"
        SORT_RATING_ASC -> "sort=rating&sort_forward=true"
        SORT_ANNOUNCE -> "sort=views&sort_forward=false&status=announcement"
        else -> "sort=rating&sort_forward=false"
    }

    /**
     * Расписание выхода: у каждой записи `episodes.next_date` (epoch s) — из него
     * день недели для [Anime.broadcast] и точка отсчёта для [Anime.episodeEstimatedAt].
     * Порядок как у Anixart: сегодняшний день первым.
     */
    private suspend fun schedule(): List<Anime> {
        val resp = http.getHtml("$API/anime/schedule") ?: return emptyList()
        val arr = runCatching { JSONObject(resp).optJSONArray("response") }.getOrNull() ?: return emptyList()
        val today = java.time.LocalDate.now().dayOfWeek.value
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val next = o.optJSONObject("episodes")?.optLong("next_date", 0L) ?: 0L
            val weekday = if (next > 0) {
                java.time.Instant.ofEpochSecond(next).atZone(java.time.ZoneId.systemDefault()).dayOfWeek.value
            } else {
                0
            }
            animeFrom(o)?.copy(
                broadcast = weekday,
                airingStatus = 2,
                episodeEstimatedAt = if (next > 0) next * 1000 else 0L,
            )
        }.distinctBy { it.id }.sortedBy { ((it.broadcast - today) % 7 + 7) % 7 }
    }

    /** `имя жанра (нормализованное)` → id, один раз на процесс. */
    @Volatile private var genreCache: Map<String, Int>? = null

    private suspend fun genreIds(): Map<String, Int> {
        genreCache?.let { return it }
        val resp = http.getHtml("$API/anime/catalog?limit=1") ?: return emptyMap()
        val genres = runCatching {
            JSONObject(resp).optJSONObject("response")?.optJSONObject("genres")?.optJSONArray("genres")
        }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, Int>()
        for (i in 0 until genres.length()) {
            val g = genres.optJSONObject(i) ?: continue
            val id = g.optInt("value", 0).takeIf { it > 0 } ?: continue
            map[norm(g.optString("title"))] = id
            g.optJSONArray("more_titles")?.let { more ->
                for (j in 0 until more.length()) map[norm(more.optString(j))] = id
            }
        }
        // Названия у каталогов расходятся: у Anixart «фантастика» и «исторический»,
        // у Yummy «Фантастика» и «История» — регистр и «ё» снимает нормализация,
        // остальное — синонимы.
        mapOf(
            "научная фантастика" to "фантастика", "исторический" to "история", "историческое" to "история",
            "психологическое" to "психология", "исекай" to "исэкай",
        ).forEach { (from, to) -> map[norm(to)]?.let { map.putIfAbsent(norm(from), it) } }
        return map.also { genreCache = it }
    }

    private fun norm(s: String): String = s.lowercase().replace('ё', 'е').trim()

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        val dubs = dubs(contentId)
        // Озвучка по умолчанию — та, у которой больше серий НА РАЗБИРАЕМОМ хосте:
        // Alloha мы не читаем, и озвучка с двадцатью сериями там — это ноль серий.
        val chosen = explicitDub(contentId)?.let { dubs.getOrNull(it) }
            ?: dubs.maxByOrNull { dub -> dub.episodes.count { (_, videos) -> videos.any(::supportedHost) } }
            ?: return emptyList()
        return chosen.episodes.keys.sorted().map { number ->
            val videos = chosen.episodes.getValue(number)
            Segment(
                id = (videos.firstOrNull { supportedHost(it) } ?: videos.first()).iframe,
                contentId = contentId,
                number = number,
                title = "Серия $number",
                playable = videos.any(::supportedHost),
            )
        }
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        val dubs = dubs(contentId)
        if (dubs.isEmpty()) return null
        val preferred = explicitDub(contentId)?.let { dubs.getOrNull(it) }
        // Выбранная озвучка первой, остальные — по просмотрам. Одна озвучка может
        // жить только на Alloha, которую мы не разбираем, и ронять из-за неё тайтл
        // нельзя — берём следующую, как Anixart и AnimeOn.
        val order = buildList {
            preferred?.let { add(it) }
            dubs.filter { it !== preferred }.sortedByDescending { it.views }.forEach { add(it) }
        }
        // В пикер — только озвучки, у которых ЭТА серия есть и лежит на хосте, который
        // мы умеем читать. Раньше список включал все озвучки тайтла: выбираешь — а
        // серии в ней нет или она на Alloha, и плеер молча брал другую. id — индекс в
        // полном списке dubs, чтобы `:tN` в contentId не поехал.
        val translations = dubs.mapIndexedNotNull { index, dub ->
            val here = dub.episodes[segment].orEmpty()
            if (here.none(::supportedHost)) null else Translation(index, dub.name, dub.isSub, dub.views)
        }
        for (dub in order) {
            val videos = dub.episodes[segment] ?: continue
            // Внутри озвучки Kodik первым — у него есть варианты качества; Sibnet
            // одним файлом идёт следом.
            for (video in videos.sortedBy { hostRank(video = it) }) {
                val variants = streamsFor(video.iframe)
                if (variants.isEmpty()) continue
                return ContentResult(
                    location = variants.first().url,
                    quality = variants.first().quality,
                    variants = variants,
                    translations = translations,
                    translationId = dubs.indexOfFirst { it === dub },
                    source = name,
                    referer = refererFor(video.iframe),
                    opening = video.opening,
                    metadata = mapOf("contentId" to contentId, "segment" to segment.toString()),
                )
            }
            Timber.w("[Yummy] no playable video for %s ep %d via %s", contentId, segment, dub.name)
        }
        return null
    }

    override suspend fun validateSource(contentId: String): Boolean =
        http.getHtml("$API/anime?limit=1") != null

    // ---- потоки ----

    /**
     * Сбой ОДНОГО хостинга — не сбой тайтла: та же серия обычно лежит и на другом.
     * Поэтому ошибка здесь гасится в пустой список, и перебор идёт дальше; наружу
     * уходит только «ничего не нашлось».
     */
    private suspend fun streamsFor(iframe: String): List<StreamVariant> = runCatching {
        when {
            iframe.contains("kodik", ignoreCase = true) -> kodik.extract(iframe)
            SibnetExtractor.owns(iframe) -> sibnet.extract(iframe)
            else -> emptyList() // Alloha и прочее
        }
    }.onFailure { Timber.w(it, "[Yummy] host failed: %s", iframe.take(80)) }.getOrDefault(emptyList())

    /** Хост, который мы разбираем (см. streamsFor): Kodik и Sibnet; Alloha и прочее — нет. */
    private fun supportedHost(video: Video): Boolean =
        video.iframe.contains("kodik", ignoreCase = true) || SibnetExtractor.owns(video.iframe)

    private fun hostRank(video: Video): Int = when {
        video.iframe.contains("kodik", ignoreCase = true) -> 0
        SibnetExtractor.owns(video.iframe) -> 1
        else -> 2
    }

    private fun refererFor(iframe: String): String =
        if (SibnetExtractor.owns(iframe)) SibnetExtractor.REFERER else KODIK_REF

    // ---- разбор видео ----

    private data class Video(val iframe: String, val views: Long, val opening: OpeningRange?)

    private data class Dub(
        val name: String,
        val isSub: Boolean,
        val views: Long,
        /** номер серии → видео этой серии в этой озвучке (Kodik, Sibnet, Alloha…) */
        val episodes: Map<Int, List<Video>>,
    )

    /** Озвучки в СТАБИЛЬНОМ порядке (по имени). */
    private suspend fun dubs(contentId: String): List<Dub> {
        val id = animeId(contentId) ?: return emptyList()
        val resp = http.getHtml("$API/anime/$id/videos") ?: return emptyList()
        val arr = runCatching { JSONObject(resp).optJSONArray("response") }.getOrNull() ?: return emptyList()
        val byDub = mutableMapOf<String, MutableMap<Int, MutableList<Video>>>()
        val viewsByDub = mutableMapOf<String, Long>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val iframe = v.optString("iframe_url").takeIf { it.isNotBlank() } ?: continue
            val number = v.optString("number").toIntOrNull() ?: continue
            val dubName = v.optJSONObject("data")?.optString("dubbing").orEmpty()
                .removePrefix("Озвучка").trim().ifBlank { "Без названия" }
            val views = v.optLong("views", 0)
            byDub.getOrPut(dubName) { mutableMapOf() }
                .getOrPut(number) { mutableListOf() }
                .add(Video(withScheme(iframe), views, openingOf(v)))
            viewsByDub[dubName] = (viewsByDub[dubName] ?: 0L) + views
        }
        return byDub.keys.sorted().map { dubName ->
            Dub(
                name = dubName,
                isSub = dubName.contains("суб", ignoreCase = true) || dubName.contains("sub", ignoreCase = true),
                views = viewsByDub[dubName] ?: 0L,
                episodes = byDub.getValue(dubName),
            )
        }
    }

    /** `skips.opening = {time, length}` в секундах; отсутствует у большинства. */
    private fun openingOf(v: JSONObject): OpeningRange? {
        val op = v.optJSONObject("skips")?.optJSONObject("opening") ?: return null
        val start = op.optDouble("time", Double.NaN).takeIf(Double::isFinite) ?: return null
        val length = op.optDouble("length", Double.NaN).takeIf(Double::isFinite) ?: return null
        return OpeningRange((start * 1000).toLong(), ((start + length) * 1000).toLong()).takeIf(OpeningRange::isValid)
    }

    // ---- разбор каталога ----

    private fun parseList(resp: String): List<Anime> {
        val arr = runCatching { JSONObject(resp).optJSONArray("response") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { animeFrom(it) } }
            .distinctBy { it.id }
    }

    private fun animeFrom(o: JSONObject): Anime? {
        val id = o.optInt("anime_id", 0).takeIf { it > 0 } ?: return null
        val title = o.optString("title").ifBlank { return null }
        val original = o.optJSONArray("other_titles")?.let { t ->
            (0 until t.length()).map { t.optString(it) }
                .firstOrNull { name -> name.isNotBlank() && name.none { c -> c in 'а'..'я' || c in 'А'..'Я' } }
        }.orEmpty()
        if (BannedContent.isBanned(title, original)) return null
        val poster = o.optJSONObject("poster")?.let { p ->
            listOf("big", "fullsize", "medium", "small").map { p.optString(it) }.firstOrNull { it.isNotBlank() }
        }.orEmpty().let(::withScheme)
        val rating = o.optJSONObject("rating")
        // Собственная оценка Yummy завышена (весь верх каталога 9+ — ранг «Легенда»
        // получал каждый). Шкала ранга калибрована по Shikimori — берём её, когда
        // есть; среднее Yummy только как запасное.
        val shikimori = rating?.optDouble("shikimori_rating", 0.0) ?: 0.0
        val grade = if (shikimori > 0) shikimori else rating?.optDouble("average", 0.0) ?: 0.0
        val genres = o.optJSONArray("genres")?.let { g ->
            (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("title") }.joinToString(", ")
        }.orEmpty()
        val episodes = o.optJSONObject("episodes")
        val views = o.optLong("views", 0L)
        return Anime(
            id = "$PREFIX$id",
            title = title,
            poster = poster,
            description = o.optString("description").replace(BB_TAG, ""),
            rating = grade,
            ratingMax = 10.0,
            ratingVotes = rating?.optInt("counters", 0) ?: 0,
            // Как у Anixart: в `status` живёт ОРИГИНАЛЬНОЕ название — по нему точнее
            // ищут Shikimori/AniSkip (см. DesktopRepository.fullDetails).
            status = original,
            genres = genres,
            year = o.optInt("year", 0),
            // Просмотры — единственная мера аудитории у Yummy; ранг «Легенда» и
            // сортировка «по просмотрам» читают именно это поле.
            watchingCount = views.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            // Ряд «Ожидаемые анонсы» ранжирует и отсеивает по favoritesCount (>= 500
            // у Anixart); у Yummy мера интереса одна — просмотры, ставим их и сюда.
            favoritesCount = views.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            studio = o.optJSONArray("studios")?.optJSONObject(0)?.optString("title").orEmpty(),
            episodesTotal = episodes?.optInt("count", 0) ?: 0,
            episodesAvailable = episodes?.optInt("aired", 0) ?: 0,
            episodeEstimatedAt = (episodes?.optLong("next_date", 0L) ?: 0L).let { if (it > 0) it * 1000 else 0L },
            // min_age.value у Yummy — 0..5 в той же лестнице (G, PG, PG-13, R, R+…).
            ageRating = o.optJSONObject("min_age")?.optInt("value", 0)?.coerceIn(0, 5) ?: 0,
            contentType = when (o.optJSONObject("type")?.optString("alias")) {
                "tv" -> "Сериал"
                "movie" -> "Фильм"
                "ova", "ona" -> "OVA"
                "special" -> "Спешл"
                else -> ""
            },
            airingStatus = when (o.optJSONObject("anime_status")?.optString("alias")) {
                "released" -> 1
                "ongoing" -> 2
                "announcement", "anons" -> 3
                else -> 0
            },
            // Точный MAL id сезона — им ищем расписание и трейлер, а не названием.
            malId = o.optJSONObject("remote_ids")?.let { r ->
                r.optInt("myanimelist_id", 0).takeIf { it > 0 } ?: r.optInt("shikimori_id", 0)
            } ?: 0,
            // Есть только в полной карточке `/anime/{id}`; в лентах поля нет.
            sourceMaterial = o.optString("original").takeIf { it != "null" }.orEmpty(),
        )
    }

    // ---- id ----

    /** `ya:111` или `ya:111:t2` → 111. */
    private fun animeId(contentId: String): String? =
        contentId.removePrefix(PREFIX).substringBefore(':').takeIf { it.isNotBlank() && it.all(Char::isDigit) }

    private fun explicitDub(contentId: String): Int? =
        Regex(""":t(\d+)""").find(contentId)?.groupValues?.get(1)?.toIntOrNull()

    private fun withScheme(url: String): String = if (url.startsWith("//")) "https:$url" else url

    companion object {
        /** Сайт (не API): нужен только ради каталога студий озвучки. */
        private const val SITE = "https://site.yummyani.me"
        private const val DUBBING_MAX_PAGES = 20
        private const val DUBBING_CACHE_MS = 60 * 60_000L

        private val CARD = Regex("""<div class="anime-column" data-anime-id="(\d+)">(.*?)<div class="rating-bottom">""", RegexOption.DOT_MATCHES_ALL)
        private val CARD_TITLE = Regex("""class="anime-title"[^>]*>([^<]+)<""")
        private val CARD_POSTER = Regex("""<img src="([^"]+)""")
        private val CARD_RATING = Regex("""class="main-rating materiable">([\d.]+)<""")
        private val CARD_TYPE = Regex("""</a>\s*<div>([^<]+)</div>""")
        private val CARD_VIEWS = Regex("""class="views-count">[^<]*<i[^>]*></i>([\d\s ]+)<""")
        private val CARD_VOTES = Regex("""Количество голосов"><i[^>]*></i>([\d\s ]+)<""")

        /** Карточки каталога студии — чистая функция ради теста. */
        fun parseDubbingCards(html: String): List<Anime> = CARD.findAll(html).mapNotNull { m ->
            val id = m.groupValues[1]
            val body = m.groupValues[2]
            val title = CARD_TITLE.find(body)?.groupValues?.get(1)?.let(::unescapeHtml)?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val poster = CARD_POSTER.find(body)?.groupValues?.get(1)?.let { if (it.startsWith("//")) "https:$it" else it }.orEmpty()
            Anime(
                id = PREFIX + id,
                title = title,
                poster = poster,
                rating = CARD_RATING.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
                ratingMax = 10.0,
                ratingVotes = CARD_VOTES.find(body)?.groupValues?.get(1)?.let(::digits) ?: 0,
                watchingCount = CARD_VIEWS.find(body)?.groupValues?.get(1)?.let(::digits) ?: 0,
                contentType = CARD_TYPE.find(body)?.groupValues?.get(1)?.trim().orEmpty(),
            )
        }.toList()

        /** Номер последней страницы из пагинации; нет пагинации — одна страница. */
        fun dubbingLastPage(html: String, studio: Int): Int =
            Regex("""dubbing/$studio\?sort=[a-z-]+&(?:amp;)?page=(\d+)""").findAll(html)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1

        private fun digits(raw: String): Int = raw.filter { it.isDigit() }.toIntOrNull() ?: 0

        private fun unescapeHtml(raw: String): String = raw
            .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

        const val API = "https://api.yani.tv"
        private const val PREFIX = "ya:"
        private const val PAGE_SIZE = 30
        const val KODIK_REF = "https://kodikplayer.com/"
        const val COMMENT_PAGE = 50
        const val COMMENTS_FRESH = "new"
        const val COMMENTS_TOP = "nice"
        /** `[b]`, `[/spoiler]`, `[п]`… — BB-теги Yummy, включая кириллические. */
        val BB_TAG = Regex("""\[/?[\p{L}\p{N}_=]+\]""")

        // Номера сортировок в терминах Anixart (см. catalogPage).
        const val SORT_FRESH = 0
        const val SORT_VIEWS = 1
        const val SORT_RELEASE_DATE = 2
        const val SORT_RATING = 3
        const val SORT_POPULAR = 4
        const val SORT_RATING_ASC = 5
        const val SORT_ANNOUNCE = 6
    }
}

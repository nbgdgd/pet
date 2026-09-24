package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.AgeRating
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogPage
import com.aniblaze.aggregator.model.CatalogSort
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.aggregator.model.ContentType
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.FilterFacets
import com.aniblaze.aggregator.model.TitleStatus
import com.aniblaze.aggregator.model.EpisodeSchedule
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.aggregator.lampa.LampaExtractor
import com.aniblaze.network.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * A fully independent "Кино" source: its CATALOG comes from TMDB (posters, ratings,
 * genres, real pagination → infinite scroll), and PLAYBACK goes through the Lampa
 * plugin's balancers (studio dubs, OK.ru streams). TMDB ids are namespaced `tmdb:<id>`
 * so the repository routes them here; on play we look up the title's imdb id and run
 * the selected balancer.
 */
class LampaCatalogSource(
    private val http: HttpClient,
    private val lampa: LampaExtractor,
    private val pluginUrl: () -> String,
    private val balancer: () -> String,
) : CinemaSource {
    private val detailsCache = CinemaMetadataCache<TmdbDetails>()
    private val mediaCache = CinemaMetadataCache<Map<String, Any?>>()

    /** Android warms the plugin and title identity while the detail page is open. */
    suspend fun preparePlayback(contentId: String) = coroutineScope {
        val target = cinemaTarget(contentId) ?: return@coroutineScope
        val metadata = async { tvOrMovieMap(target.id, target.isTv) }
        val plugin = async(Dispatchers.IO) {
            try { lampa.load(pluginUrl()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Timber.w(error, "Cinema plugin warm-up failed"); null }
        }
        metadata.await()
        plugin.await()
        Unit
    }

    private suspend fun details(kind: String, id: String, language: String = "ru-RU"): TmdbDetails? =
        detailsCache.get("$kind:$id:$language") {
            get("$API/$kind/$id?$KEY&language=$language&append_to_response=external_ids")?.let { raw ->
                runCatching { json.decodeFromString<TmdbDetails>(raw) }.getOrNull()
            }
        }

    override val name: String = "LampaTMDB"
    override val alwaysActive: Boolean = true
    override val key: String = "lampa"
    override val displayName: String = "Lampa (TMDB + балансеры)"
    override val drawbacks: String =
        "Каталог TMDB — постеры, оценки, жанры, бесконечная прокрутка. Играет через балансеры " +
            "плагина (студии-озвучки, OK.ru). Балансер выбирается в разделе «Плагин Lampa». " +
            "Лучше всех тут работает rezka (ищет по imdb/названию). collaps/cdnmovies/vibix " +
            "хотят KP-id, которого у TMDB нет — если выбранный балансер ничего не нашёл, " +
            "источник автоматически падает на rezka/cdnmovies/vibix, так что видео всё равно играет."
    override val paginates: Boolean = true
    // Films + series. Genres are NOT here — they live in the «Жанр» filter below to
    // avoid duplicating the whole genre list twice on screen.
    override val categories: List<Pair<String, String>> = listOf(
        "trending" to "Сейчас смотрят",
        "popular" to "Популярное",
        "now_playing" to "В кино",
        "top_rated" to "Топ рейтинга",
        "upcoming" to "Скоро",
        "tv:trending" to "Сериалы: смотрят",
        "tv:popular" to "Сериалы",
        "tv:on_the_air" to "Сериалы: выходят",
        "tv:top_rated" to "Сериалы: топ",
        // Animation (TMDB genre 16). Japanese titles are excluded so this stays
        // cartoons/мультсериалы — anime has its own whole side of the app.
        //
        // The «Мультфильмы» tab used to have exactly the two type lists below and
        // nothing else, so it was a flat catalog next to an anime home full of live
        // rows. These are the same feeds as «Кино» and «Главная», locked inside the
        // animation genre.
        "cartoon:trending" to "Сейчас смотрят",
        "cartoon:new_episodes" to "Вышли новые серии",
        "cartoon:hot" to "В тренде",
        "cartoon:latest" to "Последние поступления",
        "cartoon:popular" to "Популярное за всё время",
        "cartoon:top" to "Топ рейтинга",
        "cartoon:movie" to "Мультфильмы",
        "cartoon:tv" to "Мультсериалы",
    )

    override val supportsServerFilters: Boolean = true

    // TMDB genre ids → Russian labels, for the advanced filter panel.
    override val genres: List<Pair<String, String>> = listOf(
        "28" to "Боевик", "12" to "Приключения", "16" to "Мультфильм", "35" to "Комедия",
        "80" to "Криминал", "99" to "Документальный", "18" to "Драма", "10751" to "Семейный",
        "14" to "Фэнтези", "36" to "История", "27" to "Ужасы", "10402" to "Музыка",
        "9648" to "Детектив", "10749" to "Мелодрама", "878" to "Фантастика", "53" to "Триллер",
        "10752" to "Военный", "37" to "Вестерн",
    )

    override fun owns(contentId: String): Boolean =
        contentId.startsWith(ID_PREFIX) || contentId.startsWith(TV_PREFIX)

    override suspend fun description(contentId: String): String? {
        val base = contentId.substringBefore(":t")
        val isTv = base.startsWith(TV_PREFIX)
        val tmdbId = when {
            isTv -> base.removePrefix(TV_PREFIX)
            base.startsWith(ID_PREFIX) -> base.removePrefix(ID_PREFIX)
            else -> return null
        }.takeIf { it.isNotBlank() } ?: return null

        suspend fun overview(language: String): String? = details(if (isTv) "tv" else "movie", tmdbId, language)
            ?.overview?.trim()?.takeIf { it.isNotBlank() }
        return overview("ru-RU") ?: overview("en-US")
    }

    override suspend fun similar(contentId: String): List<Anime> {
        val target = cinemaTarget(contentId) ?: return emptyList()
        suspend fun load(language: String) = parse(
            get("$API/${target.kind}/${target.id}/similar?$KEY&language=$language&page=1"),
            tv = target.isTv,
        ).filter { it.id != target.base }
        return load("ru-RU").ifEmpty { load("en-US") }.take(20)
    }

    override suspend fun comments(contentId: String, page: Int): List<com.aniblaze.aggregator.model.TitleComment> {
        val target = cinemaTarget(contentId) ?: return emptyList()
        return parseReviews(
            get("$API/${target.kind}/${target.id}/reviews?$KEY&language=ru-RU&page=${page + 1}"),
        )
    }

    private data class CinemaTarget(val base: String, val id: String, val isTv: Boolean) {
        val kind: String get() = if (isTv) "tv" else "movie"
    }

    private fun cinemaTarget(contentId: String): CinemaTarget? {
        val base = contentId.substringBefore(":t")
        val isTv = base.startsWith(TV_PREFIX)
        val id = when {
            isTv -> base.removePrefix(TV_PREFIX)
            base.startsWith(ID_PREFIX) -> base.removePrefix(ID_PREFIX)
            else -> return null
        }.takeIf { it.isNotBlank() } ?: return null
        return CinemaTarget(base, id, isTv)
    }

    override suspend fun search(query: String): List<Anime> = emptyList()
    override suspend fun trending(): List<Anime> = emptyList()

    override suspend fun browsePath(path: String, page: Int): List<Anime> {
        val p = page.coerceAtLeast(1)
        return when {
            // "Сейчас смотрят" — a mix of what people are watching (trending) AND what
            // just came out (now playing), so it's both fresh and popular.
            path == "trending" -> mixTrending(p)
            path == "tv:trending" -> parse(get("$API/trending/tv/week?$KEY&language=ru-RU&page=$p"), tv = true)
            // Cartoons: animation genre minus Japanese originals (those are anime and
            // live on the Главная side of the app).
            path == "cartoon:movie" -> cartoonPage(tv = false, "sort_by=popularity.desc&vote_count.gte=30", p)
            path == "cartoon:tv" -> cartoonPage(tv = true, "sort_by=popularity.desc&vote_count.gte=20", p)
            // «Сейчас смотрят»: популярное ИЗ ТОГО, ЧТО ИДЁТ СЕЙЧАС — у фильмов окно
            // проката, у сериалов вышедшая на днях серия. Без окна это была бы просто
            // копия «В тренде».
            path == "cartoon:trending" -> {
                val today = LocalDate.now()
                interleave(
                    cartoonPage(false, "sort_by=popularity.desc&primary_release_date.gte=${today.minusDays(120)}&primary_release_date.lte=$today", p),
                    cartoonPage(true, "sort_by=popularity.desc&air_date.gte=${today.minusDays(30)}&air_date.lte=$today&vote_count.gte=20", p),
                )
            }
            // «Вышли новые серии»: мультсериалы, у которых серия вышла за две недели.
            path == "cartoon:new_episodes" -> {
                val today = LocalDate.now()
                cartoonPage(true, "sort_by=popularity.desc&air_date.gte=${today.minusDays(14)}&air_date.lte=$today&vote_count.gte=10", p)
            }
            // «В тренде»: самое популярное прямо сейчас, без оглядки на даты. Пороги
            // голосов здесь заметно выше остальных лент — ЗАМЕРЕНО: с порогом 10 первым
            // в «мультсериалах» приходил безымянный порноспин-офф с 26 голосами.
            path == "cartoon:hot" -> interleave(
                cartoonPage(false, "sort_by=popularity.desc&vote_count.gte=100", p),
                cartoonPage(true, "sort_by=popularity.desc&vote_count.gte=50", p),
            )
            // «Последние поступления»: свежайшее по дате выхода. Порог голосов держит
            // подальше безымянный мусор, который чистая сортировка по дате выносит вверх.
            path == "cartoon:latest" -> {
                val today = LocalDate.now()
                interleave(
                    cartoonPage(false, "sort_by=primary_release_date.desc&primary_release_date.lte=$today&vote_count.gte=20", p),
                    cartoonPage(true, "sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=10", p),
                )
            }
            // «Популярное за всё время»: по числу проголосовавших, а не по текущей
            // популярности, — это и есть «сколько людей это вообще посмотрело».
            path == "cartoon:popular" -> interleave(
                cartoonPage(false, "sort_by=vote_count.desc", p),
                cartoonPage(true, "sort_by=vote_count.desc", p),
            )
            // «Топ рейтинга»: высокая оценка при заметной аудитории — без порога
            // голосов наверх всплывают десятки с тремя голосами.
            path == "cartoon:top" -> interleave(
                cartoonPage(false, "sort_by=vote_average.desc&vote_count.gte=300", p),
                cartoonPage(true, "sort_by=vote_average.desc&vote_count.gte=300", p),
            )
            // The «Кино» landing's special rows (see DesktopRepository.cinemaSpecialRows) —
            // the same live shape as the anime home, built from TMDB feeds:
            // "Вышли новые серии": series that actually aired an episode in the last
            // two weeks (air_date window), most popular first.
            path == "new_episodes" -> {
                val today = LocalDate.now()
                parse(
                    get("$API/discover/tv?$KEY&language=ru-RU&sort_by=popularity.desc&air_date.gte=${today.minusDays(14)}&air_date.lte=$today&vote_count.gte=20&page=$p"),
                    tv = true,
                )
            }
            // "В тренде": this week's trending films and series, interleaved.
            path == "hot" -> interleave(
                parse(get("$API/trending/movie/week?$KEY&language=ru-RU&page=$p"), tv = false),
                parse(get("$API/trending/tv/week?$KEY&language=ru-RU&page=$p"), tv = true),
            )
            // "Последние поступления": newest actual releases — films by release date,
            // series by first air date, interleaved. A small vote floor keeps out the
            // zero-audience noise that a pure date sort surfaces.
            path == "latest" -> {
                val today = LocalDate.now()
                interleave(
                    parse(get("$API/discover/movie?$KEY&language=ru-RU&sort_by=primary_release_date.desc&primary_release_date.lte=$today&vote_count.gte=20&page=$p"), tv = false),
                    parse(get("$API/discover/tv?$KEY&language=ru-RU&sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=10&page=$p"), tv = true),
                )
            }
            path.startsWith("tv:") -> parse(get("$API/tv/${path.removePrefix("tv:")}?$KEY&language=ru-RU&page=$p"), tv = true)
            // Advanced filter panel → server-side /discover query (movie or tv).
            path.startsWith("discover:") -> {
                val q = path.removePrefix("discover:")
                parse(get(discoverUrl(q, p)), tv = q.contains("t=tv"))
            }
            path.startsWith("genre:") ->
                parse(get("$API/discover/movie?$KEY&language=ru-RU&sort_by=popularity.desc&vote_count.gte=50&with_genres=${path.removePrefix("genre:")}&page=$p"), tv = false)
            else -> parse(get("$API/movie/${path.ifBlank { "popular" }}?$KEY&language=ru-RU&region=RU&page=$p"), tv = false)
        }
    }

    /**
     * Какие фильтры умеет TMDB — и, что важнее, какие НЕ умеет.
     *
     * Проверено запросами, а не документацией:
     *  • возрастной сертификат работает ТОЛЬКО у фильмов и только вместе с
     *    `certification_country`; у сериалов тот же параметр даёт ПУСТОЙ ответ
     *    (total_results = 0), поэтому сериалам он не предлагается и не отправляется;
     *  • «выходит / завершено» — наоборот, только у сериалов (`with_status`);
     *  • количества серий в /discover нет вовсе, поэтому этого фасета тут не будет;
     *  • «по новизне» и «по дате выхода» у TMDB это одна и та же сортировка, так что
     *    предлагается одна.
     */
    override fun filterFacets(cartoons: Boolean): FilterFacets = FilterFacets(
        tags = if (cartoons) CatalogTag.CINEMA.filter { it.tmdb != ANIMATION_GENRE } else CatalogTag.CINEMA,
        sorts = listOf(CatalogSort.POPULAR, CatalogSort.RATING, CatalogSort.VIEWS, CatalogSort.RELEASE_DATE),
        years = DECADES,
        statuses = listOf(TitleStatus.ONGOING, TitleStatus.FINISHED, TitleStatus.ANNOUNCED),
        ageRatings = listOf(AgeRating.KIDS, AgeRating.SIX, AgeRating.TWELVE, AgeRating.ADULT),
        // Японии в списке мультфильмов нет намеренно: японская анимация — это аниме,
        // и у неё своя сторона приложения (см. parse/excludeJapanese).
        countries = if (cartoons) CARTOON_COUNTRIES else CINEMA_COUNTRIES,
        episodes = emptyList(),
        contentTypes = listOf(ContentType.MOVIE, ContentType.SERIES),
        minRatings = listOf(6.0, 7.0, 8.0),
    )

    override suspend fun browseFiltered(filter: CatalogFilter, page: Int, cartoons: Boolean): CatalogPage {
        val p = page.coerceAtLeast(1)
        // Тип не выбран — показываем и фильмы, и сериалы вперемешку, как это делают
        // обычные ленты раздела. Найдено при этом складывается: два запроса, два счёта.
        val wantMovie = filter.contentType != ContentType.SERIES
        val wantTv = filter.contentType != ContentType.MOVIE
        val movie = if (wantMovie) discoverPage(filter, p, tv = false, cartoons = cartoons) else null
        val tv = if (wantTv) discoverPage(filter, p, tv = true, cartoons = cartoons) else null
        val items = when {
            movie != null && tv != null -> interleave(movie.items, tv.items)
            else -> (movie ?: tv)?.items.orEmpty()
        }
        val total = (movie?.total ?: 0) + (tv?.total ?: 0)
        return CatalogPage(items, total)
    }

    private suspend fun discoverPage(filter: CatalogFilter, page: Int, tv: Boolean, cartoons: Boolean): CatalogPage {
        val kind = if (tv) "tv" else "movie"
        val url = StringBuilder("$API/discover/$kind?$KEY&language=ru-RU&include_adult=false&page=$page")

        val genres = buildList {
            if (cartoons) add(ANIMATION_GENRE)
            filter.tags.mapNotNullTo(this) { CatalogTag.byKey(it)?.tmdb?.takeIf { id -> id != 0 } }
        }.distinct()
        // Запятая — это И. Через `|` было бы ИЛИ, но выбранные теги должны СУЖАТЬ
        // выдачу, а не расширять её: замерено, боевик+комедия это 9 120 фильмов
        // против 214 266 у «боевик или комедия».
        if (genres.isNotEmpty()) url.append("&with_genres=").append(genres.joinToString(","))

        val (gte, lte) = if (tv) "first_air_date.gte" to "first_air_date.lte"
        else "primary_release_date.gte" to "primary_release_date.lte"
        if (filter.yearFrom > 0) url.append("&$gte=${filter.yearFrom}-01-01")
        if (filter.yearTo > 0) url.append("&$lte=${filter.yearTo}-12-31")

        if (filter.country.isNotBlank()) {
            countryCode(filter.country)?.let { url.append("&with_origin_country=$it") }
        }
        // Сертификат — только фильмам (у сериалов пустой ответ), статус — только сериалам.
        if (!tv) {
            filter.ageRating?.let { url.append("&certification_country=US&certification=${it.certification}") }
        } else {
            filter.status?.let { url.append("&with_status=${tvStatusOf(it)}") }
        }

        if (filter.minRating > 0.0) url.append("&vote_average.gte=${filter.minRating}")

        when (filter.sort) {
            CatalogSort.POPULAR -> url.append("&sort_by=popularity.desc")
            // Без порога голосов вершина «по рейтингу» состоит из десяток с тремя
            // голосами — это не топ, это шум.
            CatalogSort.RATING -> url.append("&sort_by=vote_average.desc&vote_count.gte=200")
            CatalogSort.VIEWS -> url.append("&sort_by=vote_count.desc")
            CatalogSort.FRESH, CatalogSort.RELEASE_DATE ->
                url.append(if (tv) "&sort_by=first_air_date.desc" else "&sort_by=primary_release_date.desc")
                    .append("&vote_count.gte=10")
        }

        val raw = get(url.toString()) ?: return CatalogPage(emptyList())
        val resp = runCatching { json.decodeFromString<TmdbResp>(raw) }.getOrNull()
            ?: return CatalogPage(emptyList())
        return CatalogPage(parse(raw, tv = tv, excludeJapanese = cartoons), resp.total_results)
    }

    /** TMDB's own tv status ids (measured: 0 airing, 1 planned, 3 ended). */
    private fun tvStatusOf(status: TitleStatus): Int = when (status) {
        TitleStatus.ONGOING -> 0
        TitleStatus.ANNOUNCED -> 1
        TitleStatus.FINISHED -> 3
    }

    private fun countryCode(country: String): String? = COUNTRY_CODES[country]

    /** One page of the cartoon set: animation genre, nothing adult, and nothing
     *  originally Japanese — that is anime, and it has its own side of the app.
     *  Японское отсеивается уже в [parse]: у TMDB нет параметра, который бы это сделал. */
    private suspend fun cartoonPage(tv: Boolean, extra: String, page: Int): List<Anime> = parse(
        get(
            "$API/discover/${if (tv) "tv" else "movie"}?$KEY&language=ru-RU&include_adult=false" +
                "&with_genres=16&$extra&page=$page",
        ),
        tv = tv,
        excludeJapanese = true,
    )

    /** "Сейчас смотрят" — interleave trending (watched) with now-playing (just released). */
    private suspend fun mixTrending(page: Int): List<Anime> = interleave(
        parse(get("$API/trending/movie/week?$KEY&language=ru-RU&page=$page"), tv = false),
        parse(get("$API/movie/now_playing?$KEY&language=ru-RU&region=RU&page=$page"), tv = false),
    )

    /** Alternate two lists item-by-item, de-duplicated by id, order preserved. */
    private fun interleave(a: List<Anime>, b: List<Anime>): List<Anime> {
        val out = LinkedHashMap<String, Anime>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out.putIfAbsent(it.id, it) }
            b.getOrNull(i)?.let { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }

    private suspend fun get(url: String): String? = http.getHtml(url, referer = REF)

    /** Build a TMDB /discover query from a filter string `g=28&y=2024&s=rating&r=7&t=tv`. */
    private fun discoverUrl(query: String, page: Int): String {
        val params = query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2); if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val isTv = params["t"] == "tv"
        val kind = if (isTv) "tv" else "movie"
        val sb = StringBuilder("$API/discover/$kind?$KEY&language=ru-RU&include_adult=false&page=$page")
        params["g"]?.takeIf { it.isNotBlank() }?.let { sb.append("&with_genres=$it") }
        params["y"]?.takeIf { it.isNotBlank() }?.let { y ->
            val (gte, lte) = if (isTv) "first_air_date.gte" to "first_air_date.lte" else "primary_release_date.gte" to "primary_release_date.lte"
            if (y.contains("-")) {
                val (a, b) = y.split("-"); sb.append("&$gte=$a-01-01&$lte=$b-12-31")
            } else if (isTv) sb.append("&first_air_date_year=$y") else sb.append("&primary_release_year=$y")
        }
        val minRating = params["r"]?.toDoubleOrNull() ?: 0.0
        if (minRating > 0) sb.append("&vote_average.gte=$minRating")
        val sort = when (params["s"]) {
            "rating" -> "vote_average.desc"
            "new" -> if (isTv) "first_air_date.desc" else "primary_release_date.desc"
            "revenue" -> if (isTv) "popularity.desc" else "revenue.desc" // revenue N/A for tv
            else -> "popularity.desc"
        }
        sb.append("&sort_by=$sort")
        // Rating/newest sorts need a vote floor or they surface obscure 1-vote titles.
        sb.append("&vote_count.gte=").append(if (sort == "popularity.desc") 50 else 200)
        return sb.toString()
    }

    override suspend fun searchCinema(query: String): List<Anime> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        // Multi-search returns both movies and series.
        val movies = parse(get("$API/search/movie?$KEY&language=ru-RU&query=$q&page=1"), tv = false)
        val tv = parse(get("$API/search/tv?$KEY&language=ru-RU&query=$q&page=1"), tv = true)
        return (movies + tv).sortedByDescending { it.rating }
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        if (!owns(contentId)) return emptyList()
        val base = contentId.substringBefore(":t")
        if (!base.startsWith(TV_PREFIX)) return listOf(Segment(id = "$base#1", contentId = base, number = 1, title = "Смотреть"))
        val eps = seriesEpisodes(base)
        if (eps.isEmpty()) return listOf(Segment(id = "$base#1", contentId = base, number = 1, title = "Смотреть"))
        return eps.mapIndexed { i, ep ->
            val t = "С${ep.season} · Серия ${ep.episode}" + if (ep.name.isNotBlank()) " · ${ep.name}" else ""
            Segment(
                id = "$base#${i + 1}", contentId = base, number = i + 1, title = t,
                releaseDate = ep.releaseDate, playable = ep.dubs.isNotEmpty(),
            )
        }
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        if (!owns(contentId)) return null
        val base = contentId.substringBefore(":t")
        val isTv = base.startsWith(TV_PREFIX)
        val tIndex = contentId.substringAfter(":t", "").toIntOrNull() ?: 0

        if (isTv) {
            // Series: play the chosen episode's chosen dub straight from cdnvideohub
            // (full season/episode tree, real OK.ru streams).
            val eps = seriesEpisodes(base)
            if (eps.isNotEmpty()) {
                val ep = eps.getOrNull(segment - 1) ?: eps.first()
                if (ep.dubs.isNotEmpty()) {
                    val dubIdx = tIndex.coerceIn(0, ep.dubs.lastIndex)
                    val variants = fetchVideoVariants(ep.dubs[dubIdx].second)
                    if (variants.isNotEmpty()) {
                    val translations = ep.dubs.mapIndexed { i, d -> Translation(i, d.first) }
                    return ContentResult(
                        location = variants.first().url,
                        quality = variants.first().quality,
                        source = "Lampa/cdnvideohub",
                        referer = CVH_REF,
                        variants = variants,
                        translations = translations.takeIf { it.size > 1 },
                        translationId = dubIdx,
                    )
                    }
                }
                val movie = tvOrMovieMap(base.removePrefix(TV_PREFIX), isTv = true) ?: return null
                val stream = runCatching {
                    lampa.resolveEpisode(
                        pluginUrl(), balancer(), movie,
                        season = ep.season,
                        episode = ep.episode,
                    )
                }.getOrNull() ?: return null
                val variants = stream.qualities.map { StreamVariant(it.first, it.second) }
                    .ifEmpty { listOf(StreamVariant("Auto", stream.url)) }
                return ContentResult(
                    location = variants.first().url,
                    quality = variants.first().quality,
                    source = "Lampa/${balancer()}",
                    variants = variants,
                )
            }
            return null
        }

        val tmdbId = base.removePrefix(if (isTv) TV_PREFIX else ID_PREFIX)
        val movie = tvOrMovieMap(tmdbId, isTv = false) ?: return null
        val streams = runCatching { lampa.resolve(pluginUrl(), balancer(), movie) }.getOrDefault(emptyList())
        if (streams.isEmpty()) return null
        val idx = tIndex.coerceIn(0, streams.lastIndex)
        val chosen = streams[idx]
        val translations = streams.mapIndexed { i, st -> Translation(i, st.title.ifBlank { "Озвучка ${i + 1}" }) }
        val variants = chosen.qualities.map { StreamVariant(it.first, it.second) }
            .ifEmpty { listOf(StreamVariant("Auto", chosen.url)) }
        return ContentResult(
            location = variants.first().url,
            quality = variants.first().quality,
            source = "Lampa/${balancer()}",
            referer = null,
            variants = variants,
            translations = translations.takeIf { it.size > 1 },
            translationId = idx,
        )
    }

    /** Build the movie/tv map (imdb + titles) that the plugin balancer searches on. */
    private suspend fun tvOrMovieMap(tmdbId: String, isTv: Boolean): Map<String, Any?>? {
        return mediaCache.get("${if (isTv) "tv" else "movie"}:$tmdbId") { loadMediaMap(tmdbId, isTv) }
    }

    private suspend fun loadMediaMap(tmdbId: String, isTv: Boolean): Map<String, Any?>? {
        val kind = if (isTv) "tv" else "movie"
        val d = details(kind, tmdbId) ?: return null
        val title = d.title.ifBlank { d.name }
        val original = d.original_title.ifBlank { d.original_name }
        val imdbId = d.imdb_id?.takeIf(String::isNotBlank)
            ?: d.external_ids?.imdb_id.orEmpty()
        val kinopoiskId = d.external_ids?.kinopoisk_id?.takeIf(String::isNotBlank)
            ?: imdbId.takeIf(String::isNotBlank)?.let { withContext(Dispatchers.IO) { lampa.kinopoiskIdForImdb(it) } }
        return buildMap {
            // A TMDB id is not a KinoPoisk id. Passing it as a plain numeric id
            // makes KinoPoisk-based balancers return an unrelated title.
            put("id", kinopoiskId ?: imdbId.ifBlank { "tmdb-$tmdbId" })
            put("tmdb_id", tmdbId)
            put("imdb_id", imdbId)
            put("kinopoisk_id", kinopoiskId.orEmpty())
            put("title", title); put("name", title)
            put("original_title", original); put("original_name", original)
            put("release_date", d.release_date.ifBlank { d.first_air_date })
            put("first_air_date", d.first_air_date)
            if (isTv) { put("number_of_seasons", d.number_of_seasons); put("seasons", d.number_of_seasons) }
        }
    }

    /** Resolve (once) and parse a series' full season/episode/dub tree via cdnvideohub. */
    private suspend fun seriesEpisodes(base: String): List<SeriesEpisode> {
        seriesCache[base]?.takeIf { System.currentTimeMillis() - it.fetchedAt < SERIES_TTL_MS }
            ?.let { return it.episodes }
        val tmdbId = base.removePrefix(TV_PREFIX)
        val details = tvDetails(tmdbId)
        val movie = tvOrMovieMap(tmdbId, isTv = true) ?: return emptyList()
        val raw = lampa.seriesPlaylist(pluginUrl(), balancer(), movie)
        val pl = raw?.let { runCatching { json.decodeFromString<CvhPlaylist>(it) }.getOrNull() }
        val playable = pl?.items.orEmpty().filter { it.vkId.isNotBlank() }
            .groupBy { it.season to it.episode }
            .map { (key, items) ->
                val (s, e) = key
                val name = items.firstNotNullOfOrNull { it.name.takeIf { n -> n.isNotBlank() } }.orEmpty()
                val dubs = items.map {
                    val label = listOf(it.voiceStudio, it.voiceType).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Озвучка" }
                    label to it.vkId
                }
                SeriesEpisode(s, e, name, dubs, "")
            }
        val tmdbEpisodes = details?.seasons.orEmpty().filter { it.season_number > 0 }
            .flatMap { season -> seasonDetails(tmdbId, season.season_number)?.episodes.orEmpty() }
        val today = LocalDate.now()
        val metadata = tmdbEpisodes.associateBy { it.season_number to it.episode_number }
        val merged = LinkedHashMap<Pair<Int, Int>, SeriesEpisode>()
        tmdbEpisodes.filter { isReleasedEpisode(it.air_date, today) }.forEach { episode ->
            merged[episode.season_number to episode.episode_number] = SeriesEpisode(
                episode.season_number, episode.episode_number, episode.name, emptyList(), episode.air_date,
            )
        }
        playable.forEach { episode ->
            val key = episode.season to episode.episode
            val info = metadata[key]
            if (info == null || isReleasedEpisode(info.air_date, today)) {
                val existing = merged[key]
                merged[key] = episode.copy(
                    name = episode.name.ifBlank { existing?.name.orEmpty() },
                    releaseDate = info?.air_date.orEmpty(),
                )
            }
        }
        val eps = merged.values.sortedWith(compareBy({ it.season }, { it.episode }))
        if (eps.isNotEmpty()) seriesCache[base] = CachedSeries(eps, System.currentTimeMillis())
        return eps
    }

    private suspend fun tvDetails(tmdbId: String): TmdbDetails? {
        return details("tv", tmdbId)
    }

    private suspend fun seasonDetails(tmdbId: String, season: Int): TmdbSeason? {
        val raw = get("$API/tv/$tmdbId/season/$season?$KEY&language=ru-RU") ?: return null
        return runCatching { json.decodeFromString<TmdbSeason>(raw) }.getOrNull()
    }

    override suspend fun nextEpisodeSchedule(contentId: String): EpisodeSchedule? {
        val base = contentId.substringBefore(":t")
        if (!base.startsWith(TV_PREFIX)) return null
        val next = tvDetails(base.removePrefix(TV_PREFIX))?.next_episode_to_air ?: return null
        if (next.air_date.isBlank()) return null
        return EpisodeSchedule(next.air_date, next.season_number, next.episode_number)
    }

    /** OK.ru progressive MP4 ladder for a cdnvideohub video id (best quality first). */
    private suspend fun fetchVideoVariants(vkId: String): List<StreamVariant> {
        val raw = http.getHtml("$CVH_API/player/sv/video/$vkId?pub=$CVH_PUB", referer = CVH_REF, headers = mapOf("Origin" to CVH_ORIGIN)) ?: return emptyList()
        val src = runCatching { json.decodeFromString<CvhVideo>(raw) }.getOrNull()?.sources ?: return emptyList()
        val variants = listOf(
            "2160p" to src.mpeg4kUrl, "1440p" to src.mpeg2kUrl, "1080p" to src.mpegFullHdUrl,
            "720p" to src.mpegHighUrl, "480p" to src.mpegMediumUrl, "360p" to src.mpegLowUrl, "240p" to src.mpegLowestUrl,
        ).filter { it.second.isNotBlank() }.map { StreamVariant(it.first, it.second) }
        if (variants.isEmpty()) return emptyList()
        // Ссылки ПРОВЕРЯЕМ, прежде чем отдать плееру.
        //
        // cdnvideohub регулярно возвращает набор, который OK.ru отвергает целиком
        // (проверено: HTTP 400 на все пять качеств сразу, у таких ссылок нет
        // параметра `id`). Плеер об этом узнавал только на воспроизведении — и
        // зритель видел вечное «Загрузка… 0 %». Пустой ответ здесь означает
        // «этот путь мёртв», и вызывающий код уходит на балансер плагина, чей
        // набор ссылок рабочий.
        if (!alive(variants.first().url)) {
            Timber.w("[Lampa] cdnvideohub links rejected for %s — falling back to the plugin balancer", vkId)
            return emptyList()
        }
        return variants
    }

    /** Отдаёт ли сервер хоть один байт. Диапазон, а не HEAD: OK.ru на HEAD отвечает не так, как на реальное чтение. */
    private suspend fun alive(url: String): Boolean {
        val response = http.getText(url, referer = CVH_REF, headers = mapOf("Range" to "bytes=0-1")) ?: return false
        return response.code in 200..299 || response.code == 206
    }

    override suspend fun validateSource(contentId: String): Boolean = true
    override suspend fun balancerEmbedFor(pageUrl: String): String? = null

    private fun parse(raw: String?, tv: Boolean, excludeJapanese: Boolean = false): List<Anime> {
        raw ?: return emptyList()
        val resp = runCatching { json.decodeFromString<TmdbResp>(raw) }.getOrNull() ?: return emptyList()
        val prefix = if (tv) TV_PREFIX else ID_PREFIX
        return resp.results.mapNotNull { m ->
            // Отсев аниме из «Мультфильмов» — ЗДЕСЬ, а не в запросе.
            //
            // В адресе годами стояло without_original_language=ja, и это ничего не
            // делало: у TMDB такого параметра в /discover просто нет, он его молча
            // игнорирует. ЗАМЕРЕНО: с ним и без него ответ побайтово тот же — 475
            // тайтлов, из первых двадцати ровно десять японских. Так «Мультфильмы»
            // наполовину состояли из аниме, у которого в приложении своя сторона.
            if (excludeJapanese && m.original_language == "ja") return@mapNotNull null
            val title = m.title.ifBlank { m.name }.ifBlank { return@mapNotNull null }
            val year = (m.release_date.ifBlank { m.first_air_date }).take(4).toIntOrNull() ?: 0
            val poster = m.poster_path?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty()
            Anime(
                id = "$prefix${m.id}", title = title, poster = poster, year = year,
                description = m.overview.trim(),
                // TMDB считает по десятибалльной шкале, и vote_count — это ровно та
                // аудитория, по которой ранг отличает «все смотрели» от «полтора зрителя».
                rating = m.vote_average, ratingMax = 10.0, ratingVotes = m.vote_count,
                contentType = if (tv) "Сериал" else "Фильм",
            )
        }.distinctBy { it.id }
    }


    private fun parseReviews(raw: String?): List<com.aniblaze.aggregator.model.TitleComment> {
        val reviews = raw?.let { runCatching { json.decodeFromString<TmdbReviews>(it) }.getOrNull() } ?: return emptyList()
        return reviews.results.mapNotNull { review ->
            review.content.trim().takeIf { it.isNotBlank() }?.let { message ->
                com.aniblaze.aggregator.model.TitleComment(
                    id = review.id.hashCode().toLong() and 0xffffffffL,
                    author = review.author.ifBlank { "Пользователь TMDB" },
                    avatar = review.author_details.avatar_path.orEmpty().let { path ->
                        if (path.startsWith("http")) path else path.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w92$it" }.orEmpty()
                    },
                    message = message,
                    timestamp = runCatching { java.time.OffsetDateTime.parse(review.created_at).toEpochSecond() }.getOrDefault(0L),
                    votes = review.author_details.rating?.toInt() ?: 0,
                )
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Parsed season/episode/dub tree per series id — filled on first getContentSegments,
    // reused by extractContent so we don't re-run the plugin per episode.
    // Series trees expire: cached forever, a running app never picked up a new episode
    // (or a whole new season — "Rick and Morty stuck on 8 seasons") until restart.
    private class CachedSeries(val episodes: List<SeriesEpisode>, val fetchedAt: Long)
    private val seriesCache = java.util.concurrent.ConcurrentHashMap<String, CachedSeries>()

    /** One episode of a series: which season/episode, its title, and its dubs (label → cdnvideohub vkId). */
    private data class SeriesEpisode(
        val season: Int,
        val episode: Int,
        val name: String,
        val dubs: List<Pair<String, String>>,
        val releaseDate: String,
    )

    private companion object {
        /** TMDB genre id for animation — the one the cartoons tab is locked to. */
        const val ANIMATION_GENRE = 16

        /** Год выбирается диапазонами: отдельная кнопка на каждый год из ста — это
         *  ровно то «перегруженное» меню, которого просили избежать. */
        val DECADES = listOf(
            2026..2026, 2025..2025, 2024..2024, 2023..2023, 2022..2022, 2021..2021,
            2020..2020, 2015..2019, 2010..2014, 2000..2009, 1990..1999, 1900..1989,
        )

        val COUNTRY_CODES = mapOf(
            "США" to "US", "Россия" to "RU", "Великобритания" to "GB", "Франция" to "FR",
            "Германия" to "DE", "Испания" to "ES", "Италия" to "IT", "Канада" to "CA",
            "Япония" to "JP", "Южная Корея" to "KR", "Китай" to "CN", "Индия" to "IN",
        )
        val CINEMA_COUNTRIES = COUNTRY_CODES.keys.toList()
        val CARTOON_COUNTRIES = CINEMA_COUNTRIES - "Япония"

        const val ID_PREFIX = "tmdb:"
        // NB: no ":t" inside — that sequence is the translation-index delimiter
        // (":t0"/":t1") and would corrupt substringBefore(":t") on a series id.
        const val TV_PREFIX = "tmdbtv:"
        const val API = "https://api.themoviedb.org/3"
        // Lampa's public TMDB key (read-only catalog access).
        const val KEY = "api_key=4ef0d7355d9ffb5151e987764708ce96"
        const val REF = "https://www.themoviedb.org/"
        // cdnvideohub (Lumex) — the plugin fetches the season playlist; we fetch each
        // episode's video directly (that endpoint accepts our request; the playlist doesn't).
        const val CVH_API = "https://plapi.cdnvideohub.com/api/v1"
        const val CVH_REF = "https://player.cdnvideohub.com/"
        const val CVH_ORIGIN = "https://player.cdnvideohub.com"
        const val CVH_PUB = "12"

        /** How long a parsed season/episode tree stays valid. Short enough that a new
         *  episode (or season) shows up without restarting the app. */
        const val SERIES_TTL_MS = 20 * 60 * 1000L
    }
}

@Serializable
private data class TmdbResp(
    val results: List<TmdbMovie> = emptyList(),
    val page: Int = 1,
    val total_pages: Int = 0,
    /** Точное число найденного — то, что показывается над лентой под фильтром. */
    val total_results: Int = 0,
)

@Serializable
private data class TmdbMovie(
    val id: Int = 0,
    val title: String = "",
    val name: String = "",
    val poster_path: String? = null,
    val overview: String = "",
    val vote_average: Double = 0.0,
    val vote_count: Int = 0,
    /** Язык оригинала. "ja" = аниме, а не мультфильм (см. parse). */
    val original_language: String = "",
    val release_date: String = "",
    val first_air_date: String = "",
)

@Serializable
private data class TmdbDetails(
    val id: Int = 0,
    val title: String = "",
    val name: String = "",
    val original_title: String = "",
    val original_name: String = "",
    val overview: String = "",
    val release_date: String = "",
    val first_air_date: String = "",
    val number_of_seasons: Int = 0,
    val imdb_id: String? = null,
    val external_ids: TmdbExternal? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    val next_episode_to_air: TmdbNextEpisode? = null,
)

@Serializable
private data class TmdbReviews(val results: List<TmdbReview> = emptyList())

@Serializable
private data class TmdbReview(
    val id: String = "",
    val author: String = "",
    val content: String = "",
    val created_at: String = "",
    val author_details: TmdbReviewAuthor = TmdbReviewAuthor(),
)

@Serializable
private data class TmdbReviewAuthor(val avatar_path: String? = null, val rating: Double? = null)

@Serializable
private data class TmdbSeasonSummary(
    val season_number: Int = 0,
    val episode_count: Int = 0,
    val air_date: String? = null,
)

@Serializable
private data class TmdbNextEpisode(
    val air_date: String = "",
    val season_number: Int = 0,
    val episode_number: Int = 0,
)

@Serializable
private data class TmdbExternal(val imdb_id: String? = null, val kinopoisk_id: String? = null)

@Serializable
private data class TmdbSeason(val episodes: List<TmdbEpisode> = emptyList())

@Serializable
private data class TmdbEpisode(
    val name: String = "",
    val season_number: Int = 0,
    val episode_number: Int = 0,
    val air_date: String = "",
)

// cdnvideohub series playlist: one item per (season, episode, dub).
@Serializable
private data class CvhPlaylist(val titleName: String = "", val isSerial: Boolean = false, val items: List<CvhItem> = emptyList())

@Serializable
private data class CvhItem(
    val vkId: String = "",
    val voiceType: String = "",
    val voiceStudio: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val name: String = "",
)

@Serializable
private data class CvhVideo(val sources: CvhSources? = null)

@Serializable
private data class CvhSources(
    val hlsUrl: String = "",
    val mpeg4kUrl: String = "",
    val mpeg2kUrl: String = "",
    val mpegQhdUrl: String = "",
    val mpegFullHdUrl: String = "",
    val mpegHighUrl: String = "",
    val mpegMediumUrl: String = "",
    val mpegLowUrl: String = "",
    val mpegLowestUrl: String = "",
)

private fun isReleasedEpisode(airDate: String, today: LocalDate): Boolean {
    val date = runCatching { LocalDate.parse(airDate) }.getOrNull() ?: return false
    return !date.isAfter(today)
}

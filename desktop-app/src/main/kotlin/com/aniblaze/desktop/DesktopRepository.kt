package com.aniblaze.desktop

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.AgeRating
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogPage
import com.aniblaze.aggregator.model.CatalogSort
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.aggregator.model.CommentBatch
import com.aniblaze.aggregator.model.ContentType
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.EpisodeRange
import com.aniblaze.aggregator.model.FilterFacets
import com.aniblaze.aggregator.model.TitleStatus
import com.aniblaze.aggregator.model.EpisodeSchedule
import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.model.PersonCredit
import com.aniblaze.aggregator.model.PersonDetails
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StudioCredit
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.TitleCredits
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.aggregator.source.CinemaSource
import com.aniblaze.aggregator.source.StudioTitlePage
import com.aniblaze.aggregator.lampa.LampaExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import com.aniblaze.aggregator.source.AnixartSource
import com.aniblaze.aggregator.source.CommentStop
import com.aniblaze.aggregator.source.commentStop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * Desktop counterpart of the mobile app's AnimeRepository + ContentResolver,
 * minus the Room cache layer (favorites/history persist via [AppSettings]
 * instead). Anime sources are merged the same way the mobile resolver does:
 * queried concurrently, first reachable stream wins, in priority order.
 */
class DesktopRepository(
    private val aggregators: List<ContentAggregator>,
    private val cinemaSources: List<CinemaSource>,
    private val settings: AppSettings,
    private val lampa: LampaExtractor? = null,
    // Community opening timings for the titles AniLiberty doesn't cover.
    private val aniskip: com.aniblaze.aggregator.source.AniskipTimings,
    // Alternative playback path: the balancer's own CDN stream (see BalancerSource).
    private val balancer: com.aniblaze.aggregator.source.BalancerSource,
    // Per-episode air dates for the episode list (no playback source carries them).
    private val airDates: com.aniblaze.aggregator.source.EpisodeAirDates,
    // Проверка ссылок перед выдачей плееру (см. resolveStream). Необязателен: без
    // него всё работает как раньше, только без проверки.
    private val http: com.aniblaze.network.HttpClient? = null,
    /** Override only for isolated tests; production uses %APPDATA%/AniBlaze. */
    commentCacheDirectory: java.io.File? = null,
) {
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val metadataSource: com.aniblaze.aggregator.source.ShikimoriSource?
        get() = aggregators.filterIsInstance<com.aniblaze.aggregator.source.ShikimoriSource>().firstOrNull()
    private data class PlayableMetadata(val anime: Anime?)
    private val playableMetadataCache = java.util.concurrent.ConcurrentHashMap<String, PlayableMetadata>()
    private val semanticRecommendations by lazy {
        val directory = settings.dataDirectory
        val key = OpenRouterCredential.read(directory)
        SemanticRecommendationStore(directory,
            extractor = key?.let { OpenRouterSemantics({ it }) },
            loadMetadata = { anime ->
                // Avoid fullDetails' unrelated MAL/vote lookup for semantic extraction.
                val source = aggregators.filterIsInstance<AnixartSource>().firstOrNull()
                val id = source?.let { anixartIdFor(anime) }
                if (source != null && id != null) source.details(id)?.copy(id = anime.id) else null
            })
    }

    internal suspend fun rankRecommendations(saved: PersistedState, pool: List<Anime>, online: Boolean):
        SemanticRecommendationStore.Result = withContext(Dispatchers.IO) {
        try { semanticRecommendations.rank(saved, pool, online) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // Corrupt/unwritable cache or service failure must never break the catalog.
            val taste = Recommender.buildTaste(saved)
            SemanticRecommendationStore.Result(taste, HybridRecommender.recommend(taste, pool, limit = pool.size + 1))
        }
    }
    private val commentStore = commentCacheDirectory?.let { SeasonCommentStore(it) } ?: SeasonCommentStore()
    private val releaseTracker = EpisodeReleaseTracker(java.io.File(
        commentCacheDirectory ?: settings.dataDirectory, "episode-releases.json"))
    val episodeReleaseRevision get() = releaseTracker.revision
    private val recentCalendar = RecentEpisodeCalendar(http)
    fun recentEpisodeTitles(): List<Anime> = releaseTracker.recent()
    suspend fun observeAvailableEpisodes(anime: Anime, segments: List<Segment>) = withContext(Dispatchers.IO) {
        if (!isCinema(anime.id)) {
            val count = segments.filter { it.playable }.maxOfOrNull { it.number } ?: 0
            releaseTracker.observe(listOf(anime.copy(episodesAvailable = count)))
        }
    }

    private class FeedCache {
        val lock = Mutex()
        var at = 0L
        var items: List<Anime> = emptyList()
    }
    private val feedCache = java.util.concurrent.ConcurrentHashMap<String, FeedCache>()
    private suspend fun cachedFeed(key: String, ttl: Long = 5 * 60_000L, load: suspend () -> List<Anime>): List<Anime> {
        val sourceKey = active().joinToString { it.name }
        val entry = feedCache.computeIfAbsent("$sourceKey|$key") { FeedCache() }
        return entry.lock.withLock {
            val now = System.currentTimeMillis()
            if (entry.at > 0 && now - entry.at < ttl) return@withLock entry.items
            val loaded = load()
            entry.items = loaded
            entry.at = now
            if (feedCache.size > 40) {
                feedCache.entries.filter { it.value !== entry && !it.value.lock.isLocked }
                    .sortedBy { it.value.at }.take(feedCache.size - 40).forEach { feedCache.remove(it.key, it.value) }
            }
            loaded
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    /** All selectable cinema sources (for the Settings switcher). */
    fun cinemaSources(): List<CinemaSource> = cinemaSources

    /** The source the user selected in Settings (falls back to the first). */
    private fun activeCinema(): CinemaSource =
        cinemaSources.firstOrNull { it.key == settings.state.value.cinemaSource } ?: cinemaSources.first()

    /**
     * The TMDB-backed cinema source. Two things live ONLY there: the `cartoon:`
     * categories (cartoons are a TMDB genre query — the scraper sites have nothing
     * comparable), and the "special rows" below. The cartoons tab used to follow the
     * Settings cinema source, so on the default (lordfilm) its category list filtered
     * down to NOTHING and the whole tab came up empty; it now always resolves here.
     */
    private fun tmdbCinema(): CinemaSource =
        cinemaSources.firstOrNull { src -> src.categories.any { it.first.startsWith(CARTOON_PREFIX) } }
            ?: activeCinema()

    private fun cinemaFor(cartoons: Boolean): CinemaSource = if (cartoons) tmdbCinema() else activeCinema()

    /**
     * The «Кино» landing's live rows — the same shape as the anime home: сейчас
     * смотрят / вышли новые серии / в тренде / последние поступления. Always served
     * by the TMDB source (the site scrapers have no such feeds), whatever «Кино»
     * source is chosen in Settings; the `tmdb!` prefix carries that routing through
     * every browse/paginate call.
     */
    fun cinemaSpecialRows(): List<Pair<String, String>> = listOf(
        "${TMDB_ROW}trending" to "Сейчас смотрят",
        "${TMDB_ROW}new_episodes" to "Вышли новые серии",
        "${TMDB_ROW}hot" to "В тренде",
        "${TMDB_ROW}latest" to "Последние поступления",
    )

    /** Category tabs of the active cinema source. */
    fun cinemaCategories(cartoons: Boolean = false): List<Pair<String, String>> = cinemaFor(cartoons).categories

    /** Whether the active cinema source paginates (drives infinite scroll). */
    fun cinemaPaginates(cartoons: Boolean = false): Boolean = cinemaFor(cartoons).paginates

    /** Whether the active cinema source supports rich server-side filters. */
    fun cinemaSupportsFilters(cartoons: Boolean = false): Boolean = cinemaFor(cartoons).supportsServerFilters

    /** Genres the active cinema source can filter by (`id to label`). */
    fun cinemaGenres(cartoons: Boolean = false): List<Pair<String, String>> = cinemaFor(cartoons).genres

    /** Cinema id → the source that owns it (so old favourites/history from a
     *  different source still resolve), falling back to the active source. */
    private fun cinemaSourceFor(contentId: String): CinemaSource =
        cinemaSources.firstOrNull { it.owns(contentId) } ?: activeCinema()
    private fun active(): List<ContentAggregator> {
        // A chosen primary source overrides everything: the whole app runs on it.
        val primary = settings.state.value.primarySource
        if (primary.isNotBlank() && primary != ALL_SOURCES) {
            aggregators.firstOrNull { it.name == primary }?.let { return listOf(it) }
        }
        val enabled = settings.state.value.enabledSources
        return aggregators.filter { it.name in enabled }.ifEmpty { aggregators }
    }

    /** Options for the global source picker: "Все" (merge) + each source by name. */
    fun animeSourceOptions(): List<String> = listOf(ALL_SOURCES) + aggregators.map { it.name }

    companion object {
        const val ALL_SOURCES = "Все"
        private const val CARTOON_PREFIX = "cartoon:"

        /** Browse-path prefix that forces the TMDB source (see [cinemaSpecialRows]). */
        private const val TMDB_ROW = "tmdb!"

        /** Catalog/metadata sources that never resolve a playable stream. */
        private val METADATA_ONLY_SOURCES = setOf("Shikimori")

        /** AniLiberty только УТОЧНЯЕТ опенинг — ждать её дольше нет смысла. */
        private const val LIBRIA_TIMING_TIMEOUT_MS = 6_000L

        /** Сколько вариантов качества щупать, прежде чем признать источник мёртвым. */
        private const val MAX_PROBED_VARIANTS = 4

        /** Сколько тайтлов держать в кэше обсуждений (см. [commentCache]). */
        private const val COMMENT_CACHE_TITLES = 12

        /** Общий потолок записей: двенадцать больших тайтлов занимали сотни МБ. */
        private const val COMMENT_CACHE_ITEMS = 20_000

        /** Сколько веток ответов подгружать на сезон и сколько ответов брать из ветки. */
        private const val REPLY_THREADS_PER_SEASON = 40
        private const val REPLIES_PER_THREAD = 25

        /** Сколько страниц обсуждения Yummy подмешивать: голова по лайкам + свежие. */
        private const val YUMMY_TOP_PAGES = 2
        private const val YUMMY_FRESH_PAGES = 6

        /** Снимки растут 25, 50, 100… вместо полного копирования на каждой странице. */
        private const val COMMENT_FIRST_EMIT = 25

        /**
         * Сколько страниц «сначала лучшие» брать перед полным обходом.
         *
         * Шесть — это полтораста записей, и у «Наруто» 141 из них без пометки серии
         * (замерено 23.08), то есть годна любой серии. Глубже лезть этой сортировкой
         * нельзя: её порядок неоднозначен и на глубине даёт до четверти повторов.
         */
        private const val COMMENT_TOP_PAGES = 6

        /**
         * Пауза между страницами обсуждения.
         *
         * Обход шёл вплотную — семь запросов в секунду к тому же хосту, с которого в
         * этот момент поднимается поток (видно в журнале 23.08: тридцать шесть страниц
         * за восемь секунд поверх старта серии). Обсуждение — это фон, ему спешить
         * некуда, а хост один на всё приложение.
         */
        private const val COMMENT_PAGE_GAP_MS = 250L

        /** Коды, означающие «файла нет» — и только они. Замерено на протухших
         *  ссылках solodcdn: ровно 404. */
        private val DEAD_STREAM_CODES = setOf(404, 410)

        /** Сколько страниц категории просеять, прежде чем признать «больше нет».
         *  Страница Anixart — 25 карточек, так что это до 150 проверенных тайтлов. */
        private const val SECTION_FILTER_MAX_PAGES = 6

        /** Набрали столько подходящих — хватит, экран уже заполнен. */
        private const val SECTION_FILTER_TARGET = 24

        /** Сколько жанров тайтла превращается в запросы пула «Похожих». */
        private const val SIMILAR_POOL_TAGS = 2

        /** По столько страниц на каждый жанровый запрос (страница Anixart — 25 карточек). */
        private const val SIMILAR_POOL_PAGES = 2

        /** Глубина пула рекомендаций по каждой ленте. */
        private const val RECOMMEND_POOL_PAGES = 4

        /** Сколько любимых жанров зрителя спрашиваем отдельными запросами. */
        private const val RECOMMEND_POOL_GENRES = 4

        /** Одновременных обращений к источникам при наборе пула. См. HomeScreen.loadSections. */
        private const val RECOMMEND_POOL_PARALLEL = 4

        /** Годы для фильтра аниме — диапазонами, чтобы список не превратился в сотню кнопок. */
        private val ANIME_YEARS = listOf(
            2026..2026, 2025..2025, 2024..2024, 2023..2023, 2022..2022, 2021..2021,
            2020..2020, 2015..2019, 2010..2014, 2000..2009, 1990..1999, 1900..1989,
        )
    }

    suspend fun trending(): List<Anime> = mergeAll { it.trending() }

    /** Merged search, collapsed by normalized title+year: the same anime from two
     *  sources (Anixart + AniLibria) showed as two cards. Source order favours
     *  Anixart, whose card (many dubs, stats) is the more useful one to keep. */
    /**
     * Поиск: сначала спрашиваем источники, потом наводим порядок сами.
     *
     * ПОЧЕМУ НЕДОСТАТОЧНО ОТДАТЬ ЗАПРОС ИСТОЧНИКАМ. Они ищут подстрокой по своему
     * русскому названию — и на этом всё: опечатка «наруот», забытая раскладка
     * «Yfhenj», английское «Bleach» или запрос вроде «исекай без романтики» не
     * находят ничего. Разбор и ранжирование живут в [SmartSearch] отдельно от сети и
     * потому проверяются тестами без единого запроса.
     *
     * Порядок шагов. Сначала запрос разбирается: если в нём есть «как X» или
     * «без Y», это описание желаемого, а не название. Из такого запроса в сеть уходит
     * НЕ он сам (источники по нему ничего не найдут), а образец «X» и фильтр по
     * тегам; результат ранжируется по замыслу. Обычный запрос уходит как есть, но
     * ответ пересортировывается: точное совпадение выше вхождения, вхождение выше
     * исправленной опечатки.
     */
    suspend fun search(query: String): List<Anime> {
        val intent = parseNaturalQuery(query)
        // Описательный запрос: имя тайтла-образца и/или условия по тегам.
        if (!intent.isEmpty) return searchByIntent(query, intent)
        // mergeAll уже схлопывает дубли между источниками (dedupeTitles); отдельный
        // ключ "название#год" здесь ломался, когда у одного источника год = 0.
        val raw = mergeAll { it.search(query) }
        // Раскладка проверяется ВТОРЫМ запросом, а не вместо первого: «Yfhenj» это
        // «Наруто», но у источников есть и настоящие латинские названия, и терять их
        // ради догадки нельзя.
        val flipped = listOfNotNull(toRussianLayout(query), toLatinLayout(query))
        var pool = if (flipped.isNotEmpty() && raw.size < LAYOUT_RETRY_BELOW) {
            val extra = flipped.flatMap { variant -> mergeAll { it.search(variant) } }
            (raw + extra).distinctBy { it.id }
        } else {
            raw
        }
        // Опечатка: выдача всё ещё бедная — подбираем ближайшее из известных названий
        // (история, избранное, уже загруженные ленты) и спрашиваем источники ИМ.
        // Исправленный запрос запоминается: строка поиска покажет «Возможно: …».
        lastTypoFix = null
        if (pool.size < LAYOUT_RETRY_BELOW) {
            val corrected = TypoSearch.closest(query, knownTitleNames()).firstOrNull()
            if (corrected != null && !corrected.equals(query, ignoreCase = true)) {
                val extra = attempt { mergeAll { it.search(corrected) } }.orEmpty()
                if (extra.isNotEmpty()) {
                    lastTypoFix = corrected
                    pool = (pool + extra).distinctBy { it.id }
                    com.aniblaze.desktop.player.PlayerDiagnostics.log("search.typo", "query=$query; corrected=$corrected; extra=${extra.size}")
                }
            }
        }
        return searchTitles(lastTypoFix ?: query, pool).map { it.anime }
    }

    /** Чем заменили последний запрос из-за опечатки; null — ничем. Читает строка поиска. */
    @Volatile var lastTypoFix: String? = null
        private set

    /** Названия, которые приложение уже видело: история, избранное и кэш лент. */
    private fun knownTitleNames(): Set<String> = buildSet {
        settings.state.value.history.forEach { add(it.title) }
        settings.state.value.favorites.forEach { add(it.title) }
        feedCache.values.forEach { cache -> cache.items.forEach { add(it.title) } }
    }

    /**
     * Запрос-описание: «аниме как Overlord, но без гарема».
     *
     * Источникам такое отдавать бесполезно — они ищут подстрокой. Поэтому в сеть
     * уходит только то, что там вообще можно спросить: название образца и фильтр по
     * тегам ([SearchIntent.toCatalogFilter]). Исключения и настроение сервер выразить
     * не может, их применяет ранжирование уже к полученному списку.
     */
    private suspend fun searchByIntent(query: String, intent: SearchIntent): List<Anime> {
        val sample = intent.similarTo?.let { name ->
            searchTitles(name, mergeAll { it.search(name) }, limit = 1).firstOrNull()?.anime
        }
        val filter = intent.toCatalogFilter()
        val candidates = LinkedHashMap<String, Anime>()
        // Каталог по тегам — основной источник кандидатов для описательного запроса.
        runCatching {
            repeat(INTENT_PAGES) { page ->
                animeFilterPage(filter, sectionKey = null, page = page).forEach { candidates[it.id] = it }
            }
        }
        // Образец задаёт направление: его собственные похожие — лучшие кандидаты.
        sample?.let { seed ->
            runCatching { similarTitles(seed, emptyList()).forEach { candidates[it.id] = it } }
        }
        // Совсем пусто (теги не распознались, каталог не ответил) — не оставляем
        // человека с пустым экраном: свободный текст ищем как обычное название.
        if (candidates.isEmpty()) {
            val fallback = intent.freeText.ifBlank { query }
            mergeAll { it.search(fallback) }.forEach { candidates[it.id] = it }
        }
        // Сам образец в выдаче не нужен: его и так знают, спрашивали «похожее».
        sample?.let { candidates.remove(it.id) }
        return rankByIntent(intent, candidates.values.toList(), similarTo = sample).map { it.anime }
    }

    /** Сколько страниц каталога тянуть под описательный запрос. */
    private val INTENT_PAGES = 3

    /** Ниже скольких находок стоит перепроверить раскладку. */
    private val LAYOUT_RETRY_BELOW = 3

    /** A named Home section — "trending" | "recent" | "popular" | "ongoing". */
    suspend fun catalog(category: String): List<Anime> = mergeAll { it.catalog(category) }

    suspend fun catalogPage(sort: Int, page: Int): List<Anime> = mergeAll { it.catalogPage(sort, page) }

    private fun curSeason() = ((java.time.LocalDate.now().monthValue - 1) / 3) + 1
    private fun curYear() = java.time.LocalDate.now().year

    /** One page of a Home section, keyed like the phone app (each key paginates on
     *  its OWN feed — so "Развернуть" loads more of THAT section, not a sort remap). */
    suspend fun homeSection(key: String, page: Int): List<Anime> {
        val raw = homeSectionRaw(key, page)
        // Ряд «Ожидаемые анонсы» — единственное место для невышедшего; в остальных
        // рядах анонсу делать нечего: постер есть, смотреть нечего. И то, что зритель
        // отметил «не понравилось», из лент уходит совсем.
        return if (key == "announce") raw.filter { passesDisliked(it) }
        else raw.filter { !com.aniblaze.aggregator.model.isUnreleased(it) && passesDisliked(it) }
    }

    private suspend fun homeSectionRaw(key: String, page: Int): List<Anime> = when {
        // Keep the durable change journal; add bounded broadcast evidence for cold starts.
        key == "newEpisodes" -> {
            if (page == 0) {
                // Discovery feeds only supply observations. Their update order is NOT
                // release evidence; the durable tracker alone decides what is fresh.
                val recent = attempt { homeSection("recent", 0) }.orEmpty()
                val recentMore = attempt { homeSection("recent", 1) }.orEmpty()
                val scheduled = attempt { homeSection("ongoing", 0) }.orEmpty()
                val airing = attempt { cachedFeed("release-ongoing") { mergeAll { it.ongoingPage(0) } } }.orEmpty()
                val calendar = recentCalendar.get()
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    releaseTracker.observe((recent + recentMore + scheduled + airing)
                        .map { withRecentEpisodeEvidence(it, calendar, now) }, now)
                }
                // Providers without a released-count field are checked via their
                // playable episode list, never via episodesTotal or title updatedAt.
                cachedFeed("episode-baselines", 5 * 60_000L) {
                    coroutineScope {
                        val gate = kotlinx.coroutines.sync.Semaphore(3)
                        recent.filter { it.episodesAvailable <= 0 }.take(24).map { anime ->
                            async { gate.withPermit { attempt { observeAvailableEpisodes(anime, segments(anime.id)) } } }
                        }.forEach { it.await() }
                    }
                    emptyList()
                }
            }
            recentEpisodeTitles().drop(page * 30).take(30)
        }
        key == "trending" -> catalogPage(3, page)
        key == "recent" -> cachedFeed("recent:$page") { mergeAll { it.latestReleases(page) } }
        key == "popular" -> catalogPage(4, page)
        key == "watching" -> mergeAll { it.watchingNow(curSeason(), curYear(), page) }
        key == "seasonal" -> mergeAll { it.seasonal(curSeason(), curYear(), page) }
        // page 0 = the curated schedule (today-first); later pages keep the row
        // scrolling via the paginated ongoing filter.
        key == "ongoing" -> cachedFeed("ongoing:$page") {
            if (page == 0) catalog("ongoing") else mergeAll { it.ongoingPage(page) }
        }
        key == "announce" -> if (page == 0) announces() else catalogPage(6, page + 3).filter { it.rating < 1.0 }
        key.startsWith("genre:") -> {
            val g = key.removePrefix("genre:")
            // Genres are filtered client-side out of the popular feed, so one page
            // yields few matches for less-common genres. Scan several pages for the
            // first load (fills the screen → the grid is scrollable → "load more" can
            // fire); later pages step one at a time.
            val pages = if (page == 0) (0..3).toList() else listOf(page + 3)
            // Как и в mergeAll: пусто из-за сбоя — это не «в жанре ничего нет».
            // Ни одна страница не далась — пусть ряд честно скажет «не удалось».
            var failure: Exception? = null
            val loaded = pages.flatMap { p ->
                try {
                    catalogPage(4, p)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure = error
                    emptyList()
                }
            }
            if (loaded.isEmpty()) failure?.let { throw it }
            val byGenre = loaded.filter { a -> a.genres.contains(g, ignoreCase = true) }.distinctBy { it.id }
            // У источников без жанров в ленте (YummyAnime) отсев даёт пусто — тогда
            // жанр спрашивается у них напрямую, через серверный фильтр.
            if (byGenre.isNotEmpty()) byGenre else genreViaFilter(g, page)
        }
        else -> emptyList()
    }

    /** «Скрыть просмотренное»: полностью досмотренные тайтлы не показываем. Только у нас —
     *  серверу история неизвестна; id перед проверкой приводится к известному. */
    internal fun passesWatched(filter: CatalogFilter, anime: Anime): Boolean =
        !filter.hideWatched || settings.watchOf(settings.canonical(anime).id)?.finished != true

    /** «Посмотрел, не понравилось» — такого в лентах и подборках больше не показываем. */
    /** Ни «не понравилось», ни «не показывать 30 дней» (срок не вышел). */
    internal fun passesDisliked(anime: Anime): Boolean {
        val id = settings.canonical(anime).id
        return !settings.isDisliked(id) && !settings.isSnoozed(id)
    }

    /**
     * Страница «по первоисточнику»: записи AniList → карточки Yummy.
     *
     * Карточка ищется по ромадзи/английскому названию, а принимается ТОЛЬКО если её
     * полная запись несёт тот же MAL id, что и запись AniList: первое похожее название
     * здесь не годится — у франшиз десятки похожих. Найденное кэшируется по MAL id.
     */
    private suspend fun anilistPage(
        sort: com.aniblaze.aggregator.model.CatalogSort,
        load: suspend (com.aniblaze.aggregator.source.AniListCatalog, String) -> List<com.aniblaze.aggregator.source.AniListCatalog.Entry>,
    ): List<Anime> = withContext(Dispatchers.IO) {
        val catalog = anilistCatalog ?: return@withContext emptyList()
        val yummy = aggregators.filterIsInstance<com.aniblaze.aggregator.source.YummyAnimeSource>().firstOrNull()
            ?: return@withContext emptyList()
        val anilistSort = when (sort) {
            com.aniblaze.aggregator.model.CatalogSort.RATING -> "SCORE_DESC"
            com.aniblaze.aggregator.model.CatalogSort.FRESH,
            com.aniblaze.aggregator.model.CatalogSort.RELEASE_DATE -> "START_DATE_DESC"
            else -> "POPULARITY_DESC"
        }
        val entries = attempt { load(catalog, anilistSort) }.orEmpty()
        if (entries.isEmpty()) return@withContext emptyList()
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        coroutineScope {
            entries.map { entry ->
                async {
                    gate.withPermit { cardForMal(entry, yummy) }
                }
            }.mapNotNull { it.await() }
        }.distinctBy { it.id }
    }

    private val malCardCache = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<Anime>>()

    private suspend fun cardForMal(
        entry: com.aniblaze.aggregator.source.AniListCatalog.Entry,
        yummy: com.aniblaze.aggregator.source.YummyAnimeSource,
    ): Anime? {
        malCardCache[entry.idMal]?.let { return it.orElse(null) }
        val queries = listOf(entry.romaji, entry.english).filter { it.isNotBlank() }.distinct()
        var found: Anime? = null
        for (q in queries) {
            val hits = attempt { yummy.search(q) }.orEmpty()
                .filter { entry.year == 0 || it.year == 0 || kotlin.math.abs(it.year - entry.year) <= 1 }
                .take(3)
            for (hit in hits) {
                val full = attempt { yummy.details(hit.id) } ?: continue
                if (full.malId == entry.idMal) { found = full; break }
            }
            if (found != null) break
        }
        malCardCache[entry.idMal] = java.util.Optional.ofNullable(found)
        return found
    }

    private val anilistCatalog: com.aniblaze.aggregator.source.AniListCatalog? by lazy {
        http?.let { com.aniblaze.aggregator.source.AniListCatalog(it) }
    }

    /** Жанровый ряд через `filterPage` тех источников, что умеют фильтр на сервере. */
    private suspend fun genreViaFilter(genre: String, page: Int): List<Anime> {
        val tag = CatalogTag.ANIME.firstOrNull { it.label.equals(genre, ignoreCase = true) } ?: return emptyList()
        val filter = com.aniblaze.aggregator.model.CatalogFilter(tags = setOf(tag.key))
        return mergeAll { src -> if (src.supportsFilter) src.filterPage(filter, page) else emptyList() }
    }

    // --- Фильтры каталога -------------------------------------------------------

    /**
     * Что умеет фильтровать сторона аниме.
     *
     * Список стран короткий не по лени: в выборке из 1800 карточек Anixart встретились
     * ровно Япония (1645), Китай (143) и Южная Корея — предлагать «Францию» значило бы
     * рисовать кнопку, которая всегда даёт пусто. Сравнение у API точное, поэтому в
     * списке стоит «Южная Корея», а не «Корея», — иначе фильтр не находил бы ничего.
     */
    fun animeFacets(): FilterFacets = FilterFacets(
        tags = CatalogTag.ANIME,
        sorts = CatalogSort.entries,
        years = ANIME_YEARS,
        statuses = TitleStatus.entries,
        ageRatings = AgeRating.entries,
        countries = listOf("Япония", "Китай", "Южная Корея"),
        episodes = EpisodeRange.entries,
        contentTypes = ContentType.entries,
        dubbings = com.aniblaze.aggregator.model.DubStudio.entries,
        sourceMaterials = com.aniblaze.aggregator.model.SourceMaterial.entries,
        hiddenGems = true,
        protagonists = com.aniblaze.aggregator.model.Protagonist.entries,
    )

    fun cinemaFacets(cartoons: Boolean): FilterFacets? = cinemaFor(cartoons).filterFacets(cartoons)

    /**
     * Страница аниме-каталога под фильтром.
     *
     * Два пути, и разница между ними принципиальная:
     *
     *  • [sectionKey] пуст — фильтр САМ является лентой, и его целиком исполняет
     *    источник (Anixart умеет). Тогда листается ровно то, что подошло.
     *  • [sectionKey] задан — открыта категория вроде «Сейчас смотрят», и её порядок
     *    важнее: она строится из расписания и никаким `/filter` не выражается. Тогда
     *    берём страницы САМОЙ КАТЕГОРИИ и просеиваем их на нашей стороне, дотягивая
     *    следующие, пока не наберётся видимый экран. Без этого выбор жанра внутри
     *    категории показывал бы две карточки и «пусто» — хотя ниже по ленте их сотни.
     *
     * Просеивание [CatalogFilter.matches] применяется в ОБОИХ случаях: под фильтром в
     * ленту подмешиваются источники, которые о нём не слышали (AniLibria, AnimeOn),
     * и без общей проверки они возвращали бы что попало.
     */
    suspend fun animeFilterPage(filter: CatalogFilter, sectionKey: String?, page: Int): List<Anime> {
        // Первоисточник: список отдаёт AniList, карточки — Yummy по MAL id.
        com.aniblaze.aggregator.model.SourceMaterial.byKey(filter.sourceMaterial)?.let { material ->
            val cards = anilistPage(filter.sort) { catalog, sort -> catalog.bySource(material.anilist, page, sort) }
            return cards.filter { filter.matches(it, CatalogTag::of) && passesWatched(filter, it) && passesDisliked(it) }
        }
        // Главный герой: отсев по тексту идёт в matches; у профилей с тегами AniList
        // страница дополняется их списком — его отсев по тексту НЕ режет (разметка
        // AniList точнее обрезанного описания), только остальные условия.
        com.aniblaze.aggregator.model.Protagonist.byKey(filter.protagonist)?.let { hero ->
            if (hero.anilistTags.isEmpty()) return@let
            val tagged = anilistPage(filter.sort) { catalog, sort -> catalog.byTags(hero.anilistTags, page, sort) }
            val rest = filter.copy(protagonist = "")
            val fromTags = tagged.filter { rest.matches(it, CatalogTag::of) && passesWatched(filter, it) && passesDisliked(it) }
            val scanned = if (sectionKey.isNullOrBlank()) {
                mergeFiltered(filter, page).filter { filter.matches(it, CatalogTag::of) && passesWatched(filter, it) && passesDisliked(it) }
            } else {
                emptyList()
            }
            val seen = fromTags.map { it.id }.toMutableSet()
            return sortClientSide(fromTags + scanned.filter { seen.add(it.id) }, filter.sort)
        }
        // Озвучка: список тайтлов студии отдаёт один источник целиком, категория и
        // остальные источники тут не при чём. Остальные условия и сортировка — по нему.
        com.aniblaze.aggregator.model.DubStudio.byKey(filter.dubbing)?.let { studio ->
            val yummy = aggregators.filterIsInstance<com.aniblaze.aggregator.source.YummyAnimeSource>().firstOrNull()
                ?: return emptyList()
            val all = attempt { yummy.dubbingCatalog(studio.yummyIds) }.orEmpty()
                .filter { filter.matches(it, CatalogTag::of) && passesWatched(filter, it) && passesDisliked(it) }
            val sorted = sortClientSide(all, filter.sort)
            return sorted.drop(page * DUBBING_PAGE).take(DUBBING_PAGE)
        }
        if (sectionKey.isNullOrBlank()) {
            val fromSources = mergeFiltered(filter, page)
            // Страница склеена из нескольких источников, каждый отсортировал СВОЁ;
            // без общего порядка «по рейтингу» открывался «ужасом» из другого источника.
            return sortClientSide(
                fromSources.filter { filter.matches(it, CatalogTag::of) && passesWatched(filter, it) && passesDisliked(it) },
                filter.sort,
            )
        }
        // Категория задаёт порядок — просеиваем её собственные страницы.
        val out = LinkedHashMap<String, Anime>()
        var scanned = 0
        var cursor = page
        while (scanned < SECTION_FILTER_MAX_PAGES && out.size < SECTION_FILTER_TARGET) {
            val batch = attempt { homeSection(sectionKey, cursor) } ?: emptyList()
            if (batch.isEmpty() && scanned > 0) break
            batch.filter { filter.matches(it, CatalogTag::of) && passesWatched(filter, it) && passesDisliked(it) }
                .forEach { out.putIfAbsent(it.id, it) }
            scanned++
            cursor++
            if (batch.isEmpty()) break
        }
        // Выбранная сортировка обязана действовать и здесь: секционную ленту сервер
        // по ней не отсортирует (она строится из расписаний и подборок), поэтому
        // порядок наводится у нас. По умолчанию порядок категории не трогается.
        return sortClientSide(out.values.toList(), filter.sort)
    }

    /** Страница каталога озвучки: список уже целиком у нас, режем его сами. */
    private val DUBBING_PAGE = 30

    /** Источники, которые фильтруют сами, — плюс остальные, просеянные у нас. */
    private suspend fun mergeFiltered(filter: CatalogFilter, page: Int): List<Anime> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                active().map { src ->
                    async {
                        attempt {
                            // Источник без своего фильтра всё же обязан отдать ленту в
                            // том же порядке, иначе под фильтром он пропал бы целиком.
                            if (src.supportsFilter) src.filterPage(filter, page)
                            else src.catalogPage(sortIdFallback(filter.sort), page)
                        } ?: emptyList()
                    }
                }.map { it.await() }.flatten().let(::dedupeTitles)
            }
        }

    /** Порядок для источников без своего фильтра — тот же смысл, что у Anixart. */
    private fun sortIdFallback(sort: CatalogSort): Int = when (sort) {
        CatalogSort.POPULAR -> 4
        CatalogSort.RATING -> 3
        CatalogSort.FRESH -> 0
        CatalogSort.VIEWS -> 1
        CatalogSort.RELEASE_DATE -> 2
    }

    /**
     * Страница «Кино» / «Мультфильмов» под фильтром.
     *
     * Когда открыта КОНКРЕТНАЯ лента (например «Сейчас смотрят»), её нельзя выразить
     * через /discover — у TMDB это отдельная ручка /trending. Поэтому здесь та же
     * развилка, что и у аниме: без ленты спрашиваем каталог напрямую и получаем
     * точное число найденного, с лентой — просеиваем её страницы у себя, и тогда
     * счётчик честно говорит «столько нашлось из загруженного».
     */
    suspend fun cinemaFilterPage(
        filter: CatalogFilter,
        sectionPath: String?,
        page: Int,
        cartoons: Boolean,
    ): CatalogPage {
        if (sectionPath.isNullOrBlank()) {
            val raw = attempt { cinemaFor(cartoons).browseFiltered(filter, page, cartoons) }
                ?: CatalogPage(emptyList())
            return raw.copy(items = raw.items.filter { filter.matches(it, CatalogTag::of) })
        }
        val out = LinkedHashMap<String, Anime>()
        var scanned = 0
        var cursor = page.coerceAtLeast(1)
        while (scanned < SECTION_FILTER_MAX_PAGES && out.size < SECTION_FILTER_TARGET) {
            val batch = attempt { browsePage(sectionPath, cursor, cartoons) } ?: emptyList()
            batch.filter { filter.matches(it, CatalogTag::of) }.forEach { out.putIfAbsent(it.id, it) }
            scanned++
            cursor++
            if (batch.isEmpty()) break
        }
        return CatalogPage(sortClientSide(out.values.toList(), filter.sort))
    }

    /** Most-anticipated unreleased titles, ranked by favourites. */
    /**
     * «Ожидаемые анонсы».
     *
     * Всегда от Anixart, каким бы ни был основной источник: у него лента анонсов
     * ранжирована по добавлениям в избранное — это и есть «ожидаемые». У Yummy
     * анонсы — по просмотрам страницы, и наверху оказывались Genshin и Wakfu. Лента
     * основного источника (если это не Anixart) добавляется ХВОСТОМ — только то, чего
     * у Anixart нет (по названию), — и не спорит с его порядком.
     */
    private suspend fun announces(): List<Anime> {
        var failure: Exception? = null
        suspend fun pages(load: suspend (Int) -> List<Anime>): List<Anime> = (0..3).flatMap { page ->
            try {
                load(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                emptyList()
            }
        }
        val anixart = aggregators.filterIsInstance<com.aniblaze.aggregator.source.AnixartSource>().firstOrNull()
        val primary = anixart?.let { src -> pages { page -> src.catalogPage(6, page) } }.orEmpty()
            .filter { it.rating < 1.0 && it.favoritesCount >= 500 }
            .distinctBy { it.id }
            .sortedByDescending { it.favoritesCount }
        val others = if (anixart != null && active().all { it === anixart }) emptyList() else pages { page -> catalogPage(6, page) }
            .filter { it.rating < 1.0 && (it.airingStatus == 3 || it.episodesAvailable == 0) && !it.id.startsWith("ax:") }
            .distinctBy { it.id }
            .sortedByDescending { it.favoritesCount }
        // Ни одной страницы ниоткуда — это сбой, а не «анонсов нет».
        if (primary.isEmpty() && others.isEmpty()) failure?.let { throw it }
        val seen = primary.map { announceKey(it.title) }.toMutableSet()
        return primary + others.filter { seen.add(announceKey(it.title)) }
    }

    private fun announceKey(title: String): String =
        title.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    suspend fun random(): Anime? = withContext(Dispatchers.IO) {
        for (src in active()) {
            val a = attempt { src.random() }
            if (a != null) return@withContext a
        }
        null
    }

    /**
     * A random title to hop to when the finished one has no more episodes (the
     * player's «случайное аниме» toggle): rolled from «В тренде», «Сейчас смотрят»
     * or the source's true random endpoint — whichever the dice picked first that
     * actually returned something.
     *
     * Never suggests something already seen: anything in history, with saved
     * progress or with watched episodes is excluded — by id AND by normalized
     * title, because the same anime carries a different id on every source.
     */
    suspend fun randomOngoingPick(excludeId: String): Anime? = withContext(Dispatchers.IO) {
        val s = settings.state.value
        val seenIds = buildSet {
            add(excludeId)
            s.history.forEach { add(it.id) }
            s.progress.forEach { add(it.anime.id) }
            s.watched.forEach { add(it.substringBeforeLast('#')) }
        }
        val seenTitles = buildSet {
            s.history.forEach { add(normTitle(it.title)) }
            s.progress.forEach { add(normTitle(it.anime.title)) }
        }
        fun unseen(a: Anime) = a.id !in seenIds && normTitle(a.title) !in seenTitles
        for (kind in listOf("trending", "watching", "random").shuffled()) {
            val pick = when (kind) {
                // The random endpoint returns ONE title per call — reroll a few
                // times if the dice keep landing on something already watched.
                "random" -> (1..4).firstNotNullOfOrNull { attempt { random() }?.takeIf(::unseen) }
                else -> attempt { catalog(kind) }.orEmpty().filter(::unseen).randomOrNull()
            }
            if (pick != null) return@withContext pick
        }
        null
    }

    /** Title key for "already watched" comparisons across sources. */
    private fun normTitle(title: String): String =
        title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Pixel height out of a quality label: "1080p" → 1080, "Auto" → 0. */
    fun qualityHeight(quality: String): Int =
        Regex("(\\d{3,4})").find(quality)?.value?.toIntOrNull() ?: 0

    /**
     * Per-dub view stats from Anixart by title — the percentage fallback for the
     * player's озвучка picker when the playing source (Kodik, balancer…) reports
     * no view counts of its own. Queried regardless of which sources are enabled:
     * the stats are display-only and never affect playback.
     */
    suspend fun voiceoverShares(title: String): Map<String, Long> = withContext(Dispatchers.IO) {
        val anixart = aggregators.firstOrNull { it is com.aniblaze.aggregator.source.AnixartSource }
            as? com.aniblaze.aggregator.source.AnixartSource ?: return@withContext emptyMap()
        attempt { anixart.voiceoverViewsByTitle(title) } ?: emptyMap()
    }

    /**
     * 1080p rescue for a stream that topped out at 720p (Anixart/Kodik's usual
     * ceiling). AniLibria is the one source that reliably serves 1080 — resolve the
     * same title+episode there and hand back ONLY its ≥1080p renditions, labelled
     * with the source so picking one knowingly switches the stream (and its dub).
     * Called AFTER playback started, so it never delays the first frame.
     */
    suspend fun hiResVariants(title: String, segment: Int, year: Int): List<StreamVariant> = withContext(Dispatchers.IO) {
        if (title.isBlank() || segment <= 0) return@withContext emptyList()
        val libria = aggregators.firstOrNull { it.name.contains("libria", ignoreCase = true) }
            ?: return@withContext emptyList()
        val res = attempt { resolveViaSource(libria, contentId = "", title = title, segment = segment, expectedYear = year) }
            ?: return@withContext emptyList()
        res.variants.orEmpty()
            .filter { qualityHeight(it.quality) >= 1080 }
            .map { StreamVariant("${it.quality} · AniLibria", it.url) }
    }

    /** Проверить источник по кнопке в настройках: жив ли API. Результат — в SourceHealth. */
    suspend fun checkSource(name: String): Boolean = withContext(Dispatchers.IO) {
        val src = aggregators.firstOrNull { it.name == name } ?: return@withContext false
        val ok = try {
            src.validateSource("")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SourceHealth.record(name, ok = false, error = error.message.orEmpty())
            return@withContext false
        }
        SourceHealth.record(name, ok = ok, error = if (ok) "" else "проверка не прошла")
        ok
    }

    /** Other seasons / franchise entries of a title (for the season selector). */
    suspend fun seasons(contentId: String): List<Anime> = seasons(Anime(id = contentId, title = "", poster = ""))

    /**
     * Сезоны и части франшизы для карточки — с ЛЮБОГО сезона.
     *
     * Почему раньше пропадали со второго. Список спрашивался у активных источников,
     * а карточка второго сезона, открытая из истории/избранного, чаще всего несёт id
     * ДРУГОГО источника (`canonical` подменяет id на известный из истории): при
     * основном источнике Yummy приходил `ax:`-id, Yummy его не знал, Anixart не
     * спрашивали — пусто, и переключатель не рисовался. Первый сезон открывался из
     * каталога со «своим» id, потому и работал.
     *
     * Теперь: сначала владелец id (кто угодно, не только активный), затем остальные
     * источники, умеющие франшизы, — по СТРОГОМУ совпадению названия и года (см.
     * yummyIdFor / anixartIdFor), а не по первому похожему. Совпавшая с текущей
     * карточкой запись подменяется ею самой, чтобы id текущего сезона оставался
     * тем же и меню его выделяло. Кэш — по id карточки.
     */
    suspend fun seasons(anime: Anime): List<Anime> = withContext(Dispatchers.IO) {
        if (isCinema(anime.id)) return@withContext emptyList()
        seasonsCache[anime.id]?.let { return@withContext it }
        val owner = aggregators.firstOrNull { it.ownsContentId(anime.id) }
        var list = owner?.let { attempt { it.related(anime.id) } }.orEmpty()
        if (list.isEmpty() && anime.title.isNotBlank()) {
            val yummy = aggregators.filterIsInstance<com.aniblaze.aggregator.source.YummyAnimeSource>().firstOrNull()
            val anixart = aggregators.filterIsInstance<com.aniblaze.aggregator.source.AnixartSource>().firstOrNull()
            val fallbacks = listOfNotNull(
                anixart?.takeIf { it !== owner }?.let { src -> suspend { anixartIdFor(anime)?.let { id -> attempt { src.related(id) } } } },
                yummy?.takeIf { it !== owner }?.let { src -> suspend { yummyIdFor(anime, src)?.let { id -> attempt { src.related(id) } } } },
            )
            for (load in fallbacks) {
                list = load().orEmpty()
                if (list.isNotEmpty()) break
            }
        }
        val merged = mergeCurrentSeason(anime, list)
        com.aniblaze.desktop.player.PlayerDiagnostics.log(
            "detail.seasons",
            "id=${anime.id}; owner=${owner?.javaClass?.simpleName ?: "none"}; count=${merged.size}; currentInList=${merged.any { it.id == anime.id }}",
        )
        if (merged.isNotEmpty()) seasonsCache[anime.id] = merged
        merged
    }

    private val seasonsCache = java.util.concurrent.ConcurrentHashMap<String, List<Anime>>()

    /**
     * The Anixart release id for ANY anime card. Own ids pass through; foreign ids
     * (an AniLibria alias, an av: id) are located by a STRICT title search — name
     * must match AND the years must agree when both are known. No confident match →
     * null, and the caller shows nothing rather than someone else's data. That
     * strictness is what keeps descriptions/comments from ever mixing titles up.
     */
    private suspend fun anixartIdFor(anime: Anime): String? {
        if (anime.id.startsWith("ax:")) return anime.id
        val anixart = aggregators.firstOrNull { it is com.aniblaze.aggregator.source.AnixartSource }
            ?: return null
        val candidates = attempt { anixart.search(anime.title) }.orEmpty()
            .filter {
                it.id.startsWith("ax:") && titleMatches(anime.title, it.title) &&
                    (anime.year == 0 || it.year == 0 || it.year == anime.year)
            }
        return selectCommentRelease(anime, candidates)?.id
    }

    /** Полное описание тайтла: каталожная лента Anixart режет его до ~200 символов;
     *  /release/{id} отдаёт целиком. Null — оставляем то, что есть на карточке. */
    suspend fun fullDetails(anime: Anime): Anime? = withContext(Dispatchers.IO) {
        if (isCinema(anime.id)) {
            val cinema = cinemaSources.firstOrNull { it.owns(anime.id) }
                ?: return@withContext null
            return@withContext attempt { cinema.description(anime.id) }
                ?.takeIf { it.isNotBlank() }
                ?.let { anime.copy(description = it) }
        }
        // Свой источник знает свою карточку лучше, чем Anixart найдёт её по названию:
        // у YummyAnime лента без жанров и с урезанным описанием, а `/anime/{id}` — с
        // полными, плюс оригинальное название для поиска оценки MAL ниже.
        val own = aggregators.firstOrNull { it.ownsContentId(anime.id) && it !is com.aniblaze.aggregator.source.AnixartSource }
        val ownDetails = when (own) {
            is com.aniblaze.aggregator.source.YummyAnimeSource -> attempt { own.details(anime.id) }
            else -> null
        }
        val anixart = aggregators.firstOrNull { it is com.aniblaze.aggregator.source.AnixartSource }
            as? com.aniblaze.aggregator.source.AnixartSource
        val details = ownDetails
            ?: anixart?.let { src -> anixartIdFor(anime)?.let { id -> attempt { src.details(id) } } }
            ?: return@withContext null
        // Заодно подтягиваем оценку MyAnimeList: тот же проход дозаполнения, что
        // собирает жанры, приносит и её. Отдельного обхода не заводим — он снова
        // отбирал бы сеть у играющего потока.
        //
        // `status` у нас несёт оригинальное (ромадзи) название, а по нему Shikimori
        // ищет заметно точнее, чем по русскому.
        val score = attempt { aniskip.communityScore(details.title, details.status) }
            ?: return@withContext details
        details.copy(malScore = score.first, malVotes = score.second, malLowVotes = score.third)
    }

    /** Production credits are metadata-only and never participate in stream routing. */
    suspend fun titleCredits(anime: Anime): TitleCredits? =
        metadataSource?.titleCredits(anime)

    /**
     * A Shikimori studio page is first a cheap candidate list. Only cards that can
     * be resolved by an enabled playback source survive, so the page cannot lead to
     * a metadata-only dead end. Resolution is bounded and cached for the app session.
     */
    suspend fun studioTitles(studio: StudioCredit, page: Int): StudioTitlePage = withContext(Dispatchers.IO) {
        val metadataPage = metadataSource?.studioTitles(studio, page)
            ?: return@withContext StudioTitlePage(emptyList(), hasMore = false)
        metadataPage.copy(titles = resolvePlayableMetadata(metadataPage.titles))
    }

    suspend fun personDetails(person: PersonCredit): PersonDetails? = withContext(Dispatchers.IO) {
        val details = metadataSource?.personDetails(person) ?: return@withContext null
        val gate = kotlinx.coroutines.sync.Semaphore(5)
        val byMetadataId = coroutineScope {
            details.works.map { work -> async(Dispatchers.IO) {
                gate.withPermit { resolvePlayableMetadata(work.anime)?.let { work.copy(anime = it) } }
            } }.mapNotNull { it.await() }
        }
            .distinctBy { it.anime.id to it.role }
        details.copy(works = byMetadataId)
    }

    private suspend fun resolvePlayableMetadata(items: List<Anime>): List<Anime> = coroutineScope {
        val gate = kotlinx.coroutines.sync.Semaphore(5)
        items.map { metadata -> async(Dispatchers.IO) {
            gate.withPermit { resolvePlayableMetadata(metadata) }
        } }.mapNotNull { it.await() }.distinctBy { it.id }
    }

    internal suspend fun resolvePlayableMetadata(metadata: Anime): Anime? {
        val playbackSources = active().filter { it.name !in METADATA_ONLY_SOURCES }
        val cacheKey = playbackSources.joinToString(separator = ",", postfix = "|${metadata.id}") { it.name }
        playableMetadataCache[cacheKey]?.let { return it.anime }
        var firstFailure: Exception? = null
        val candidates = buildList {
            for (source in playbackSources) {
                try {
                    addAll(source.search(metadata.title))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (firstFailure == null) firstFailure = error
                }
            }
        }.filter {
                titleMatches(it.title, metadata.title) &&
                    (metadata.year == 0 || it.year == 0 || metadata.year == it.year)
            }
        val resolved = candidates.firstOrNull()?.let { playable ->
            playable.copy(
                year = playable.year.takeIf { it > 0 } ?: metadata.year,
                episodesTotal = playable.episodesTotal.takeIf { it > 0 } ?: metadata.episodesTotal,
                episodesAvailable = maxOf(playable.episodesAvailable, metadata.episodesAvailable),
                airingStatus = playable.airingStatus.takeIf { it > 0 } ?: metadata.airingStatus,
                studio = playable.studio.ifBlank { metadata.studio },
            )
        }
        // An empty successful search is a cacheable miss. A miss while at least one
        // enabled source failed is not: retry must be able to discover that title.
        if (resolved == null && firstFailure != null) throw firstFailure!!
        playableMetadataCache[cacheKey] = PlayableMetadata(resolved)
        return resolved
    }

    /**
     * Обсуждение тайтла, отдаваемое ПО МЕРЕ ЗАГРУЗКИ и без повторов.
     *
     * Первая порция уходит на экран сразу, остальные страницы догружаются следом —
     * ждать полного обхода незачем, а у Наруто он и невозможен мгновенно: 6456 записей
     * на 258 страницах (замерено 23.08).
     *
     * Что здесь собрано в одном месте и почему:
     *
     *  * ОТБОР ПО ИДЕНТИЧНОСТИ идёт ЗДЕСЬ, а не на экране. Раньше каждый потребитель
     *    делал свой `distinctBy` над своим куском, и совпадения между страницами,
     *    между кэшем и сетью, между повторными открытиями тайтла никто не ловил.
     *  * КЭШ ОБЩИЙ. Второе открытие того же тайтла не ходит в сеть заново и не
     *    приписывает те же записи ещё раз: копится один накопитель на релиз.
     *  * ОДИН ОБХОД НА ТАЙТЛ. Замок по идентификатору релиза не даёт плееру и странице
     *    тайтла тянуть одно и то же двумя запросами разом.
     *  * ОБХОД ЗНАЕТ, ГДЕ КОНЕЦ. Источник сообщает число страниц и записей; раньше оба
     *    поля выбрасывались, и обход упирался в выдуманный потолок в двенадцать
     *    страниц — три процента обсуждения Наруто.
     *
     * Релиз Anixart соответствует одному сезону, а endpoint возвращает обсуждение
     * релиза целиком: параметр episode он игнорирует. Поэтому это один общий пул
     * сезона, а не N разрозненных запросов по сериям.
     *
     * [maxPages] — бюджет вызывающего, а не выдуманный конец обсуждения. Плеер
     * использует технический safety limit и идёт до честного конца в фоне.
     */
    /** Сезоны (ключи кэша), для которых обсуждение Yummy уже подмешано в этом запуске. */
    private val yummyCommentsMerged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** `ya:` id по карточке любого источника: свой — сразу, чужой — поиском по названию. */
    private val yummyIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun yummyIdFor(anime: Anime, yummy: com.aniblaze.aggregator.source.YummyAnimeSource): String? {
        if (yummy.ownsContentId(anime.id)) return anime.id
        yummyIdCache[anime.id]?.let { return it.ifBlank { null } }
        val hit = attempt { yummy.search(anime.title) }.orEmpty().firstOrNull {
            titleMatches(anime.title, it.title) && (anime.year == 0 || it.year == 0 || it.year == anime.year)
        }
        yummyIdCache[anime.id] = hit?.id.orEmpty()
        return hit?.id
    }

    /**
     * Подмешивает в накопитель сезона обсуждение того же тайтла с YummyAnime.
     *
     * Зачем: настоящих реплик у Anixart на серию десятки, и в режиме «только реальные»
     * лента редеет. У Yummy под тем же тайтлом своя аудитория — это ещё столько же
     * живых мнений. Записи помечены `source = "yummy"`, поэтому не путаются с Anixart
     * ни в отборе по идентичности, ни при сверке полного обхода.
     *
     * Голова «nice» (по лайкам) — старые общие мнения, годные любой серии; дальше
     * страницы «new». Бюджет ограничен: это добавка к чату, а не второй полный обход.
     * Один раз на запуск на сезон; выключается в настройках ([AppSettings.chatYummyComments]).
     */
    private suspend fun mergeYummyComments(anime: Anime, seasonKey: String, entry: CommentWalk) {
        if (!settings.state.value.chatYummyComments) return
        if (!yummyCommentsMerged.add(seasonKey)) return
        val yummy = aggregators.filterIsInstance<com.aniblaze.aggregator.source.YummyAnimeSource>().firstOrNull() ?: return
        val id = yummyIdFor(anime, yummy) ?: return
        var added = 0
        for ((sort, pages) in listOf(
            com.aniblaze.aggregator.source.YummyAnimeSource.COMMENTS_TOP to YUMMY_TOP_PAGES,
            com.aniblaze.aggregator.source.YummyAnimeSource.COMMENTS_FRESH to YUMMY_FRESH_PAGES,
        )) {
            for (page in 0 until pages) {
                val chunk = try {
                    yummy.comments(id, page, sort)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Сбой Yummy не должен стоить чата: следующий запуск попробует снова.
                    com.aniblaze.desktop.player.PlayerDiagnostics.failure("comments.yummy", error)
                    yummyCommentsMerged.remove(seasonKey)
                    return
                }
                if (chunk.isEmpty()) break
                added += entry.accumulator.add(chunk)
                if (chunk.size < com.aniblaze.aggregator.source.YummyAnimeSource.COMMENT_PAGE) break
            }
        }
        if (added > 0) {
            trimCommentCache(seasonKey)
            persistCommentWalk(seasonKey, entry)
        }
        com.aniblaze.desktop.player.PlayerDiagnostics.log(
            "comments.yummy",
            "title=${anime.title.take(28)}; id=$id; added=$added; total=${entry.accumulator.size}",
        )
    }

    /** Сезоны, для которых ветки ответов уже подгружены в этом запуске. */
    private val repliesMerged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Ветки ответов под самыми обсуждаемыми записями — в тот же накопитель.
     *
     * Списки обоих источников отдают только самостоятельные записи; ответы лежат за
     * отдельным запросом на каждую ветку. Всё обсуждение так не соберёшь (у Наруто
     * тысячи веток), поэтому берётся ограниченное число ветвей с наибольшим числом
     * ответов и голосов. У каждого ответа проставлен «@ник» родителя — в чате видно,
     * кому адресована реплика. Один раз на запуск на сезон; отключается настройкой.
     */
    private suspend fun mergeReplyThreads(anime: Anime, seasonKey: String, entry: CommentWalk) {
        if (!settings.state.value.chatReplies) return
        if (!repliesMerged.add(seasonKey)) return
        val anixart = aggregators.filterIsInstance<AnixartSource>().firstOrNull()
        val yummy = aggregators.filterIsInstance<com.aniblaze.aggregator.source.YummyAnimeSource>().firstOrNull()
        val parents = entry.accumulator.snapshot()
            .filter { it.replyCount > 0 && it.parentId == 0L }
            .sortedWith(compareByDescending<com.aniblaze.aggregator.model.TitleComment> { it.replyCount }.thenByDescending { it.votes })
            .take(REPLY_THREADS_PER_SEASON)
        var added = 0
        for (parent in parents) {
            val replies = try {
                when (parent.source) {
                    "anixart" -> anixart?.commentReplies(parent.id, parent.author)
                    "yummy" -> yummy?.commentReplies(parent.id, parent.author)
                    else -> null
                }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.aniblaze.desktop.player.PlayerDiagnostics.failure("comments.replies", error)
                continue
            }
            added += entry.accumulator.add(replies.take(REPLIES_PER_THREAD))
        }
        if (added > 0) {
            trimCommentCache(seasonKey)
            persistCommentWalk(seasonKey, entry)
        }
        com.aniblaze.desktop.player.PlayerDiagnostics.log(
            "comments.replies",
            "title=${anime.title.take(28)}; threads=${parents.size}; added=$added; total=${entry.accumulator.size}",
        )
    }

    fun commentFeed(
        anime: Anime,
        maxPages: Int = com.aniblaze.aggregator.source.COMMENT_SAFETY_PAGES,
    ): Flow<CommentBatch> = flow {
        if (isCinema(anime.id)) {
            val cinema = cinemaSources.firstOrNull { it.owns(anime.id) }
            val comments = cinema?.let { attempt { it.comments(anime.id, 0) } }.orEmpty()
            emit(CommentBatch(comments, complete = true, stop = CommentStop.EMPTY_PAGE))
            return@flow
        }
        val anixart = aggregators.firstOrNull { it is com.aniblaze.aggregator.source.AnixartSource }
            as? com.aniblaze.aggregator.source.AnixartSource
        val id = anixart?.let { anixartIdFor(anime) }
        if (anixart == null || id == null) {
            emit(CommentBatch(emptyList(), complete = true, stop = CommentStop.FAILED))
            return@flow
        }
        val seasonKey = seasonCommentKey("anixart", id)
        val cached = commentWalk(seasonKey)
        val stale = cached.stop != null && commentStore.isStale(cached.updatedAt)
        var deliveredSize = -1
        var deliveredComplete = false
        // Кэш отдаётся ДО замка. Иначе второй экран, открытый на том же сезоне, ждал бы
        // чужого обхода, чтобы получить уже собранное, — то есть смотрел бы в пустоту
        // при полном кэше.
        if (cached.accumulator.size > 0 || cached.stop != null) {
            deliveredSize = cached.accumulator.size
            deliveredComplete = cached.stop != null && !stale
            emit(
                CommentBatch(
                    comments = cached.accumulator.snapshot(),
                    complete = cached.stop != null && !stale,
                    stop = cached.stop.takeUnless { stale },
                    fromCache = true,
                    refreshing = stale || cached.stop == null,
                ),
            )
        }
        commentLock(seasonKey).withLock {
            val entry = commentWalk(seasonKey)
            // Обсуждение того же тайтла с YummyAnime — в тот же накопитель (см.
            // mergeYummyComments). До проверок ниже, чтобы и «полный свежий кэш»
            // отдал выросший снимок: ветка сравнивает размер с уже доставленным.
            mergeYummyComments(anime, seasonKey, entry)
            // Ветки ответов — когда в накопителе уже есть голова обсуждения: у полного
            // кэша прямо здесь, у свежего обхода — после его завершения (ниже).
            if (entry.stop != null) mergeReplyThreads(anime, seasonKey, entry)
            // Пока мы ждали замок, другой экран мог закончить обход. В таком случае
            // верхний снимок был пустым/старым — обязательно отдаём выросший результат.
            if (entry.stop != null && !commentStore.isStale(entry.updatedAt)) {
                if (entry.accumulator.size != deliveredSize || !deliveredComplete) {
                    emit(
                        CommentBatch(
                            entry.accumulator.snapshot(),
                            complete = true,
                            stop = entry.stop,
                            fromCache = true,
                            refreshing = false,
                        ),
                    )
                }
                return@withLock
            }
            val refreshingStored = entry.stop != null
            // Полный, но устаревший снимок остаётся на экране, а обход начинается с
            // головы заново. Новое сливается в тот же accumulator и не удваивает кэш.
            if (entry.stop != null) entry.restartTraversal()
            if (entry.nextPage >= maxPages) {
                if (entry.accumulator.size != deliveredSize) {
                    emit(
                        CommentBatch(
                            entry.accumulator.snapshot(),
                            complete = false,
                            stop = null,
                            fromCache = true,
                            refreshing = false,
                        ),
                    )
                }
                return@withLock
            }
            var barren = 0
            while (entry.nextPage < maxPages) {
                // ДВЕ СОРТИРОВКИ, И ОБЕ НУЖНЫ.
                //
                // Голова «сначала лучшие» — это старые записи БЕЗ ПОМЕТКИ СЕРИИ, и
                // только они годятся любой серии подряд. Дальше идёт полный обход по
                // свежим: он однозначен и не съезжает. Замер, из-за которого так
                // сделано, — у AnixartSource.COMMENTS_TOP.
                //
                // Страницы обеих сортировок валятся в ОДИН накопитель; повторы между
                // ними снимает отбор по идентичности, ради которого он и заведён.
                val top = entry.nextPage < COMMENT_TOP_PAGES
                val page = if (top) entry.nextPage else entry.nextPage - COMMENT_TOP_PAGES
                val chunk = try {
                    anixart.comments(
                        id,
                        page,
                        sort = if (top) AnixartSource.COMMENTS_TOP else AnixartSource.COMMENTS_FRESH,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Сбой сети НЕ ЗАПОМИНАЕТСЯ. Отметить обход законченным значило бы
                    // навсегда (до перезапуска) объявить обсуждение этого тайтла
                    // прочитанным из-за одной моргнувшей секунды: следующее открытие
                    // получило бы кэш и остановилось, не сделав ни одной попытки.
                    // Уже собранное остаётся, продолжить можно с той же страницы.
                    com.aniblaze.desktop.player.PlayerDiagnostics.failure("comments.page", error)
                    persistCommentWalk(seasonKey, entry)
                    emit(
                        CommentBatch(
                            entry.accumulator.snapshot(),
                            complete = false,
                            stop = CommentStop.FAILED,
                            refreshing = false,
                        ),
                    )
                    return@withLock
                }
                val beforeRevision = entry.accumulator.revision
                val fresh = entry.accumulator.add(chunk.items)
                // Duplicates against yesterday's cache are not the end of today's walk.
                // The TOP prefix is deliberately excluded from the FRESH traversal set.
                val traversalFresh = if (top) chunk.items.size else chunk.items.count {
                    entry.seenFresh.add(com.aniblaze.aggregator.source.commentIdentity(it))
                }
                trimCommentCache(seasonKey)
                barren = if (!top && traversalFresh == 0) barren + 1 else 0
                entry.nextPage++
                if (!top) entry.rawSeen += chunk.rawCount
                if (chunk.totalPages > 0) entry.totalPages = chunk.totalPages
                if (chunk.totalCount > 0) entry.totalCount = chunk.totalCount
                com.aniblaze.desktop.player.PlayerDiagnostics.log(
                    "comments.page",
                    "title=${anime.title.take(28)}; sort=${if (top) "top" else "fresh"}; page=$page; received=${chunk.items.size}; " +
                        "newUnique=$fresh; duplicates=${chunk.items.size - fresh}; total=${entry.accumulator.size}" +
                        (if (entry.totalCount > 0) "; source=${entry.totalCount}/${entry.totalPages}стр" else ""),
                )
                // Голова топовых — не обход, а добор пула, и «страница без новых» там
                // ничего не значит: порядок неоднозначен. Но ПУСТОТА значит и там —
                // источник не ответил, и добирать нечего. Без этой оговорки лежащий
                // Anixart всё равно получал шесть запросов подряд впустую (видно в
                // журнале 24.08: шесть страниц, received=0, unique=0).
                val stop = if (top) {
                    // При обновлении старого непустого снимка пустой ответ скорее
                    // означает временный сбой источника. Старые настоящие реплики не
                    // объявляем исчезнувшими и не затираем.
                    CommentStop.EMPTY_PAGE.takeIf { chunk.rawCount == 0 && !refreshingStored }
                } else commentStop(
                    page = page,
                    rawReceived = chunk.rawCount,
                    rawSeen = entry.rawSeen,
                    freshUnique = traversalFresh,
                    barrenStreak = barren,
                    totalCount = entry.totalCount,
                    uniqueSeen = entry.accumulator.size,
                )
                if (stop != null) entry.stop = stop
                if (stop == CommentStop.LAST_PAGE && entry.canReconcile) {
                    // Сверка — только с Anixart: чужие записи (Yummy) в обходе не
                    // участвуют и исчезнуть по его итогам не должны.
                    entry.accumulator.retainIdentities(entry.seenFresh, keepSources = setOf("yummy"), keepReplies = true)
                }
                // Каждая страница уходит на экран сразу, а не после всего обхода.
                val reachedEmitMark = fresh > 0 && entry.accumulator.size >= entry.nextEmitAt
                val updatedExisting = entry.accumulator.revision != beforeRevision && fresh == 0
                if (reachedEmitMark || updatedExisting || stop != null) {
                    persistCommentWalk(seasonKey, entry)
                    emit(
                        CommentBatch(
                            entry.accumulator.snapshot(),
                            complete = stop != null,
                            stop = stop,
                            refreshing = stop == null,
                        ),
                    )
                    while (entry.nextEmitAt <= entry.accumulator.size && entry.nextEmitAt < Int.MAX_VALUE / 2) {
                        entry.nextEmitAt *= 2
                    }
                }
                if (stop == null) kotlinx.coroutines.delay(COMMENT_PAGE_GAP_MS)
                if (stop != null) {
                    com.aniblaze.desktop.player.PlayerDiagnostics.log(
                        "comments.done",
                        "title=${anime.title.take(28)}; pages=${entry.nextPage}; unique=${entry.accumulator.size}; stop=$stop",
                    )
                    break
                }
            }
            if (entry.stop == null && entry.nextPage >= maxPages) {
                com.aniblaze.desktop.player.PlayerDiagnostics.log(
                    "comments.budget",
                    "title=${anime.title.take(28)}; pages=${entry.nextPage}; unique=${entry.accumulator.size}",
                )
                persistCommentWalk(seasonKey, entry)
                emit(CommentBatch(entry.accumulator.snapshot(), complete = false, stop = null, refreshing = false))
            }
            // Ветки ответов под собранной головой — одним хвостовым снимком, чтобы
            // повторное открытие отдало ровно то же, что и первое.
            val beforeReplies = entry.accumulator.size
            mergeReplyThreads(anime, seasonKey, entry)
            if (entry.accumulator.size != beforeReplies) {
                emit(
                    CommentBatch(
                        entry.accumulator.snapshot(),
                        complete = entry.stop != null,
                        stop = entry.stop,
                        refreshing = false,
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Состояние обхода одного сезонного релиза: живёт между открытиями и запусками. */
    private class CommentWalk(stored: StoredCommentSeason? = null) {
        val accumulator = com.aniblaze.aggregator.source.CommentAccumulator()
        val seenFresh = HashSet<String>()
        var canReconcile = stored == null || stored.nextPage == 0
        var nextPage = stored?.nextPage ?: 0
        /** Сколько сырых записей уже прошло через разбор — сравнимо с [totalCount]. */
        var rawSeen = stored?.rawSeen ?: 0
        var totalPages = stored?.totalPages ?: 0
        var totalCount = stored?.totalCount ?: 0
        var stop: CommentStop? = stored?.stop
        var updatedAt: Long = stored?.updatedAt ?: 0L
        var nextEmitAt = COMMENT_FIRST_EMIT

        init {
            stored?.comments?.let(accumulator::add)
            while (nextEmitAt <= accumulator.size && nextEmitAt < Int.MAX_VALUE / 2) nextEmitAt *= 2
        }

        fun restartTraversal() {
            seenFresh.clear()
            canReconcile = true
            nextPage = 0
            rawSeen = 0
            totalPages = 0
            totalCount = 0
            stop = null
        }

        fun stored(now: Long) = StoredCommentSeason(
            comments = accumulator.snapshot(),
            nextPage = nextPage,
            rawSeen = rawSeen,
            totalPages = totalPages,
            totalCount = totalCount,
            stop = stop,
            updatedAt = now,
        )
    }

    /**
     * Собранные обсуждения, самое давнее по обращению вытесняется первым.
     *
     * Потолок нужен: у «Блича» 13 384 записи (замерено 23.08), а за долгий сеанс
     * тайтлов открывают десятки — без него это тихая утечка на всю жизнь процесса.
     * Порядок ПО ОБРАЩЕНИЮ, а не по добавлению: тайтл, к которому возвращаются,
     * переживает тот, что открыли однажды.
     */
    private val commentCache = object : LinkedHashMap<String, CommentWalk>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CommentWalk>): Boolean {
            val evict = size > COMMENT_CACHE_TITLES
            if (evict) commentLocks.remove(eldest.key)
            return evict
        }
    }
    private val commentLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun commentWalk(key: String): CommentWalk = synchronized(commentCache) {
        commentCache.getOrPut(key) { CommentWalk(commentStore.load(key)) }
    }

    private fun persistCommentWalk(key: String, entry: CommentWalk) {
        val now = System.currentTimeMillis()
        entry.updatedAt = now
        commentStore.save(key, entry.stored(now))
    }

    /** Вытесняет старые обсуждения по ОБЩЕМУ числу записей, а не только по titles. */
    private fun trimCommentCache(currentId: String) = synchronized(commentCache) {
        var total = commentCache.values.sumOf { it.accumulator.size }
        if (total <= COMMENT_CACHE_ITEMS) return@synchronized
        val iterator = commentCache.entries.iterator()
        while (total > COMMENT_CACHE_ITEMS && commentCache.size > 1 && iterator.hasNext()) {
            val old = iterator.next()
            if (old.key == currentId) continue
            total -= old.value.accumulator.size
            commentLocks.remove(old.key)
            iterator.remove()
        }
    }

    /**
     * Один обход на релиз: два экрана не тянут одно и то же двумя запросами.
     *
     * `computeIfAbsent`, а не `getOrPut`: последний у ConcurrentHashMap не атомарен
     * (сначала читает, потом кладёт), и два экрана, открывшие тайтл одновременно,
     * получили бы ДВА РАЗНЫХ замка — то есть ровно два параллельных обхода, от которых
     * этот замок и заводился.
     */
    private fun commentLock(id: String): Mutex = commentLocks.computeIfAbsent(id) { Mutex() }

    /** Ветка ответов под комментарием. */
    suspend fun commentReplies(commentId: Long): List<com.aniblaze.aggregator.model.TitleComment> =
        withContext(Dispatchers.IO) {
            val anixart = aggregators.firstOrNull { it is com.aniblaze.aggregator.source.AnixartSource }
                as? com.aniblaze.aggregator.source.AnixartSource ?: return@withContext emptyList()
            attempt { anixart.commentReplies(commentId) } ?: emptyList()
        }

    /** Расписание онгоингов: день недели (1=Пн..7=Вс) → тайтлы. Дни без выходов
     *  опущены. Источник — недельное расписание Anixart (broadcast проставлен там). */
    suspend fun weekSchedule(): Map<Int, List<Anime>> = withContext(Dispatchers.IO) {
        (attempt { catalog("ongoing") } ?: emptyList())
            .filter { it.broadcast in 1..7 }
            .groupBy { it.broadcast }
    }

    /** "Похожие" for the detail page. Asked of EVERY aggregator, not just the active
     *  ones — only the source owning the id can answer (recommendations live on its
     *  release object), and it must answer even when another source is primary. */
    suspend fun recommended(contentId: String): List<Anime> = withContext(Dispatchers.IO) {
        if (isCinema(contentId)) return@withContext emptyList()
        aggregators.firstNotNullOfOrNull { src ->
            attempt { src.recommended(contentId) }?.takeIf { it.isNotEmpty() }
        } ?: emptyList()
    }

    /**
     * Блок «Похожие» целиком: рекомендации источника ПЛЮС свой подбор по каталогу.
     *
     * Почему блока раньше не было вовсе. Единственным источником данных был
     * [recommended], то есть поле `recommended_releases` у релиза Anixart, и оно
     * молчит сразу в трёх случаях: у релиза этого поля просто нет (у нишевых и
     * свежих тайтлов — обычное дело); тайтл открыт НЕ с Anixart, и его id (alias
     * AniLibria, `av:…`) в `/release/{id}` не разрешается; запрос упал, а [attempt]
     * превратил исключение в пустой список. Во всех трёх экран получал `emptyList()`
     * и молча не рисовал блок — «похожих нет» и «спросить было не у кого» выглядели
     * одинаково.
     *
     * Теперь пусто бывает только когда каталог не ответил вообще ничем: подбор идёт
     * по ЖАНРАМ самого тайтла через тот же фильтр каталога, которым пользуется
     * главная, и ранжируется [Recommender.similar].
     *
     * @param franchise сезоны/части этого тайтла (то, что уже загрузил селектор
     *        сезонов) — они исключаются из выдачи вместе с тем, что отсеет
     *        нормализация названий.
     */
    suspend fun similarTitles(anime: Anime, franchise: List<Anime> = emptyList()): List<Anime> =
        withContext(Dispatchers.IO) {
            if (isCinema(anime.id)) {
                return@withContext cinemaSources.firstOrNull { it.owns(anime.id) }
                    ?.let { attempt { it.similar(anime.id) } }
                    .orEmpty()
            }
            val fromSource = attempt { recommended(anime.id) } ?: emptyList()
            // Своего пула не берём, только когда источник И ТАК дал полный блок:
            // лишний обход каталога стоит нескольких запросов.
            val pool = if (fromSource.size >= Recommender.SIMILAR_LIMIT) {
                emptyList()
            } else {
                similarPool(anime)
            }
            Recommender.similar(
                base = anime,
                candidates = fromSource + pool,
                sourceIds = fromSource.map { it.id }.toSet(),
                exclude = franchise,
            )
        }

    /**
     * Кандидаты для «Похожих»: каталог, суженный ЖАНРАМИ открытого тайтла.
     *
     * Спрашиваем не «популярное вообще» (тогда у любого нишевого тайтла в похожих
     * оказывался бы один и тот же топ каталога), а фильтр по его собственным тегам —
     * Anixart исполняет такой запрос сам, остальные источники просеиваются на нашей
     * стороне (см. [animeFilterPage]). Двух тегов достаточно: с тремя и больше выдача
     * схлопывается до десятка карточек.
     */
    private suspend fun similarPool(anime: Anime): List<Anime> {
        val tags = CatalogTag.of(anime).take(SIMILAR_POOL_TAGS)
        val out = LinkedHashMap<String, Anime>()
        coroutineScope {
            val queries = buildList {
                if (tags.isEmpty()) {
                    // Жанров у карточки нет — остаётся общий срез каталога.
                    add(CatalogFilter(sort = CatalogSort.POPULAR))
                } else {
                    tags.forEach { add(CatalogFilter(tags = setOf(it), sort = CatalogSort.POPULAR)) }
                    // Плюс один запрос по ОБОИМ тегам сразу: пересечение жанров даёт
                    // самых близких кандидатов, а по одному тегу их могло не хватить.
                    if (tags.size > 1) add(CatalogFilter(tags = tags.toSet(), sort = CatalogSort.POPULAR))
                }
            }
            queries.flatMap { filter ->
                (0 until SIMILAR_POOL_PAGES).map { page ->
                    async { attempt { animeFilterPage(filter, sectionKey = null, page = page) } ?: emptyList() }
                }
            }.map { it.await() }.forEach { batch -> batch.forEach { out.putIfAbsent(it.id, it) } }
        }
        // Похожее — чтобы смотреть; анонс смотреть нельзя.
        return out.values.filterNot { com.aniblaze.aggregator.model.isUnreleased(it) }
    }

    /**
     * Пул кандидатов для экрана «Рекомендации».
     *
     * Здесь, в отличие от [similarPool], жанр заранее неизвестен — вкус выводится из
     * истории уже ПОСЛЕ загрузки, — поэтому берётся широкий срез каталога: популярное
     * за всё время (глубина каталога), тренды и последние поступления (свежесть),
     * онгоинги. Ранжирует его [Recommender.recommend]; наше дело — принести побольше
     * РАЗНЫХ карточек с заполненными жанрами.
     *
     * Запросы придушены семафором по той же причине, что и секции главной: пятнадцать
     * одновременных обращений к одному API отбирают канал друг у друга и половина
     * возвращается пустой.
     */
    /**
     * Кандидаты для «Рекомендаций».
     *
     * [round] — какой заход. Нулевой берёт первые страницы каждой ленты, первый —
     * следующие, и так далее: экран догружает пул, пока человек листает вниз. Без
     * этого пул был один и конечный, и подборка упиралась в потолок на первом же
     * экране прокрутки.
     *
     * «Сейчас выходит» запрашивается только в нулевом заходе: это не лента со
     * страницами, а один фиксированный список, и на втором круге он вернул бы то же
     * самое.
     */
    suspend fun recommendationPool(
        genres: List<String> = emptyList(),
        round: Int = 0,
    ): List<Anime> = cachedFeed("recommend:${genres.sorted()}:$round", 10 * 60_000L) {
        loadRecommendationPool(genres, round)
    }

    private suspend fun loadRecommendationPool(genres: List<String>, round: Int): List<Anime> = withContext(Dispatchers.IO) {
        val gate = kotlinx.coroutines.sync.Semaphore(RECOMMEND_POOL_PARALLEL)
        val out = LinkedHashMap<String, Anime>()
        val firstPage = round * RECOMMEND_POOL_PAGES
        coroutineScope {
            val jobs = buildList {
                repeat(RECOMMEND_POOL_PAGES) { offset ->
                    val page = firstPage + offset
                    add(async { gate.withPermit { attempt { catalogPage(4, page) } ?: emptyList() } })
                    add(async { gate.withPermit { attempt { catalogPage(3, page) } ?: emptyList() } })
                }
                if (round == 0) {
                    add(async { gate.withPermit { attempt { catalog("ongoing") } ?: emptyList() } })
                }
                repeat(2) { offset ->
                    val page = round * 2 + offset
                    add(async { gate.withPermit { attempt { homeSection("recent", page) } ?: emptyList() } })
                }
                // Любимые жанры зрителя — отдельными запросами: без них каталог отдаёт
                // только «самое популярное вообще», и редкий вкус (спорт, детектив)
                // в пул просто не попадал.
                genres.take(RECOMMEND_POOL_GENRES).forEach { key ->
                    val filter = CatalogFilter(tags = setOf(key), sort = CatalogSort.POPULAR)
                    add(async { gate.withPermit { attempt { animeFilterPage(filter, null, round) } ?: emptyList() } })
                }
            }
            jobs.map { it.await() }.forEach { batch -> batch.forEach { out.putIfAbsent(it.id, it) } }
        }
        dedupeTitles(out.values.toList())
    }

    /**
     * Airing info for the episode list: per-episode dates, release status and when
     * the next episode is due.
     *
     * Anime only: cinema segments already carry their own [Segment.releaseDate] from
     * TMDB. Resolved separately from [segments] so a slow/absent lookup never delays
     * the episode list — it just fills in a moment later.
     */
    suspend fun titleSchedule(anime: Anime, refresh: Boolean = false): com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule =
        withContext(Dispatchers.IO) {
            if (isCinema(anime.id)) {
                return@withContext com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule.EMPTY
            }
            // Anixart keeps the original (romaji) name in `status` — the fallback key
            // when Shikimori's Russian naming differs from the catalog's.
            val altTitle = if (anime.id.startsWith("ax:")) anime.status else ""
            attempt { airDates.forTitle(anime.title, altTitle, malIdHint = malIdOf(anime), refresh = refresh) }
                ?: com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule.EMPTY
        }

    /**
     * MAL id карточки: свой, если источник дал; для Yummy-карточки без него —
     * из полной записи `/anime/{id}` (там `remote_ids`). Иначе 0 — поиск по названию.
     */
    private suspend fun malIdOf(anime: Anime): Int {
        if (anime.malId > 0) return anime.malId
        val yummy = aggregators.filterIsInstance<com.aniblaze.aggregator.source.YummyAnimeSource>().firstOrNull()
            ?: return 0
        if (!yummy.ownsContentId(anime.id)) return 0
        return attempt { yummy.details(anime.id) }?.malId ?: 0
    }

    /** Итог загрузки серий: пусто-но-успешно и «не смогли» — разные вещи. */
    data class SegmentsResult(val segments: List<Segment>, val failed: Boolean) {
        companion object { val EMPTY = SegmentsResult(emptyList(), failed = false) }
    }

    suspend fun segments(contentId: String): List<Segment> = segmentsDetailed(contentId).segments

    /**
     * Серии + честный признак сбоя. Раньше упавший источник и источник без серий
     * давали одинаковый пустой список, и экран писал «Серии пока недоступны» —
     * то есть обвинял тайтл в том, что не ответила сеть.
     */
    suspend fun segmentsDetailed(contentId: String, title: String? = null, year: Int = 0): SegmentsResult = withContext(Dispatchers.IO) {
        if (isCinema(contentId)) {
            return@withContext runCatching { cinemaSourceFor(contentId).getContentSegments(contentId) }
                .fold(
                    onSuccess = { SegmentsResult(it, failed = false) },
                    onFailure = { SegmentsResult(emptyList(), failed = it !is CancellationException) },
                )
        }
        var anyFailure = false
        // Только владелец понимает формат id — и владелец спрашивается ВСЕГДА, даже
        // если сейчас выключен: карточка в избранном сохранена под его id, и
        // «Серии пока недоступны» у неё появлялось ровно от того, что источник
        // сняли с галочки, а не от того, что серий нет.
        val owners = directContentSources(aggregators, contentId)
        for (src in owners) {
            val segs = try {
                src.getContentSegments(contentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                anyFailure = true
                com.aniblaze.desktop.player.PlayerDiagnostics.failure("segments.${src.name}", error)
                emptyList()
            }
            if (segs.isNotEmpty()) return@withContext SegmentsResult(segs, failed = false)
        }
        // У владельца серий нет (анонс у Anixart, тайтл ещё не залит) — их может
        // знать другой включённый источник. Ищем по названию, как делает и поток
        // (resolveStreamRaw → byTitle): плеер потом так же найдёт видео.
        if (!title.isNullOrBlank()) {
            for (src in active()) {
                if (src.name in METADATA_ONLY_SOURCES || src in owners) continue
                val hits = attempt { src.search(title) }.orEmpty()
                for (hit in hits.take(6)) {
                    if (!titleMatches(title, hit.title)) continue
                    if (year > 0 && hit.year > 0 && hit.year != year) continue
                    val segs = attempt { src.getContentSegments(hit.id) }.orEmpty()
                    if (segs.isNotEmpty()) {
                        com.aniblaze.desktop.player.PlayerDiagnostics.log(
                            "segments.byTitle",
                            "source=${src.name}; title=$title; id=${hit.id}; count=${segs.size}",
                        )
                        // contentId остаётся ЗАПРОШЕННЫМ: прогресс и история живут под ним.
                        return@withContext SegmentsResult(segs.map { it.copy(contentId = contentId) }, failed = false)
                    }
                }
            }
        }
        SegmentsResult(emptyList(), failed = anyFailure)
    }

    /** Exact cinema schedule from TMDB, or the next weekly Anixart broadcast day. */
    suspend fun nextEpisodeSchedule(anime: Anime): EpisodeSchedule? = withContext(Dispatchers.IO) {
        if (isCinema(anime.id)) {
            return@withContext attempt { cinemaSourceFor(anime.id).nextEpisodeSchedule(anime.id) }
        }
        var weekday = anime.broadcast
        if (weekday !in 1..7) {
            for (src in active()) {
                val fresh = attempt { src.search(anime.title) }.orEmpty()
                    .firstOrNull { titleMatches(anime.title, it.title) && it.broadcast in 1..7 }
                if (fresh != null) { weekday = fresh.broadcast; break }
            }
        }
        if (weekday !in 1..7) return@withContext null
        val today = java.time.LocalDate.now()
        val days = (weekday - today.dayOfWeek.value + 7) % 7
        EpisodeSchedule(today.plusDays(days.toLong()).toString())
    }

    /** Resolves a playable stream. Cinema → Lordfilm/Zetflix (m3u8 for VLC); anime →
     *  race the enabled sources, first reachable hit wins in priority order. */
    /**
     * Sources offerable in the player's «Источник» picker.
     *
     * Only ones that can actually return a stream. Shikimori is metadata-only —
     * its extractContent() returns null by construction — so listing it gave the
     * user a menu entry that could never work, and picking it read as a bug.
     */
    fun animeSources(): List<String> =
        aggregators.filter { it.name !in METADATA_ONLY_SOURCES }.map { it.name }

    /** Fetch exact opening bounds from AniLiberty independently of the source that
     * supplies the video. Most playback currently wins through Anixart/Kodik, whose
     * responses contain no opening timestamps. */
    suspend fun exactTimings(
        title: String,
        segment: Int,
        year: Int = 0,
        altTitle: String = "",
    ): com.aniblaze.aggregator.source.AniskipTimings.SkipTimings = withContext(Dispatchers.IO) {
        val empty = com.aniblaze.aggregator.source.AniskipTimings.SkipTimings.EMPTY
        if (title.isBlank() || segment <= 0) return@withContext empty
        val started = System.currentTimeMillis()
        // ПАРАЛЛЕЛЬНО, а не по очереди. Раньше сначала полностью отрабатывала ветка
        // AniLiberty — а она делает поиск плюс до 8 сетевых попыток подряд, и когда у
        // тайтла там 0 серий (частый случай), это десятки секунд ДО того, как вообще
        // спросят AniSkip. Кнопка появлялась так поздно, что читалась как «её нет».
        // Ветка AniLiberty вдобавок ограничена по времени: она лишь уточняет опенинг.
        coroutineScope {
            val libriaJob = async {
                val src = aggregators.firstOrNull { it.name.contains("libria", ignoreCase = true) }
                    ?: return@async null
                withTimeoutOrNull(LIBRIA_TIMING_TIMEOUT_MS) {
                    runCatching {
                        resolveViaSource(src, contentId = "", title = title, segment = segment, expectedYear = year)?.opening
                    }.getOrNull()
                }
            }
            val aniskipJob = async {
                try { aniskip.timings(title, segment, altTitle) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { empty.copy(retryable = true) }
            }
            val fromLibria = libriaJob.await()
            val fromAniskip = aniskipJob.await()
            val result = com.aniblaze.aggregator.source.AniskipTimings.SkipTimings(
                opening = fromLibria ?: fromAniskip.opening,
                ending = fromAniskip.ending,
                retryable = fromAniskip.retryable,
            )
            com.aniblaze.desktop.player.PlayerDiagnostics.log(
                "timings.resolved",
                "title=${title.take(40)}; alt=${altTitle.take(40)}; ep=$segment; " +
                    "libria=${fromLibria != null}; aniskipOp=${fromAniskip.opening != null}; " +
                    "aniskipEd=${fromAniskip.ending != null}; ms=${System.currentTimeMillis() - started}",
            )
            result
        }
    }

    /**
     * Ссылка на серию — уже ПРОВЕРЕННАЯ.
     *
     * Ссылки solodcdn (куда ведёт цепочка Anixart → Kodik) живут недолго и потом
     * отвечают 404 — замерено: все 22 адреса одного сеанса, включая тот, что за три
     * минуты до этого играл. Плеер узнавал об этом единственным способом: отдать
     * адрес libVLC и ждать девять секунд, пока не сработает «поток не стартовал».
     * На каждую озвучку и каждое качество — свои девять секунд, итого около минуты
     * замершего кадра, и со стороны это ровно «программа зависла».
     *
     * Двухбайтовый запрос отвечает за 70 мс и отличает «мертво» от «работает» до
     * того, как об этом узнает плеер.
     */
    suspend fun resolveStream(contentId: String, segment: Int, title: String? = null, source: String? = null, expectedYear: Int = 0): ContentResult? {
        val resolved = resolveStreamRaw(contentId, segment, title, source, expectedYear) ?: return null
        if (isCinema(contentId)) return resolved // у кино ссылки проверяет сам источник
        return withLiveVariantFirst(resolved)
    }

    /**
     * Ставит первым тот вариант, который реально отвечает.
     *
     * Возвращает null, только когда КАЖДЫЙ проверенный адрес ответил определённой
     * ошибкой: тогда звать плеер незачем, и вызывающий сразу идёт к следующей
     * озвучке или источнику. Молчание сети (таймаут, обрыв) за приговор не считается
     * — иначе плохая секунда связи выглядела бы как мёртвый источник.
     */
    private suspend fun withLiveVariantFirst(resolved: ContentResult): ContentResult? = coroutineScope {
        val http = this@DesktopRepository.http ?: return@coroutineScope resolved
        val variants = resolved.variants?.takeIf { it.isNotEmpty() }
            ?: listOf(StreamVariant(resolved.quality.ifBlank { "Auto" }, resolved.location))
        val referer = resolved.referer?.takeIf { it.isNotBlank() }
        // ВСЕ варианты спрашиваются РАЗОМ, а не по очереди.
        //
        // Раньше молчание первого адреса заканчивало проверку немедленно («судить не
        // по чему, отдаём как есть»), и живые адреса за ним не спрашивались вовсе.
        // Ровно это и вышло 18.08: `stream.probe | index=0; code=none; verdict=unknown`
        // — одиннадцать секунд ожидания, после которых плееру отдали адрес мёртвого
        // solodcdn, хотя в том же списке лежала работающая AniLibria. Одновременный
        // опрос стоит ОДНОГО таймаута вместо четырёх и не даёт первому молчуну
        // спрятать остальных.
        val codes = variants.take(MAX_PROBED_VARIANTS)
            .map { variant ->
                async { if (variant.url.isBlank()) null else http.probe(variant.url, referer = referer) }
            }
            .map { it.await() }
        // Мёртвым считается ТОЛЬКО «такого файла нет». Всё остальное — 403 из-за
        // заголовков, 5xx, диковинный код — отдаём плееру: он ходит со своими
        // заголовками и может справиться там, где не справился этот запрос. Ошибиться
        // в эту сторону дёшево (девять секунд), в обратную — значит отказаться играть
        // работающую серию. Молчание (null) приговором тоже не считается.
        codes.forEachIndexed { index, code ->
            val verdict = when {
                code == null -> "unknown"
                code in DEAD_STREAM_CODES -> "dead"
                else -> "alive"
            }
            com.aniblaze.desktop.player.PlayerDiagnostics.log(
                "stream.probe",
                "index=$index; code=${code ?: "none"}; verdict=$verdict",
            )
        }
        val aliveIndex = codes.indexOfFirst { it != null && it !in DEAD_STREAM_CODES }
        if (aliveIndex >= 0) {
            if (aliveIndex == 0) return@coroutineScope resolved
            // Живой вариант — вперёд, мёртвые до него в хвост: качество ниже лучше,
            // чем девять секунд ожидания на каждом мёртвом адресе.
            val reordered = listOf(variants[aliveIndex]) + variants.filterIndexed { i, _ -> i != aliveIndex }
            return@coroutineScope resolved.copy(
                location = variants[aliveIndex].url,
                quality = variants[aliveIndex].quality,
                variants = reordered,
            )
        }
        if (codes.size < variants.size || codes.isEmpty() ||
            codes.any { it == null || it !in DEAD_STREAM_CODES }) return@coroutineScope resolved
        com.aniblaze.desktop.player.PlayerDiagnostics.log("stream.probe.allDead", "variants=${variants.size}; source=${resolved.source}")
        null
    }

    private suspend fun resolveStreamRaw(contentId: String, segment: Int, title: String?, source: String?, expectedYear: Int): ContentResult? = withContext(Dispatchers.IO) {
        if (isCinema(contentId)) {
            // When the Lampa plugin is enabled, resolve cinema through its balancers
            // (studio dubs, OK.ru streams); fall back to the site's own player.
            lampaResolve(contentId)?.let { return@withContext it }
            return@withContext attempt { cinemaSourceFor(contentId).extractContent(contentId, segment) }
        }
        // Balancer engine (Settings → "Движок"): play the balancer's own CDN stream
        // instead of the catalog source's. Falls through to the normal path when the
        // balancer has nothing, so switching engines can never leave you without video.
        if (settings.state.value.playbackEngine == "balancer" && !title.isNullOrBlank()) {
            attempt { balancer.resolve(title, segment) }?.let { return@withContext it }
        }
        // Forced source (player picker): try to resolve through it — directly if it
        // owns the id, else by searching its own catalog for the title. If it has
        // nothing (title absent, or only an unreleased season), fall through to the
        // race so playback still works instead of a black screen. The year gate keeps
        // a fuzzy cross-source search from silently playing a different season.
        if (!source.isNullOrBlank()) {
            val src = aggregators.firstOrNull { it.name == source }
            if (src != null) resolveViaSource(src, contentId, title, segment, expectedYear)?.let { return@withContext it }
        }
        val sources = if (source.isNullOrBlank()) {
            active()
        } else {
            // The forced source just failed. Selecting a source persists it as the
            // primary source, so active() now holds ONLY the source that already
            // failed — race the rest instead, otherwise this fallback is dead code.
            active().filter { it.name != source }.ifEmpty { aggregators.filter { it.name != source } }
        }
        val directSources = directContentSources(sources, contentId)
        val results = Channel<ContentResult?>(maxOf(1, directSources.size))
        val jobs = directSources.map { src ->
            resolverScope.launch {
                val value = try {
                    src.extractContent(contentId, segment).also { SourceHealth.record(src.name, ok = it != null, error = if (it == null) "нет потока" else "") }
                } catch (_: CancellationException) {
                    null
                } catch (error: Exception) {
                    SourceHealth.record(src.name, ok = false, error = error.message.orEmpty())
                    null
                }
                results.trySend(value)
            }
        }
        val raced = try {
            withTimeoutOrNull(20_000) {
                repeat(directSources.size) {
                    results.receive()?.let { return@withTimeoutOrNull it }
                }
                null
            }
        } finally {
            jobs.forEach { it.cancel() }
            results.cancel()
        }
        if (raced != null) return@withContext raced
        // Гонка спрашивает источники ПО ИДЕНТИФИКАТОРУ, а чужой id знает только тот,
        // кто его выдал: остальные молча возвращают null, даже если тот же эпизод у
        // них есть. Пока хоть кто-то отвечал, это было незаметно; «серия недоступна
        // ни в одной озвучке» — это ровно тот случай, когда искать по названию уже
        // пора. Шаг чисто дополнительный: он выполняется только там, где раньше
        // возвращался null, и ничего не замедляет в обычной жизни.
        if (title.isNullOrBlank()) return@withContext null
        // Поиск по названию — по ВСЕМ источникам, а не только активным: при выбранном
        // основном источнике active() — это он один, и запасной ход искал серию там,
        // где её только что не нашли. Сначала активные, затем остальные.
        val byTitleSources = (sources + aggregators.filter { it !in sources }).distinctBy { it.name }
        for (src in byTitleSources) {
            if (src.name in METADATA_ONLY_SOURCES) continue
            val byTitle = attempt { resolveViaSource(src, contentId, title, segment, expectedYear) }
            if (byTitle != null) {
                com.aniblaze.desktop.player.PlayerDiagnostics.log(
                    "stream.byTitle",
                    "source=${src.name}; title=$title; segment=$segment",
                )
                return@withContext byTitle
            }
        }
        null
    }

    private suspend fun resolveViaSource(
        src: ContentAggregator,
        contentId: String,
        title: String?,
        segment: Int,
        expectedYear: Int = 0,
    ): ContentResult? {
        // Direct hit only when this source owns the id. A foreign id must never be
        // interpolated into another source's endpoint.
        if (src.ownsContentId(contentId)) {
            attempt { src.extractContent(contentId, segment) }?.let { return it }
        }
        if (title.isNullOrBlank()) return null
        // Search its own catalog. Sources split a franchise into per-season releases,
        // and a not-yet-aired season lists zero episodes — so the first hit is often
        // empty. Try each hit that actually MATCHES the title until one yields the
        // requested episode. The title gate stops a fuzzy search (e.g. AniLibria's)
        // from silently playing an unrelated anime when it lacks the real one.
        val hits = attempt { src.search(title) } ?: emptyList()
        for (hit in hits.take(8)) {
            if (!titleMatches(title, hit.title)) continue
            if (expectedYear > 0 && hit.year > 0 && hit.year != expectedYear) continue
            attempt { src.extractContent(hit.id, segment) }?.let { return it }
        }
        return null
    }

    /** Loose title equivalence for cross-source matching: same significant words,
     *  ignoring case, punctuation and season markers (digits / "сезон" / "часть"). */
    private fun titleMatches(a: String, b: String): Boolean {
        val stop = setOf("сезон", "часть", "the", "tv", "ova", "ona", "season", "part", "и", "the")
        fun tokens(s: String) = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ')
            .filter { it.length > 2 && it !in stop && it.toIntOrNull() == null && !it.matches(Regex("[ivxl]+")) }
            .toSet()
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val common = ta.count { it in tb }
        // Subset (one title is the other + a season suffix) or a strong majority overlap.
        return common == minOf(ta.size, tb.size) || common >= kotlin.math.max(2, (minOf(ta.size, tb.size) * 0.6).toInt())
    }

    /**
     * Опрос всех включённых источников с ОТЛИЧИЕМ ОШИБКИ ОТ ПУСТОТЫ.
     *
     * Здесь стоял `attempt { call(src) } ?: emptyList()`, и это делало сбой
     * неотличимым от честно пустой категории: исключение превращалось в пустой
     * список, экран получал `Result.success(emptyList())` и рисовал прочерк. Ровно
     * так «Последние поступления» выглядели как «в разделе ничего нет», хотя на деле
     * источник не ответил — и кнопки «Повторить» пользователь не видел, потому что
     * ряд не считался упавшим.
     *
     * Правило: пусто И БЫЛА ОШИБКА — значит сбой, бросаем дальше, ряд покажет
     * «не удалось загрузить» с повтором. Пусто и ошибок не было — категория
     * действительно пуста, так и покажем.
     */
    private suspend fun mergeAll(call: suspend (ContentAggregator) -> List<Anime>): List<Anime> = withContext(Dispatchers.IO) {
        coroutineScope {
            val outcomes = active().map { src ->
                async {
                    try {
                        Result.success(call(src)).also { SourceHealth.record(src.name, ok = true) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        SourceHealth.record(src.name, ok = false, error = error.message.orEmpty())
                        Result.failure<List<Anime>>(error)
                    }
                }
            }.map { it.await() }
            val raw = outcomes.mapNotNull { it.getOrNull() }.flatten()
            releaseTracker.observe(raw)
            val merged = dedupeTitles(raw)
            if (merged.isEmpty()) {
                outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
            }
            merged
        }
    }

    /**
     * Схлопывает один и тот же тайтл, пришедший из разных источников. distinctBy{id}
     * этого не делал: у Anixart id вида "ax:11", у AniLibria — alias, так что одно
     * аниме показывалось двумя карточками во всех лентах и в поиске.
     *
     * Ключ — нормализованное название + год, но год участвует ТОЛЬКО когда известен
     * с обеих сторон: у AniLibria year часто 0, и жёсткий ключ "название#год" тогда
     * разваливался. Первый победивший источник задаёт карточку — порядок aggregators
     * это приоритет, так что выигрывает более полный Anixart.
     */
    private fun dedupeTitles(all: List<Anime>): List<Anime> {
        val out = LinkedHashMap<String, Anime>()
        val yearByKey = HashMap<String, Int>()
        val known = knownIds()
        // Сначала — к известному id (см. AppSettings.canonical): тогда карточка из
        // любого источника несёт «остановились на…» и звезду избранного, даже если
        // источник, под чьим id всё сохранено, сейчас выключен.
        for (a in all.map(settings::canonical)) {
            val key = normTitle(a.title)
            if (key.isBlank()) { out.putIfAbsent(a.id, a); continue }
            val seenYear = yearByKey[key]
            // Разные годы у обоих известны → это разные вещи (сезоны/ремейки).
            val distinct = seenYear != null && seenYear > 0 && a.year > 0 && seenYear != a.year
            val slot = if (distinct) "$key#${a.year}" else key
            val prev = out[slot]
            // Из дублей побеждает тот id, под которым тайтл УЖЕ ЛЕЖИТ у пользователя —
            // в истории, избранном, прогрессе. Иначе тот же «Наруто», пришедший из
            // YummyAnime как `ya:111`, терял плашку «остановились на…», которая
            // записана под `ax:609`: карточка новая — след старый.
            if (prev == null || (prev.id !in known && a.id in known)) {
                out[slot] = a
                if (a.year > 0) yearByKey[slot] = a.year
            }
        }
        return out.values.toList()
    }

    /** Идентификаторы, под которыми у пользователя есть след: история, избранное,
     *  прогресс, оценки. Дёшево: читается из уже загруженного состояния. */
    private fun knownIds(): Set<String> {
        val st = settings.state.value
        return buildSet {
            st.history.forEach { add(it.id) }
            st.favorites.forEach { add(it.id) }
            st.progress.forEach { add(it.anime.id) }
            st.ratings.forEach { add(it.anime.id) }
        }
    }

    // --- Cinema — separate from the anime source toggles, always available. The
    //     active source is chosen in Settings; switching restarts the app. ---

    /** One page from the active cinema source, retried a couple of times to ride
     *  out a transient network hiccup (plain markup, so one retry is plenty). */
    private suspend fun browsePage(path: String, page: Int, cartoons: Boolean = false): List<Anime> {
        // "tmdb!" rows always resolve through the TMDB source (see cinemaSpecialRows),
        // so the special rows work — and paginate — on top of any chosen site source.
        val viaTmdb = path.startsWith(TMDB_ROW)
        val source = if (viaTmdb) tmdbCinema() else cinemaFor(cartoons)
        val realPath = if (viaTmdb) path.removePrefix(TMDB_ROW) else path
        repeat(3) { attempt ->
            val res = attempt { source.browsePath(realPath, page) } ?: emptyList()
            if (res.isNotEmpty()) return res
            kotlinx.coroutines.delay(300L * (attempt + 1))
        }
        return emptyList()
    }

    /**
     * Fetches [count] catalog pages of the active source's listing [path] ("" for
     * the newest feed, or a genre slug) and merges them in order. Stops once a page
     * yields nothing new (end of catalog, or a non-paginating source).
     */
    suspend fun cinemaBrowseBatch(path: String, startPage: Int, count: Int, cartoons: Boolean = false): List<Anime> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Anime>()
        for (p in startPage until startPage + count) {
            val page = browsePage(path, p, cartoons)
            var added = 0
            page.forEach { if (out.putIfAbsent(it.id, it) == null) added++ }
            if (page.isNotEmpty() && added == 0) break // same items repeating = past the end
            kotlinx.coroutines.delay(80)
        }
        out.values.toList()
    }

    suspend fun cinemaSearch(query: String, cartoons: Boolean = false): List<Anime> = withContext(Dispatchers.IO) {
        attempt { cinemaFor(cartoons).searchCinema(query) } ?: emptyList()
    }

    /** The balancer's clean embed-player URL for a cinema title — opened in the
     *  browser so the site's own player (dubs/quality/fullscreen) is used, without
     *  the surrounding site page. Routed to the source that owns the id. */
    suspend fun cinemaEmbedUrl(contentId: String): String? = withContext(Dispatchers.IO) {
        val url = contentId.substringBefore(":t")
        attempt { cinemaSourceFor(url).balancerEmbedFor(url) }
    }

    fun isCinema(contentId: String): Boolean = cinemaSources.any { it.owns(contentId) }

    /** Optional warm-up follows the opened title, independently of the selected catalog. */
    suspend fun prepareCinemaPlayback(contentId: String) = withContext(Dispatchers.IO) {
        (cinemaSources.firstOrNull { it.owns(contentId) } as? com.aniblaze.aggregator.source.LampaCatalogSource)
            ?.preparePlayback(contentId)
        Unit
    }

    /** Resolve a cinema title through the enabled Lampa plugin balancer: find its
     *  KinoPoisk id (from the site's detail page), run the balancer, and return the
     *  dubs as [Translation]s (each a separate stream). Null → not enabled / no id /
     *  balancer found nothing, so the caller falls back to the site's own player. */
    private suspend fun lampaResolve(contentId: String): ContentResult? {
        val ex = lampa ?: return null
        val s = settings.state.value
        if (!s.lampaEnabled || s.lampaPluginUrl.isBlank()) return null
        val url = contentId.substringBefore(":t")
        val tIndex = contentId.substringAfter(":t", "").toIntOrNull() ?: 0
        val kp = attempt { cinemaSourceFor(url).kinopoiskId(url) } ?: return null
        val movie = mapOf<String, Any?>("kinopoisk_id" to kp, "id" to kp, "imdb_id" to "", "title" to "")
        val streams = attempt { ex.resolve(s.lampaPluginUrl, s.lampaBalancer, movie) } ?: emptyList()
        if (streams.isEmpty()) return null
        val idx = tIndex.coerceIn(0, streams.lastIndex)
        val chosen = streams[idx]
        val translations = streams.mapIndexed { i, st -> Translation(i, st.title.ifBlank { "Озвучка ${i + 1}" }) }
        val variants = chosen.qualities.map { StreamVariant(it.first, it.second) }
            .ifEmpty { listOf(StreamVariant("Auto", chosen.url)) }
        return ContentResult(
            location = variants.first().url,
            quality = variants.first().quality,
            source = "Lampa/${s.lampaBalancer}",
            referer = null,
            variants = variants,
            translations = translations.takeIf { it.size > 1 },
            translationId = idx,
        )
    }

}

/** Единственная точка отбора источников для прямого запроса по стабильному id. */
internal fun directContentSources(
    sources: List<ContentAggregator>,
    contentId: String,
): List<ContentAggregator> = sources.filter { it.ownsContentId(contentId) }

/**
 * Порядок по выбранной сортировке — для лент, которые сервер отсортировать не может
 * (секции из расписаний и подборок, источники без своего фильтра).
 *
 * «По популярности» порядок НЕ трогает: это умолчание, и у категории он свой,
 * осмысленный (сегодняшние серии сверху и т.п.). Оценка сравнивается в долях своей
 * шкалы — 7.4 из 10 и 4.6 из 5 иначе перепутались бы. «По новизне» и «по дате
 * выхода» на клиенте различить нечем: точной даты у карточек нет, только год.
 */
internal fun sortClientSide(items: List<Anime>, sort: CatalogSort): List<Anime> = when (sort) {
    CatalogSort.POPULAR -> items
    CatalogSort.RATING -> items.sortedByDescending { if (it.ratingMax > 0) it.rating / it.ratingMax else 0.0 }
    CatalogSort.VIEWS -> items.sortedByDescending { maxOf(it.watchingCount, it.favoritesCount, it.ratingVotes) }
    CatalogSort.FRESH, CatalogSort.RELEASE_DATE -> items.sortedByDescending { it.year }
}

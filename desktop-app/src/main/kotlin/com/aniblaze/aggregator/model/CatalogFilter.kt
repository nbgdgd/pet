package com.aniblaze.aggregator.model

/**
 * Фильтр каталога — один набор условий на все разделы сразу.
 *
 * Раздел выбирает не СВОИ поля, а СВОИ ЗНАЧЕНИЯ: у аниме есть «количество серий» и
 * жанр «исэкай», у кино — возрастной сертификат и «сериал/фильм». Что раздел
 * действительно умеет, описывает [FilterFacets]; сюда попадает только выбранное.
 *
 * Каждый источник старается отдать фильтрацию своему API, но полагаться на это
 * нельзя: у «Сейчас смотрят» лента строится из расписания, а AniLibria и AnimeOn
 * фильтров не умеют вовсе. Поэтому есть [matches] — та же проверка на нашей стороне,
 * которой прогоняется ВСЁ, что вернул источник. Так любое сочетание фильтров даёт
 * честный результат, даже когда сервер о половине условий не слышал.
 */
data class CatalogFilter(
    /** Канонические ключи тегов (см. [CatalogTag.key]). Между собой — И. */
    val tags: Set<String> = emptySet(),
    val yearFrom: Int = 0,
    val yearTo: Int = 0,
    val status: TitleStatus? = null,
    val ageRating: AgeRating? = null,
    /** Страна как её называет каталог («Япония», «Китай»); пусто = любая. */
    val country: String = "",
    val episodes: EpisodeRange? = null,
    val contentType: ContentType? = null,
    /** Нижняя граница оценки В ШКАЛЕ РАЗДЕЛА (у кино — из десяти). 0 = любая.
     *  Была отдельной кнопкой в «Кино» до общих фильтров; здесь, чтобы не пропала. */
    val minRating: Double = 0.0,
    /** Не показывать полностью просмотренное. Отсев на нашей стороне — сервер истории не знает. */
    val hideWatched: Boolean = false,
    /**
     * Студия озвучки ([DubStudio.key]); пусто = любая. Это НЕ свойство карточки — у
     * тайтла в модели нет списка озвучек, — поэтому [matches] его не проверяет:
     * список тайтлов студии целиком отдаёт источник (см. YummyAnimeSource.dubbingCatalog),
     * а остальные условия досеиваются уже по нему.
     */
    val dubbing: String = "",
    /**
     * Первоисточник ([SourceMaterial.key]); пусто = любой. Список тайтлов по нему
     * отдаёт AniList (см. AniListCatalog) — у каталогов такого фильтра нет.
     */
    val sourceMaterial: String = "",
    /**
     * «Непопулярные, но качественные»: высокая оценка при малой аудитории. Отсев на
     * нашей стороне по [hiddenGem].
     */
    val hiddenGems: Boolean = false,
    /** Главный герой ([Protagonist.key]); пусто = любой. Считается по тексту карточки. */
    val protagonist: String = "",
    val sort: CatalogSort = CatalogSort.POPULAR,
) {
    /** Пусто = «ничего не выбрано», лента показывается как обычно. Сортировка сюда
     *  НЕ входит: она есть всегда и не превращает раздел в «результаты поиска». */
    val isEmpty: Boolean
        get() = tags.isEmpty() && yearFrom == 0 && yearTo == 0 && status == null &&
            ageRating == null && country.isBlank() && episodes == null && contentType == null &&
            minRating <= 0.0 && !hideWatched && dubbing.isBlank() && sourceMaterial.isBlank() && !hiddenGems &&
            protagonist.isBlank()

    /**
     * Совсем нетронутый фильтр: ни условий, ни смены сортировки.
     *
     * Отличие от [isEmpty] принципиально, и на нём держится живость выпадашки
     * сортировки. Экраны решают «рисовать ряды или сетку результатов» — и раньше
     * решали по [isEmpty], который сортировку не учитывает. Выбор «По рейтингу» при
     * пустом фильтре не менял РОВНЫМ СЧЁТОМ НИЧЕГО: режим не переключался, запросы не
     * уходили — выпадашка выглядела сломанной. Теперь смена сортировки — тоже повод
     * показать сетку каталога, а [isEmpty] остаётся про «какие условия слать серверу».
     */
    val isDefault: Boolean
        get() = isEmpty && sort == CatalogSort.POPULAR

    val activeCount: Int
        get() = tags.size + (if (yearFrom > 0 || yearTo > 0) 1 else 0) + (if (status != null) 1 else 0) +
            (if (ageRating != null) 1 else 0) + (if (country.isNotBlank()) 1 else 0) +
            (if (episodes != null) 1 else 0) + (if (contentType != null) 1 else 0) +
            (if (minRating > 0.0) 1 else 0) + (if (hideWatched) 1 else 0) + (if (dubbing.isNotBlank()) 1 else 0) +
            (if (sourceMaterial.isNotBlank()) 1 else 0) + (if (hiddenGems) 1 else 0) + (if (protagonist.isNotBlank()) 1 else 0)

    /**
     * Подходит ли тайтл. Пустое условие пропускает всё; НЕИЗВЕСТНОЕ поле тоже
     * пропускает — иначе фильтр по году выкосил бы все тайтлы источника, который года
     * не отдаёт, и раздел выглядел бы сломанным вместо «источник не знает».
     */
    fun matches(anime: Anime, tagsOf: (Anime) -> Set<String>): Boolean {
        if (tags.isNotEmpty() && !tagsOf(anime).containsAll(tags)) return false
        if (yearFrom > 0 && anime.year > 0 && anime.year < yearFrom) return false
        if (yearTo > 0 && anime.year > 0 && anime.year > yearTo) return false
        if (status != null && !status.matches(anime)) return false
        if (contentType != null && !contentType.matches(anime)) return false
        if (country.isNotBlank() && anime.country.isNotBlank() &&
            !anime.country.contains(country, ignoreCase = true)
        ) {
            return false
        }
        if (episodes != null && anime.episodesTotal > 0 && !episodes.contains(anime.episodesTotal)) return false
        if (ageRating != null && anime.ageRating > 0 && anime.ageRating != ageRating.id) return false
        // Оценка сравнивается в шкале самого тайтла: 7.4 у фильма и 4.6 у аниме —
        // одно и то же место распределения, и общий порог их бы перепутал.
        if (minRating > 0.0 && anime.rating > 0.0 && anime.rating / anime.ratingMax < minRating / 10.0) return false
        // Первоисточник известен и не тот — мимо; неизвестный пропускается: список
        // по нему уже отобрал AniList, а карточка каталога поля не несёт.
        if (sourceMaterial.isNotBlank() && anime.sourceMaterial.isNotBlank() &&
            SourceMaterial.byKey(sourceMaterial)?.matches(anime.sourceMaterial) == false
        ) {
            return false
        }
        if (hiddenGems && !hiddenGem(anime)) return false
        // Герой — по тексту карточки; без описания судить не о чем, такой тайтл мимо.
        Protagonist.byKey(protagonist)?.let { if (!it.matches(anime)) return false }
        return true
    }

    /** Выбранное, разложенное на снимаемые по одному теги. */
    fun chips(): List<FilterChip> = buildList {
        tags.forEach { key ->
            val tag = CatalogTag.byKey(key) ?: return@forEach
            add(FilterChip(tag.label) { copy(tags = tags - key) })
        }
        if (yearFrom > 0 || yearTo > 0) {
            val label = when {
                yearFrom > 0 && yearTo > 0 && yearFrom == yearTo -> "$yearFrom год"
                yearFrom > 0 && yearTo > 0 -> "$yearFrom—$yearTo"
                yearFrom > 0 -> "с $yearFrom"
                else -> "до $yearTo"
            }
            add(FilterChip(label) { copy(yearFrom = 0, yearTo = 0) })
        }
        status?.let { add(FilterChip(it.label) { copy(status = null) }) }
        ageRating?.let { add(FilterChip(it.label) { copy(ageRating = null) }) }
        if (country.isNotBlank()) add(FilterChip(country) { copy(country = "") })
        episodes?.let { add(FilterChip(it.label) { copy(episodes = null) }) }
        contentType?.let { add(FilterChip(it.label) { copy(contentType = null) }) }
        if (minRating > 0.0) add(FilterChip("от ${"%.0f".format(minRating)}") { copy(minRating = 0.0) })
        if (hideWatched) add(FilterChip("Без просмотренного") { copy(hideWatched = false) })
        DubStudio.byKey(dubbing)?.let { add(FilterChip("Озвучка ${it.label}") { copy(dubbing = "") }) }
        SourceMaterial.byKey(sourceMaterial)?.let { add(FilterChip("По ${it.genitive}") { copy(sourceMaterial = "") }) }
        if (hiddenGems) add(FilterChip("Скрытые жемчужины") { copy(hiddenGems = false) })
        Protagonist.byKey(protagonist)?.let { add(FilterChip(it.label) { copy(protagonist = "") }) }
    }

    /** Сброс — но выбранная сортировка остаётся: её никто не «выбирал как фильтр». */
    fun cleared(): CatalogFilter = CatalogFilter(sort = sort)

    fun toggleTag(key: String): CatalogFilter =
        copy(tags = if (key in tags) tags - key else tags + key)
}

/** Один снимаемый тег над лентой. */
class FilterChip(val label: String, val remove: () -> CatalogFilter)

/**
 * Страница отфильтрованного каталога вместе с числом найденного.
 *
 * [total] честно бывает неизвестен: TMDB отдаёт точное `total_results`, а Anixart —
 * нет (его `total_count` равен размеру страницы, `total_page_count` всегда ноль).
 * Поэтому счётчик над лентой в одном разделе показывает точное число, а в другом —
 * «столько уже загружено и есть ещё». Врать одинаковым числом было бы хуже.
 */
data class CatalogPage(val items: List<Anime>, val total: Int = UNKNOWN_TOTAL) {
    companion object {
        const val UNKNOWN_TOTAL = -1
    }
}

/**
 * Сортировки. Общие для всех разделов — за каждой стоит то, что источник реально
 * умеет; там, где не умеет, раздел просто не покажет её в списке.
 */
enum class CatalogSort(val label: String) {
    POPULAR("По популярности"),
    RATING("По рейтингу"),
    FRESH("По новизне"),
    VIEWS("По просмотрам"),
    RELEASE_DATE("По дате выхода"),
}

/** Статус выхода. Числа — те же, что у Anixart ([Anime.airingStatus]). */
enum class TitleStatus(val label: String, val id: Int) {
    ONGOING("Выходит", 2),
    FINISHED("Завершено", 1),
    ANNOUNCED("Анонс", 3),
    ;

    /** Источник не сказал статуса — тайтл проходит: «не знаем» это не «не подходит». */
    fun matches(anime: Anime): Boolean = anime.airingStatus == 0 || anime.airingStatus == id
}

/**
 * Возрастной рейтинг. Значения — те, что реально лежат в каталоге Anixart (поле
 * `age_rating`, 1..5); ноль там значит «не проставлен» и в выбор не попадает.
 *
 * Подписи сверены по известным тайтлам: 1 — «Мой сосед Тоторо», 2 — «Покемон»,
 * 4 — «Наруто» и «Госпожа Кагуя», 5 — «Тетрадь смерти», «Берсерк», «Атака титанов».
 */
enum class AgeRating(val id: Int, val label: String, /** Сертификат MPAA для TMDB. */ val certification: String) {
    KIDS(1, "0+", "G"),
    SIX(2, "6+", "PG"),
    TWELVE(3, "12+", "PG-13"),
    SIXTEEN(4, "16+", "PG-13"),
    ADULT(5, "18+", "R"),
}

/** Сколько серий. Диапазоны — под то, как аниме реально издаётся. */
enum class EpisodeRange(val label: String, val from: Int, val to: Int) {
    MOVIE("1 серия", 1, 1),
    SHORT("2—12", 2, 12),
    /** «Не 12 серий»: всё, что длиннее одного стандартного сезона. */
    MORE_THAN_SEASON("13+", 13, 0),
    SEASON("13—26", 13, 26),
    LONG("27—99", 27, 99),
    ENDLESS("100+", 100, 0),
    ;

    fun contains(count: Int): Boolean = count >= from && (to == 0 || count <= to)
}

/**
 * Тип контента. Один список на всё приложение, но раздел показывает только свои
 * значения: у аниме это Сериал/Фильм/OVA/Спешл (Anixart `category_id` 1/2/3/6), у
 * кино — Фильм/Сериал.
 */
enum class ContentType(val label: String, /** Anixart category.id, 0 = нет такого. */ val anixartId: Int) {
    SERIES("Сериал", 1),
    MOVIE("Фильм", 2),
    OVA("OVA", 3),
    SPECIAL("Спешл", 6),
    ;

    fun matches(anime: Anime): Boolean {
        if (anime.contentType.isBlank()) return true
        return anime.contentType.equals(label, ignoreCase = true)
    }
}

/**
 * Тег каталога — то, что человек выбирает, и то, чем это оказывается у источника.
 *
 * Словарь Anixart снят с каталога, а не выдуман: 1800 карточек по шести сортировкам,
 * 83 разных жанра. Из списка, который просили, четырёх в нём просто НЕТ, и вместо
 * выдуманных кнопок здесь стоит ближайшее настоящее:
 *
 *  • «Магия» — 2 тайтла на весь каталог. Живое соседнее — «Городское фэнтези» (65)
 *    и «Махо-сёдзё» (16), плюс обычное «Фэнтези» (710).
 *  • «Демоны» — жанра нет; тайтлы про них лежат в «Сверхъестественном» (200) и
 *    «Мифологии» (72).
 *  • «Постапокалипсис» — жанра нет; ближайшее живое «Выживание» (33).
 *  • «Игровой мир» — называется «Видеоигры» (20).
 *
 * Число в скобках — сколько раз жанр встретился в выборке; по нему и выбрано, что
 * показывать сразу, а что прятать под «Ещё».
 */
enum class CatalogTag(
    val key: String,
    val label: String,
    /** Точное имя жанра в каталоге Anixart, или пусто — если такого там нет. */
    val anixart: String,
    /** Идентификатор жанра TMDB, или 0. */
    val tmdb: Int,
    /** Показывать сразу, не под кнопкой «Ещё». */
    val primary: Boolean = false,
) {
    ACTION("action", "Экшен", "экшен", 28, primary = true),
    FANTASY("fantasy", "Фэнтези", "фэнтези", 14, primary = true),
    COMEDY("comedy", "Комедия", "комедия", 35, primary = true),
    ADVENTURE("adventure", "Приключения", "приключения", 12, primary = true),
    DRAMA("drama", "Драма", "драма", 18, primary = true),
    ROMANCE("romance", "Романтика", "романтика", 10749, primary = true),
    SCHOOL("school", "Школа", "школа", 0, primary = true),
    SCIFI("scifi", "Научная фантастика", "фантастика", 878, primary = true),
    SUPERNATURAL("supernatural", "Сверхъестественное", "сверхъестественное", 0, primary = true),
    ISEKAI("isekai", "Исекай", "исэкай", 0, primary = true),
    HISTORY("history", "Историческое", "исторический", 36, primary = true),
    MYSTERY("mystery", "Тайна", "тайна", 9648, primary = true),
    THRILLER("thriller", "Триллер", "триллер", 53, primary = true),
    REINCARNATION("reincarnation", "Реинкарнация", "реинкарнация", 0, primary = true),
    WAR("war", "Военное", "военное", 10752, primary = true),
    PSYCHOLOGY("psychology", "Психология", "психологическое", 0, primary = true),
    HORROR("horror", "Ужасы", "ужасы", 27, primary = true),
    SPORT("sport", "Спорт", "спорт", 0, primary = true),
    MECHA("mecha", "Меха", "меха", 0, primary = true),
    DETECTIVE("detective", "Детектив", "детектив", 80, primary = true),

    // Дальше — под «Ещё»: реже встречаются или уточняют что-то из списка выше.
    URBAN_FANTASY("urban_fantasy", "Городское фэнтези", "городское фэнтези", 0),
    MAGICAL_GIRL("magical_girl", "Махо-сёдзё", "махо-сёдзё", 0),
    MYTHOLOGY("mythology", "Мифология", "мифология", 0),
    MARTIAL_ARTS("martial_arts", "Боевые искусства", "боевые искусства", 0),
    SUPERPOWER("superpower", "Супер сила", "супер сила", 0),
    SURVIVAL("survival", "Выживание", "выживание", 0),
    SLICE_OF_LIFE("slice_of_life", "Повседневность", "повседневность", 0),
    VAMPIRES("vampires", "Вампиры", "вампиры", 0),
    VIDEOGAMES("videogames", "Игровой мир", "видеоигры", 0),
    TIME_TRAVEL("time_travel", "Путешествие во времени", "путешествие во времени", 0),
    SPACE("space", "Космос", "космос", 0),
    SAMURAI("samurai", "Самураи", "самураи", 0),
    MUSIC("music", "Музыка", "музыка", 10402),
    CRIME("crime", "Криминал", "организованная преступность", 80),
    SHOUNEN("shounen", "Сёнен", "сёнен", 0),
    SEINEN("seinen", "Сэйнэн", "сэйнэн", 0),
    SHOUJO("shoujo", "Сёдзё", "сёдзё", 0),
    HAREM("harem", "Гарем", "гарем", 0),
    ECCHI("ecchi", "Этти", "этти", 0, primary = true),
    GOURMET("gourmet", "Гурман", "гурман", 0),
    KIDS("kids", "Детское", "детское", 0),
    FAMILY("family", "Семейное", "", 10751),
    ANIMATION("animation", "Мультфильм", "", 16),
    DOCUMENTARY("documentary", "Документальное", "", 99),
    WESTERN("western", "Вестерн", "", 37),
    ;

    companion object {
        private val BY_KEY = entries.associateBy { it.key }

        fun byKey(key: String): CatalogTag? = BY_KEY[key]

        /** Теги, которые может обслужить каталог аниме. */
        val ANIME: List<CatalogTag> = entries.filter { it.anixart.isNotBlank() }

        /** Теги, которые может обслужить TMDB (кино и мультфильмы). */
        val CINEMA: List<CatalogTag> = entries.filter { it.tmdb != 0 }

        /**
         * Теги тайтла — по строке жанров, которую отдал источник.
         *
         * Строка сравнивается по вхождению целого слова, а не по `contains`: «сёнен»
         * иначе цеплялся бы за «сёнен-ай», а «спорт» — за «спортивные единоборства».
         */
        fun of(anime: Anime): Set<String> {
            if (anime.genres.isBlank()) return emptySet()
            val parts = anime.genres.split(',', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return emptySet()
            val out = HashSet<String>()
            for (tag in entries) {
                if (tag.anixart.isNotBlank() && parts.contains(tag.anixart)) out.add(tag.key)
                // TMDB отдаёт жанры человеческими словами («Боевик», «Мультфильм»),
                // и они не совпадают с именами Anixart — поэтому подпись тоже сверяем.
                else if (parts.contains(tag.label.lowercase())) out.add(tag.key)
            }
            return out
        }
    }
}

/**
 * Что раздел реально умеет фильтровать. Пустой список = такого выбора у раздела нет,
 * и блок в панели не рисуется вовсе — «показывай только те фильтры, которые подходят
 * контенту этой категории» держится на этом, а не на догадках экрана.
 */
data class FilterFacets(
    val tags: List<CatalogTag>,
    val sorts: List<CatalogSort>,
    val years: List<IntRange> = emptyList(),
    val statuses: List<TitleStatus> = emptyList(),
    val ageRatings: List<AgeRating> = emptyList(),
    val countries: List<String> = emptyList(),
    val episodes: List<EpisodeRange> = emptyList(),
    val contentTypes: List<ContentType> = emptyList(),
    /** Пороги оценки в десятибалльной шкале; пусто = такого выбора у раздела нет. */
    val minRatings: List<Double> = emptyList(),
    /** Студии озвучки, по которым умеем фильтровать; пусто = блока «Озвучка» нет. */
    val dubbings: List<DubStudio> = emptyList(),
    /** Первоисточники; пусто = блока нет. */
    val sourceMaterials: List<SourceMaterial> = emptyList(),
    /** Показывать переключатель «Скрытые жемчужины». */
    val hiddenGems: Boolean = false,
    /** Профили главного героя; пусто = блока нет. */
    val protagonists: List<Protagonist> = emptyList(),
)

/**
 * Первоисточник аниме. `anilist` — значение `MediaSource` в AniList, единственном
 * источнике, который умеет фильтровать по нему; `names` — как это же поле пишут
 * каталоги (Yummy `original`), для отсева на нашей стороне.
 */
enum class SourceMaterial(val key: String, val label: String, val genitive: String, val anilist: String, val names: List<String>) {
    MANGA("manga", "Манга", "манге", "MANGA", listOf("манга", "manga", "веб-манга", "манхва", "маньхуа")),
    RANOBE("ranobe", "Ранобэ", "ранобэ", "LIGHT_NOVEL", listOf("ранобэ", "лайт-новел", "light novel", "новелла")),
    ORIGINAL("original", "Оригинал", "оригинальному сценарию", "ORIGINAL", listOf("оригинал", "original")),
    GAME("game", "Игра", "игре", "VIDEO_GAME", listOf("игра", "game", "визуальная новелла", "visual novel"));

    fun matches(catalogName: String): Boolean {
        val n = catalogName.lowercase().replace('ё', 'е')
        return names.any { n.contains(it) }
    }

    companion object {
        fun byKey(key: String): SourceMaterial? = entries.firstOrNull { it.key == key }
    }
}

/**
 * «Скрытая жемчужина»: оценка в верхней части шкалы при аудитории заметно ниже
 * обычной. Пороги аудитории — по замерам TitleRank: Yummy считает просмотры
 * (медиана ~55 тыс.), остальные — голоса/избранное (3000 — верхние ~10%).
 * Без оценки или без аудитории тайтл жемчужиной не считается: не о чем говорить.
 */
fun hiddenGem(anime: Anime): Boolean {
    if (anime.rating <= 0.0 || anime.ratingMax <= 0.0) return false
    val share = anime.rating / anime.ratingMax
    val audience = maxOf(anime.favoritesCount, anime.watchingCount, anime.ratingVotes)
    if (audience <= 0) return false
    val ceiling = if (anime.id.startsWith("ya:")) HIDDEN_GEM_VIEWS_MAX else HIDDEN_GEM_VOTES_MAX
    return share >= HIDDEN_GEM_MIN_SHARE && audience <= ceiling
}

/** Верхняя треть шкалы: 7.4 из 10, 3.7 из 5. */
const val HIDDEN_GEM_MIN_SHARE = 0.74
/** Yummy: просмотров меньше половины медианы каталога. */
const val HIDDEN_GEM_VIEWS_MAX = 25_000
/** Anixart и остальные: голосов/избранного меньше обычного. */
const val HIDDEN_GEM_VOTES_MAX = 1_200

/**
 * Студия озвучки как условие фильтра.
 *
 * Идентификаторы — страницы-каталоги студий на YummyAnime (`/catalog/dubbing/<id>`),
 * сняты с карточек тайтлов на сайте. У DEEP (РуАниме / DEEP; официальный дубляж
 * уровня лицензии — «Атака титанов» для Кинопоиска) их две: 17741 «DEEP» и 682
 * «РуАниме / DEEP» — одна студия в разные годы, берём обе. У больших студий
 * (AniDUB — почти 4000 тайтлов) берётся верх по популярности, см. DUBBING_MAX_PAGES.
 */
enum class DubStudio(val key: String, val label: String, val yummyIds: List<Int>) {
    DEEP("deep", "DEEP (лицензия)", listOf(17741, 682)),
    CRUNCHYROLL("crunchyroll", "Crunchyroll (лицензия)", listOf(5)),
    ANILIBRIA("anilibria", "AniLibria", listOf(84)),
    STUDIO_BAND("studioband", "Studio Band", listOf(491)),
    JAM("jam", "JAM", listOf(261)),
    ANIDUB("anidub", "AniDUB", listOf(60)),
    DREAM_CAST("dreamcast", "Dream Cast", listOf(201)),
    ANIMEVOST("animevost", "AnimeVost", listOf(127));

    companion object {
        fun byKey(key: String): DubStudio? = entries.firstOrNull { it.key == key }
    }
}

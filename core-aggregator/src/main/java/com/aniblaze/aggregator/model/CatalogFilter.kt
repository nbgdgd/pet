package com.aniblaze.aggregator.model

/**
 * Фильтр каталога аниме. Перенесено с настольной версии.
 *
 * Каждое условие источник старается выполнить у себя (Anixart умеет жанры, годы,
 * статус, возраст, число серий и тип), но полагаться на это нельзя: часть источников
 * фильтров не понимает вовсе. Поэтому есть [matches] — та же проверка на нашей
 * стороне, которой прогоняется ВСЁ, что вернул сервер.
 *
 * Ключевое правило проверки: НЕИЗВЕСТНОЕ поле пропускает. Если источник не отдал год,
 * тайтл не выкидывается фильтром по году — иначе лента выглядела бы сломанной там, где
 * на самом деле «источник не знает».
 *
 * ЕДИНСТВЕННОЕ исключение — жанры. Тайтл с пустой строкой жанров под выбранный жанр НЕ
 * подходит, и это не оплошность: выбрав «Экшен», человек ждёт экшен, а не вперемешку с
 * тем, про что источник промолчал. У года и числа серий цена ошибки обратная — там
 * «не знаю» стоит трактовать в пользу тайтла, потому что сам по себе год ничего не
 * обещает.
 */
data class CatalogFilter(
    /** Канонические ключи тегов (см. [CatalogTag.key]). Между собой — И. */
    val tags: Set<String> = emptySet(),
    val yearFrom: Int = 0,
    val yearTo: Int = 0,
    val status: TitleStatus? = null,
    val ageRating: AgeRating? = null,
    val episodes: EpisodeRange? = null,
    val contentType: ContentType? = null,
    /** Нижняя граница оценки в ДЕСЯТИБАЛЛЬНОЙ шкале. 0 = любая. */
    val minRating: Double = 0.0,
    val sort: CatalogSort = CatalogSort.POPULAR,
) {
    /** Пусто = «ничего не выбрано». Сортировка сюда НЕ входит: она есть всегда. */
    val isEmpty: Boolean
        get() = tags.isEmpty() && yearFrom == 0 && yearTo == 0 && status == null &&
            ageRating == null && episodes == null && contentType == null && minRating <= 0.0

    /** Сколько условий выбрано — число на кнопке «Фильтры». */
    val activeCount: Int
        get() = tags.size + (if (yearFrom > 0 || yearTo > 0) 1 else 0) + (if (status != null) 1 else 0) +
            (if (ageRating != null) 1 else 0) + (if (episodes != null) 1 else 0) +
            (if (contentType != null) 1 else 0) + (if (minRating > 0.0) 1 else 0)

    /** Подходит ли тайтл. */
    fun matches(anime: Anime): Boolean {
        if (tags.isNotEmpty() && !CatalogTag.of(anime).containsAll(tags)) return false
        if (yearFrom > 0 && anime.year > 0 && anime.year < yearFrom) return false
        if (yearTo > 0 && anime.year > 0 && anime.year > yearTo) return false
        if (status != null && !status.matches(anime)) return false
        if (contentType != null && !contentType.matches(anime)) return false
        if (episodes != null && anime.episodesTotal > 0 && !episodes.contains(anime.episodesTotal)) return false
        if (ageRating != null && anime.ageRating > 0 && anime.ageRating != ageRating.id) return false
        // Оценка сравнивается В ШКАЛЕ САМОГО ТАЙТЛА: 7.4 у фильма и 3.7 у аниме — одно
        // и то же место распределения, и общий порог их бы перепутал.
        if (minRating > 0.0 && anime.rating > 0.0 && anime.ratingMax > 0.0 &&
            anime.rating / anime.ratingMax < minRating / 10.0
        ) {
            return false
        }
        return true
    }

    /** Выбранное, разложенное на снимаемые по одному чипы. */
    fun chips(): List<FilterChipModel> = buildList {
        tags.forEach { key ->
            val tag = CatalogTag.byKey(key) ?: return@forEach
            add(FilterChipModel(tag.label) { copy(tags = tags - key) })
        }
        if (yearFrom > 0 || yearTo > 0) {
            val label = when {
                yearFrom > 0 && yearTo > 0 && yearFrom == yearTo -> "$yearFrom год"
                yearFrom > 0 && yearTo > 0 -> "$yearFrom—$yearTo"
                yearFrom > 0 -> "с $yearFrom"
                else -> "до $yearTo"
            }
            add(FilterChipModel(label) { copy(yearFrom = 0, yearTo = 0) })
        }
        status?.let { add(FilterChipModel(it.label) { copy(status = null) }) }
        ageRating?.let { add(FilterChipModel(it.label) { copy(ageRating = null) }) }
        episodes?.let { add(FilterChipModel(it.label) { copy(episodes = null) }) }
        contentType?.let { add(FilterChipModel(it.label) { copy(contentType = null) }) }
        if (minRating > 0.0) add(FilterChipModel("от ${"%.1f".format(minRating)}") { copy(minRating = 0.0) })
    }

    /** Сброс — но выбранная сортировка остаётся: её никто не «выбирал как фильтр». */
    fun cleared(): CatalogFilter = CatalogFilter(sort = sort)

    fun toggleTag(key: String): CatalogFilter =
        copy(tags = if (key in tags) tags - key else tags + key)
}

/** Один снимаемый чип над лентой. */
class FilterChipModel(val label: String, val remove: () -> CatalogFilter)

/** Сортировки — те, что Anixart реально понимает (число в [anixartId]). */
enum class CatalogSort(val label: String, val anixartId: Int) {
    POPULAR("По популярности", 4),
    RATING("По рейтингу", 3),
    FRESH("По новизне", 2),
    WATCHING("Сейчас смотрят", 1),
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
 * 4 — «Наруто», 5 — «Тетрадь смерти», «Берсерк», «Атака титанов».
 */
enum class AgeRating(val id: Int, val label: String) {
    KIDS(1, "0+"),
    SIX(2, "6+"),
    TWELVE(3, "12+"),
    SIXTEEN(4, "16+"),
    ADULT(5, "18+"),
}

/** Сколько серий. Диапазоны — под то, как аниме реально издаётся. */
enum class EpisodeRange(val label: String, val from: Int, val to: Int) {
    MOVIE("1 серия", 1, 1),
    SHORT("2—12", 2, 12),
    SEASON("13—26", 13, 26),
    LONG("27—99", 27, 99),
    ENDLESS("100+", 100, 0),
    ;

    fun contains(count: Int): Boolean = count >= from && (to == 0 || count <= to)
}

/** Тип контента. Числа — Anixart `category.id`. */
enum class ContentType(val label: String, val anixartId: Int) {
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
 * Словарь снят с каталога Anixart, а не выдуман: 1800 карточек по шести сортировкам,
 * 83 разных жанра. Из привычного списка четырёх там просто НЕТ, и вместо выдуманных
 * кнопок здесь стоит ближайшее настоящее: «Магия» — 2 тайтла на весь каталог (живое
 * соседнее — «Городское фэнтези» и «Фэнтези»), «Демоны» — нет жанра вовсе (тайтлы про
 * них лежат в «Сверхъестественном» и «Мифологии»), «Постапокалипсис» — ближайшее живое
 * «Выживание», «Игровой мир» — называется «Видеоигры».
 *
 * [primary] — показывать сразу; остальное прячется под «Ещё», чтобы список жанров на
 * телефоне не занимал два экрана.
 */
enum class CatalogTag(
    val key: String,
    val label: String,
    /** Точное имя жанра в каталоге Anixart. */
    val anixart: String,
    val primary: Boolean = false,
) {
    ACTION("action", "Экшен", "экшен", primary = true),
    FANTASY("fantasy", "Фэнтези", "фэнтези", primary = true),
    COMEDY("comedy", "Комедия", "комедия", primary = true),
    ADVENTURE("adventure", "Приключения", "приключения", primary = true),
    DRAMA("drama", "Драма", "драма", primary = true),
    ROMANCE("romance", "Романтика", "романтика", primary = true),
    SCHOOL("school", "Школа", "школа", primary = true),
    SCIFI("scifi", "Фантастика", "фантастика", primary = true),
    SUPERNATURAL("supernatural", "Сверхъестественное", "сверхъестественное", primary = true),
    ISEKAI("isekai", "Исекай", "исэкай", primary = true),
    HISTORY("history", "Историческое", "исторический", primary = true),
    MYSTERY("mystery", "Тайна", "тайна", primary = true),
    THRILLER("thriller", "Триллер", "триллер", primary = true),
    PSYCHOLOGY("psychology", "Психологическое", "психологическое", primary = true),
    HORROR("horror", "Ужасы", "ужасы", primary = true),
    SPORT("sport", "Спорт", "спорт", primary = true),
    MECHA("mecha", "Меха", "меха", primary = true),
    DETECTIVE("detective", "Детектив", "детектив", primary = true),

    // Дальше — под «Ещё».
    REINCARNATION("reincarnation", "Реинкарнация", "реинкарнация"),
    WAR("war", "Военное", "военное"),
    URBAN_FANTASY("urban_fantasy", "Городское фэнтези", "городское фэнтези"),
    MAGICAL_GIRL("magical_girl", "Махо-сёдзё", "махо-сёдзё"),
    MYTHOLOGY("mythology", "Мифология", "мифология"),
    MARTIAL_ARTS("martial_arts", "Боевые искусства", "боевые искусства"),
    SUPERPOWER("superpower", "Супер сила", "супер сила"),
    SURVIVAL("survival", "Выживание", "выживание"),
    SLICE_OF_LIFE("slice_of_life", "Повседневность", "повседневность"),
    VAMPIRES("vampires", "Вампиры", "вампиры"),
    VIDEOGAMES("videogames", "Игровой мир", "видеоигры"),
    TIME_TRAVEL("time_travel", "Путешествие во времени", "путешествие во времени"),
    SPACE("space", "Космос", "космос"),
    SAMURAI("samurai", "Самураи", "самураи"),
    MUSIC("music", "Музыка", "музыка"),
    CRIME("crime", "Криминал", "организованная преступность"),
    SHOUNEN("shounen", "Сёнен", "сёнен"),
    SEINEN("seinen", "Сэйнэн", "сэйнэн"),
    SHOUJO("shoujo", "Сёдзё", "сёдзё"),
    HAREM("harem", "Гарем", "гарем"),
    ECCHI("ecchi", "Этти", "этти"),
    GOURMET("gourmet", "Гурман", "гурман"),
    KIDS("kids", "Детское", "детское"),
    ;

    companion object {
        private val BY_KEY = entries.associateBy { it.key }

        fun byKey(key: String): CatalogTag? = BY_KEY[key]

        /**
         * Теги тайтла — по строке жанров, которую отдал источник.
         *
         * Строка сравнивается по ЦЕЛОМУ элементу списка, а не через `contains`: иначе
         * «сёнен» цеплялся бы за «сёнен-ай», а «спорт» — за «спортивные единоборства».
         */
        fun of(anime: Anime): Set<String> {
            if (anime.genres.isBlank()) return emptySet()
            val parts = anime.genres.split(',', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return emptySet()
            val out = HashSet<String>()
            for (tag in entries) {
                if (parts.contains(tag.anixart) || parts.contains(tag.label.lowercase())) out.add(tag.key)
            }
            return out
        }
    }
}

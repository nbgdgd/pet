package com.aniblaze.aggregator.model

import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Проверка фильтра НА НАШЕЙ СТОРОНЕ — та самая, что прогоняет всё, что вернули
 * источники.
 *
 * Она важнее серверной: под фильтром в ленту подмешиваются AniLibria и AnimeOn,
 * которые о фильтрах не слышали, и без этой проверки любое сочетание давало бы
 * «почти то, что просили».
 *
 * Отдельно сторожится правило «НЕИЗВЕСТНОЕ ПРОПУСКАЕТ»: источник, не отдающий год,
 * не должен исчезать из выдачи целиком при фильтре по году — иначе раздел выглядит
 * сломанным там, где на самом деле просто нет данных.
 */
class CatalogFilterTest {

    private fun anime(
        genres: String = "",
        year: Int = 0,
        status: Int = 0,
        age: Int = 0,
        country: String = "",
        episodes: Int = 0,
        type: String = "",
        rating: Double = 0.0,
        ratingMax: Double = 5.0,
    ) = Anime(
        id = "x", title = "x", poster = "", genres = genres, year = year, airingStatus = status,
        ageRating = age, country = country, episodesTotal = episodes, contentType = type,
        rating = rating, ratingMax = ratingMax,
    )

    private fun CatalogFilter.accepts(a: Anime) = matches(a, CatalogTag::of)

    // --- теги ---

    @Test
    fun `выбранные теги работают вместе, а не по отдельности`() {
        val filter = CatalogFilter(tags = setOf("isekai", "reincarnation"))
        assertTrue(filter.accepts(anime(genres = "исэкай, реинкарнация, фэнтези")))
        // Только один из двух — не подходит: теги СУЖАЮТ выборку.
        assertFalse(filter.accepts(anime(genres = "исэкай, фэнтези")))
    }

    @Test
    fun `жанр сверяется по целому слову`() {
        // «сёнен» не должен цепляться за «сёнен-ай», а «спорт» — за «спортивные
        // единоборства»: иначе выбор одного тега тянул бы за собой соседний.
        assertFalse(CatalogFilter(tags = setOf("shounen")).accepts(anime(genres = "сёнен-ай, драма")))
        assertTrue(CatalogFilter(tags = setOf("shounen")).accepts(anime(genres = "сёнен, экшен")))
        assertFalse(CatalogFilter(tags = setOf("sport")).accepts(anime(genres = "спортивные единоборства")))
    }

    @Test
    fun `теги узнаются и по словарю TMDB`() {
        // Кино отдаёт жанры человеческими словами, а не именами Anixart.
        assertTrue(CatalogFilter(tags = setOf("thriller")).accepts(anime(genres = "Триллер, Драма")))
    }

    // --- «неизвестное пропускает» ---

    @Test
    fun `тайтл без года не выкидывается фильтром по году`() {
        val filter = CatalogFilter(yearFrom = 2020, yearTo = 2024)
        assertTrue("источник не отдал год — это не повод его прятать", filter.accepts(anime(year = 0)))
        assertTrue(filter.accepts(anime(year = 2022)))
        assertFalse(filter.accepts(anime(year = 2019)))
        assertFalse(filter.accepts(anime(year = 2025)))
    }

    @Test
    fun `тайтл без статуса и без возраста проходит`() {
        assertTrue(CatalogFilter(status = TitleStatus.ONGOING).accepts(anime(status = 0)))
        assertTrue(CatalogFilter(ageRating = AgeRating.ADULT).accepts(anime(age = 0)))
        assertTrue(CatalogFilter(episodes = EpisodeRange.SHORT).accepts(anime(episodes = 0)))
        assertTrue(CatalogFilter(country = "Япония").accepts(anime(country = "")))
    }

    // --- остальные фасеты ---

    @Test
    fun `статус сверяется числом, а не текстом`() {
        assertTrue(CatalogFilter(status = TitleStatus.ONGOING).accepts(anime(status = 2)))
        assertFalse(CatalogFilter(status = TitleStatus.ONGOING).accepts(anime(status = 1)))
        assertTrue(CatalogFilter(status = TitleStatus.FINISHED).accepts(anime(status = 1)))
    }

    @Test
    fun `количество серий попадает в диапазон`() {
        val filter = CatalogFilter(episodes = EpisodeRange.SEASON) // 13..26
        assertTrue(filter.accepts(anime(episodes = 13)))
        assertTrue(filter.accepts(anime(episodes = 26)))
        assertFalse(filter.accepts(anime(episodes = 12)))
        assertFalse(filter.accepts(anime(episodes = 27)))
        // Верхняя граница у «100+» открыта.
        assertTrue(CatalogFilter(episodes = EpisodeRange.ENDLESS).accepts(anime(episodes = 1200)))
    }

    @Test
    fun `порог оценки считается в шкале самого тайтла`() {
        val filter = CatalogFilter(minRating = 8.0)
        // Аниме по пятибалльной: 4.2 из 5 это 8.4 из 10 — проходит.
        assertTrue(filter.accepts(anime(rating = 4.2, ratingMax = 5.0)))
        assertFalse(filter.accepts(anime(rating = 3.5, ratingMax = 5.0)))
        // Кино по десятибалльной сравнивается напрямую.
        assertTrue(filter.accepts(anime(rating = 8.4, ratingMax = 10.0)))
        assertFalse(filter.accepts(anime(rating = 7.0, ratingMax = 10.0)))
    }

    @Test
    fun `сочетание всех фасетов пропускает только подходящее`() {
        val filter = CatalogFilter(
            tags = setOf("isekai", "fantasy"),
            yearFrom = 2020, yearTo = 2024,
            status = TitleStatus.FINISHED,
            ageRating = AgeRating.SIXTEEN,
            country = "Япония",
            episodes = EpisodeRange.SHORT,
            contentType = ContentType.SERIES,
        )
        val good = anime(
            genres = "исэкай, фэнтези, приключения", year = 2022, status = 1, age = 4,
            country = "Япония", episodes = 12, type = "Сериал",
        )
        assertTrue(filter.accepts(good))
        // Каждое условие по очереди — и каждое обязано ронять совпадение.
        assertFalse(filter.accepts(good.copy(genres = "фэнтези, приключения")))
        assertFalse(filter.accepts(good.copy(year = 2019)))
        assertFalse(filter.accepts(good.copy(airingStatus = 2)))
        assertFalse(filter.accepts(good.copy(ageRating = 5)))
        assertFalse(filter.accepts(good.copy(country = "Китай")))
        assertFalse(filter.accepts(good.copy(episodesTotal = 24)))
        assertFalse(filter.accepts(good.copy(contentType = "Фильм")))
    }

    // --- теги над лентой ---

    @Test
    fun `каждый выбранный фильтр снимается своим тегом`() {
        val filter = CatalogFilter(
            tags = setOf("isekai"), yearFrom = 2024, yearTo = 2024,
            status = TitleStatus.ONGOING, country = "Япония",
        )
        assertEquals(4, filter.chips().size)
        assertEquals(4, filter.activeCount)
        val withoutYear = filter.chips().first { it.label == "2024 год" }.remove()
        assertEquals(0, withoutYear.yearFrom)
        assertTrue("снятие года не должно трогать остальное", withoutYear.tags.contains("isekai"))
        assertEquals(3, withoutYear.activeCount)
    }

    @Test
    fun `сброс убирает всё, но оставляет выбранную сортировку`() {
        val filter = CatalogFilter(tags = setOf("mecha"), yearFrom = 2010, sort = CatalogSort.RATING)
        val cleared = filter.cleared()
        assertTrue(cleared.isEmpty)
        assertEquals(0, cleared.activeCount)
        assertEquals(CatalogSort.RATING, cleared.sort)
    }

    @Test
    fun `сортировка не считается условием фильтра, но выводит из умолчания`() {
        // Ровно та развилка, на которой выпадашка сортировки была мёртвой кнопкой:
        // isEmpty решает, ЧТО слать серверу (сортировка — не условие), а isDefault —
        // ПОКАЗЫВАТЬ ЛИ сетку результатов. Смена сортировки обязана включать сетку.
        val sorted = CatalogFilter(sort = CatalogSort.VIEWS)
        assertTrue("сортировка — не условие отбора", sorted.isEmpty)
        assertFalse("но выпадашка обязана менять экран", sorted.isDefault)
        assertTrue(CatalogFilter().isDefault)
        // Сброс оставляет сортировку — и не должен возвращать в умолчание, пока она
        // не «По популярности»: человек её выбрал руками.
        assertFalse(CatalogFilter(tags = setOf("mecha"), sort = CatalogSort.RATING).cleared().isDefault)
    }

    @Test
    fun `тег переключается одним и тем же нажатием`() {
        val once = CatalogFilter().toggleTag("mecha")
        assertEquals(setOf("mecha"), once.tags)
        assertTrue(once.toggleTag("mecha").tags.isEmpty())
    }

    // --- словарь ---

    @Test
    fun `у каждого тега есть хотя бы один настоящий источник`() {
        // Тег, который не умеет ни каталог аниме, ни TMDB, — это кнопка, которая
        // всегда даёт пусто.
        val orphans = CatalogTag.entries.filter { it.anixart.isBlank() && it.tmdb == 0 }
        assertTrue("теги без источника: ${orphans.map { it.label }}", orphans.isEmpty())
    }

    @Test
    fun `ключи тегов уникальны`() {
        assertEquals(CatalogTag.entries.size, CatalogTag.entries.map { it.key }.toSet().size)
    }
}

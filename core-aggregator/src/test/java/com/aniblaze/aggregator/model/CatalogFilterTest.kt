package com.aniblaze.aggregator.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка фильтра каталога.
 *
 * Главное здесь — правило «неизвестное поле пропускает». Половина источников не отдаёт
 * ни года, ни числа серий, и строгая проверка выкосила бы их каталог целиком: лента
 * выглядела бы сломанной там, где на самом деле «источник не знает».
 */
class CatalogFilterTest {

    private fun anime(
        id: String = "1",
        genres: String = "",
        year: Int = 0,
        rating: Double = 0.0,
        ratingMax: Double = 5.0,
        episodesTotal: Int = 0,
        ageRating: Int = 0,
        contentType: String = "",
        airingStatus: Int = 0,
    ) = Anime(
        id = id,
        title = "Тайтл",
        poster = "",
        genres = genres,
        year = year,
        rating = rating,
        ratingMax = ratingMax,
        episodesTotal = episodesTotal,
        ageRating = ageRating,
        contentType = contentType,
        airingStatus = airingStatus,
    )

    // ---- «не знаем» это не «не подходит» -------------------------------------

    @Test
    fun `unknown year passes a year filter`() {
        val f = CatalogFilter(yearFrom = 2020)
        assertTrue(f.matches(anime(year = 0)))
        assertFalse(f.matches(anime(year = 2015)))
        assertTrue(f.matches(anime(year = 2021)))
    }

    @Test
    fun `unknown episode count passes an episode filter`() {
        val f = CatalogFilter(episodes = EpisodeRange.SHORT)
        assertTrue(f.matches(anime(episodesTotal = 0)))
        assertTrue(f.matches(anime(episodesTotal = 12)))
        assertFalse(f.matches(anime(episodesTotal = 50)))
    }

    @Test
    fun `unknown status passes a status filter`() {
        val f = CatalogFilter(status = TitleStatus.ONGOING)
        assertTrue(f.matches(anime(airingStatus = 0)))
        assertTrue(f.matches(anime(airingStatus = 2)))
        assertFalse(f.matches(anime(airingStatus = 1)))
    }

    @Test
    fun `genres are the one field where unknown means no`() {
        // Обратно всему остальному: выбрав «Экшен», человек ждёт экшен, а не вперемешку
        // с тем, про что источник промолчал.
        assertFalse(CatalogFilter(tags = setOf("action")).matches(anime(genres = "")))
        assertTrue(CatalogFilter(tags = setOf("action")).matches(anime(genres = "экшен")))
    }

    // ---- жанры ----------------------------------------------------------------

    @Test
    fun `tags are combined with AND`() {
        val f = CatalogFilter(tags = setOf("action", "fantasy"))
        assertTrue(f.matches(anime(genres = "экшен, фэнтези, приключения")))
        assertFalse(f.matches(anime(genres = "экшен, драма")))
    }

    @Test
    fun `genre matching takes the whole list item, not a substring`() {
        // «сёнен» не должен цепляться за «сёнен-ай», а «спорт» — за «спортивные
        // единоборства»: иначе фильтр «Сёнен» тащил бы совсем другое.
        assertFalse(CatalogTag.of(anime(genres = "сёнен-ай, драма")).contains("shounen"))
        assertTrue(CatalogTag.of(anime(genres = "сёнен, драма")).contains("shounen"))
    }

    // ---- оценка ----------------------------------------------------------------

    @Test
    fun `rating is compared in the title's own scale`() {
        val f = CatalogFilter(minRating = 8.0)
        // Anixart считает из пяти: 4.2 из 5 это 8.4 из 10 — проходит.
        assertTrue(f.matches(anime(rating = 4.2, ratingMax = 5.0)))
        assertFalse(f.matches(anime(rating = 3.5, ratingMax = 5.0)))
        // Десятибалльный источник сравнивается сам с собой.
        assertTrue(f.matches(anime(rating = 8.4, ratingMax = 10.0)))
        assertFalse(f.matches(anime(rating = 7.0, ratingMax = 10.0)))
    }

    @Test
    fun `titles with no rating are not filtered out`() {
        assertTrue(CatalogFilter(minRating = 9.0).matches(anime(rating = 0.0)))
    }

    // ---- состояние фильтра -------------------------------------------------------

    @Test
    fun `an empty filter matches everything`() {
        val f = CatalogFilter()
        assertTrue(f.isEmpty)
        assertEquals(0, f.activeCount)
        assertTrue(f.matches(anime(genres = "что угодно", year = 1999)))
    }

    @Test
    fun `sort alone is not a filter condition`() {
        val f = CatalogFilter(sort = CatalogSort.RATING)
        assertTrue(f.isEmpty)
        assertEquals(0, f.activeCount)
    }

    @Test
    fun `clearing keeps the chosen sort`() {
        val f = CatalogFilter(tags = setOf("action"), sort = CatalogSort.FRESH).cleared()
        assertTrue(f.isEmpty)
        assertEquals(CatalogSort.FRESH, f.sort)
    }

    @Test
    fun `active count counts a year range once`() {
        val f = CatalogFilter(tags = setOf("action", "drama"), yearFrom = 2010, yearTo = 2020, minRating = 8.0)
        assertEquals(4, f.activeCount)
    }

    @Test
    fun `toggling a tag adds then removes it`() {
        val once = CatalogFilter().toggleTag("action")
        assertEquals(setOf("action"), once.tags)
        assertTrue(once.toggleTag("action").tags.isEmpty())
    }

    @Test
    fun `each chip removes exactly its own condition`() {
        val f = CatalogFilter(tags = setOf("action"), yearFrom = 2010, status = TitleStatus.ONGOING)
        val chips = f.chips()
        assertEquals(3, chips.size)
        val withoutStatus = chips.first { it.label == TitleStatus.ONGOING.label }.remove()
        assertEquals(null, withoutStatus.status)
        assertEquals(setOf("action"), withoutStatus.tags)
        assertEquals(2010, withoutStatus.yearFrom)
    }

    // ---- диапазоны серий ----------------------------------------------------------

    @Test
    fun `the open-ended range has no upper bound`() {
        assertTrue(EpisodeRange.ENDLESS.contains(100))
        assertTrue(EpisodeRange.ENDLESS.contains(1000))
        assertFalse(EpisodeRange.ENDLESS.contains(99))
    }

    @Test
    fun `ranges do not overlap`() {
        val ranges = EpisodeRange.entries
        for (n in 1..120) {
            assertTrue("серий $n не попало ни в один диапазон", ranges.any { it.contains(n) })
            assertEquals("серий $n попало в несколько диапазонов", 1, ranges.count { it.contains(n) })
        }
    }
}

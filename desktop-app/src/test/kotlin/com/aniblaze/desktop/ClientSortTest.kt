package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogSort
import kotlin.test.Test
import org.junit.Assert.assertEquals

/**
 * Клиентская сортировка — для лент, которые сервер отсортировать не может.
 * Главное, что тут можно сломать, — сравнение оценок РАЗНЫХ шкал.
 */
class ClientSortTest {

    private fun anime(id: String, rating: Double = 0.0, ratingMax: Double = 5.0, year: Int = 0, watching: Int = 0) =
        Anime(id = id, title = id, poster = "", rating = rating, ratingMax = ratingMax, year = year, watchingCount = watching)

    @Test
    fun `по популярности порядок категории не трогается`() {
        val items = listOf(anime("а"), anime("б"), anime("в"))
        assertEquals(items, sortClientSide(items, CatalogSort.POPULAR))
    }

    @Test
    fun `оценки сравниваются в долях своей шкалы`() {
        // 4.6 из 5 (= 0.92) обязана стоять выше 7.4 из 10 (= 0.74) — по сырым числам
        // было бы наоборот.
        val five = anime("аниме", rating = 4.6, ratingMax = 5.0)
        val ten = anime("кино", rating = 7.4, ratingMax = 10.0)
        assertEquals(listOf(five, ten), sortClientSide(listOf(ten, five), CatalogSort.RATING))
    }

    @Test
    fun `по новизне сверху свежие годы`() {
        val old = anime("старое", year = 2011)
        val new = anime("новое", year = 2025)
        assertEquals(listOf(new, old), sortClientSide(listOf(old, new), CatalogSort.FRESH))
    }

    @Test
    fun `по просмотрам сверху большая аудитория`() {
        val big = anime("толпа", watching = 50_000)
        val small = anime("ниша", watching = 40)
        assertEquals(listOf(big, small), sortClientSide(listOf(small, big), CatalogSort.VIEWS))
    }
}

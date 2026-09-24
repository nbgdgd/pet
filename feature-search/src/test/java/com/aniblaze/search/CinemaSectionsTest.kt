package com.aniblaze.search

import com.aniblaze.aggregator.model.Anime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaSectionsTest {
    private fun anime(id: String, rating: Double, ratingMax: Double, year: Int) = Anime(
        id = id,
        title = id,
        poster = "",
        rating = rating,
        ratingMax = ratingMax,
        year = year,
    )

    @Test
    fun `sections keep source order and normalize mixed rating scales`() {
        val items = listOf(
            anime("popular-first", 4.0, 5.0, 2022),
            anime("ten-scale-best", 9.0, 10.0, 2021),
            anime("five-scale-best", 4.8, 5.0, 2020),
            anime("newest", 7.0, 10.0, 2026),
        )

        val sections = cinemaSections(items)

        assertEquals("popular-first", sections.first { it.key == "popular" }.items.first().id)
        assertEquals("five-scale-best", sections.first { it.key == "best" }.items.first().id)
        assertEquals("newest", sections.first { it.key == "new" }.items.first().id)
    }

    @Test
    fun `duplicates are removed before building rows`() {
        val duplicate = anime("same", 4.5, 5.0, 2025)
        val sections = cinemaSections(listOf(duplicate, duplicate.copy(title = "copy")))
        assertTrue(sections.all { row -> row.items.count { it.id == "same" } == 1 })
    }

    @Test
    fun `expanded sections keep every loaded page instead of replacing the cards`() {
        val items = (1..36).map { index -> anime("item-$index", 4.0, 5.0, 2020 + index % 6) }
        val sections = cinemaSections(items)

        assertEquals(36, sections.first { it.key == "popular" }.items.size)
        assertEquals(items.map { it.id }.toSet(), sections.first { it.key == "popular" }.items.map { it.id }.toSet())
    }
}

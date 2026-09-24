package com.aniblaze.aggregator.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Новые условия фильтра: «Этти» на виду, «13+» серий, «Скрыть просмотренное». */
class CatalogFilterExtrasTest {
    @Test fun `Этти показывается сразу и уходит в Anixart точным именем`() {
        val tag = CatalogTag.byKey("ecchi")!!
        assertTrue(tag.primary)
        assertEquals("этти", tag.anixart)
        assertTrue(tag in CatalogTag.ANIME)
    }

    @Test fun `диапазон 13+ - всё длиннее одного сезона`() {
        val range = EpisodeRange.MORE_THAN_SEASON
        assertEquals("13+", range.label)
        assertTrue(!range.contains(12))
        assertTrue(range.contains(13))
        assertTrue(range.contains(500))
        val filter = CatalogFilter(episodes = range)
        assertTrue(!filter.matches(Anime("a", "t", "", episodesTotal = 12)) { emptySet() })
        assertTrue(filter.matches(Anime("a", "t", "", episodesTotal = 24)) { emptySet() })
        // Неизвестное число серий фильтр пропускает: «не знаем» — не «не подходит».
        assertTrue(filter.matches(Anime("a", "t", "", episodesTotal = 0)) { emptySet() })
    }

    @Test fun `скрыть просмотренное - считается условием и снимается плашкой`() {
        val filter = CatalogFilter(hideWatched = true)
        assertTrue(!filter.isEmpty)
        assertEquals(1, filter.activeCount)
        val chip = filter.chips().single()
        assertEquals("Без просмотренного", chip.label)
        assertTrue(chip.remove().isEmpty)
        assertTrue(!CatalogFilter().hideWatched)
    }
}

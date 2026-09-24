package com.aniblaze.desktop.ui

import com.aniblaze.aggregator.source.EpisodeAirDates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Плашки «Серии»: всего / вышло / следующая — вместо строки текста под заголовком. */
class EpisodeSummaryTilesTest {
    private val today = java.time.LocalDate.of(2026, 9, 18)

    @Test fun `онгоинг - всего, вышло и следующая с датой`() {
        val next = java.time.LocalDate.of(2026, 9, 20).atTime(15, 30).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        val schedule = EpisodeAirDates.TitleSchedule(
            status = EpisodeAirDates.Status.AIRING, totalEpisodes = 12, nextEpisode = 12, nextAiringAt = next,
        )
        val tiles = episodeSummaryTiles(schedule, available = 11, catalogTotal = 0, now = today)
        assertEquals(listOf("Всего", "Вышло", "Следующая"), tiles.map { it.label })
        assertEquals(listOf("12", "11", "Серия 12"), tiles.map { it.value })
        assertEquals("через 2 дн. в 15:30", tiles.last().caption)
        assertTrue(tiles.last().accent)
    }

    @Test fun `завершён - всего и статус, без «вышло»`() {
        val schedule = EpisodeAirDates.TitleSchedule(status = EpisodeAirDates.Status.FINISHED, totalEpisodes = 24)
        val tiles = episodeSummaryTiles(schedule, available = 24, catalogTotal = 0, now = today)
        assertEquals(listOf("Всего", "Статус"), tiles.map { it.label })
        assertEquals("Завершён", tiles.last().value)
        assertEquals(TileTone.NEUTRAL, tiles.last().tone)
        assertEquals(TileTone.NEUTRAL, tiles.first().tone)
    }

    @Test fun `без расписания - всего из каталога`() {
        val tiles = episodeSummaryTiles(EpisodeAirDates.TitleSchedule.EMPTY, available = 3, catalogTotal = 12, now = today)
        assertEquals(listOf("Всего" to "12", "Вышло" to "3"), tiles.map { it.label to it.value })
        assertTrue(episodeSummaryTiles(EpisodeAirDates.TitleSchedule.EMPTY, available = 0, catalogTotal = 0, now = today).isEmpty())
    }
}

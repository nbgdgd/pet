package com.aniblaze.aggregator

import com.aniblaze.aggregator.source.isReleasedEpisode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LampaCatalogSourceTest {
    private val today = LocalDate.of(2026, 7, 15)

    @Test
    fun `aired episode is visible`() {
        assertTrue(isReleasedEpisode("2026-07-12", today))
        assertTrue(isReleasedEpisode("2026-07-15", today))
    }

    @Test
    fun `announced future episode is hidden`() {
        assertFalse(isReleasedEpisode("2026-07-19", today))
    }

    @Test
    fun `missing date is not treated as released metadata`() {
        assertFalse(isReleasedEpisode("", today))
        assertFalse(isReleasedEpisode("not-a-date", today))
    }
}

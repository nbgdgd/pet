package com.aniblaze.app.stats

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsDataTest {
    @Test
    fun `seek position does not inflate watch time or longest day`() {
        val now = LocalDate.of(2026, 9, 10).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val stats = buildStats(
            rows = listOf(
                WatchRow("a", "e1", 3_500_000, 3_600_000, now, 600_000, completed = false),
            ),
            titles = emptyMap(),
            episodeCounts = emptyMap(),
            today = LocalDate.of(2026, 9, 10),
            zone = ZoneOffset.UTC,
        )

        assertEquals(0, stats.hours)
        assertEquals(10, stats.longestDayMinutes)
    }
}

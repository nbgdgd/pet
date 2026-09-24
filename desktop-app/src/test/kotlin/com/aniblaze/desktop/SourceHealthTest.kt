package com.aniblaze.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** Окно в час: старые ответы выпадают, доля отказов считается по свежим. */
class SourceHealthTest {
    @Test fun `события старше часа выпадают из окна`() {
        val src = "test-window-${System.nanoTime()}"
        val t0 = 1_000_000_000L
        SourceHealth.record(src, ok = false, error = "timeout", now = t0)
        SourceHealth.record(src, ok = true, now = t0 + 10_000)
        assertEquals(1, SourceHealth.snapshot(src)!!.failed)
        assertEquals(0.5, SourceHealth.snapshot(src)!!.failureShare)

        SourceHealth.record(src, ok = true, now = t0 + 61 * 60_000)
        val snap = SourceHealth.snapshot(src)!!
        assertEquals(0, snap.failed)
        assertEquals(1, snap.ok)
        assertEquals("timeout", snap.lastError)
        assertEquals(t0, snap.lastFailAt)
    }

    @Test fun `ago - человекочитаемо`() {
        val now = 10_000_000L
        assertEquals("ещё не отвечал", SourceHealth.ago(0, now))
        assertEquals("только что", SourceHealth.ago(now - 30_000, now))
        assertEquals("5 мин назад", SourceHealth.ago(now - 5 * 60_000, now))
        assertEquals("2 ч назад", SourceHealth.ago(now - 2 * 60 * 60_000, now))
    }
}

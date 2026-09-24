package com.timeoverlay

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `ноль`() = assertEquals("0:00", TimeFormat.format(0))

    @Test
    fun `секунды`() = assertEquals("0:07", TimeFormat.format(7_400))

    @Test
    fun `минуты`() = assertEquals("4:37", TimeFormat.format(277_000))

    @Test
    fun `ровно час`() = assertEquals("1:00:00", TimeFormat.format(3_600_000))

    @Test
    fun `часы с минутами и секундами`() = assertEquals("1:12:04", TimeFormat.format(4_324_000))

    @Test
    fun `отрицательное значение не ломает формат`() = assertEquals("0:00", TimeFormat.format(-5_000))

    @Test
    fun `длинный формат — секунды`() = assertEquals("45 с", TimeFormat.formatLong(45_000))

    @Test
    fun `длинный формат — минуты`() = assertEquals("12 мин", TimeFormat.formatLong(12 * 60_000L))

    @Test
    fun `длинный формат — часы с минутами`() =
        assertEquals("3 ч 12 мин", TimeFormat.formatLong(3 * 3_600_000L + 12 * 60_000L))

    @Test
    fun `длинный формат — ровные часы`() =
        assertEquals("2 ч 0 мин", TimeFormat.formatLong(2 * 3_600_000L))

    @Test
    fun `длинный формат — ноль`() = assertEquals("0 с", TimeFormat.formatLong(0))
}

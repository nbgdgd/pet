package com.aniblaze.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RatingStarsPointerTest {
    @Test
    fun `движение между звездами всегда дает ровно один индекс`() {
        val cell = 30f
        assertEquals(1, starAtPointer(0f, cell))
        assertEquals(1, starAtPointer(29.99f, cell))
        assertEquals(2, starAtPointer(30f, cell))
        assertEquals(3, starAtPointer(75f, cell))
        assertEquals(5, starAtPointer(149.99f, cell))
    }

    @Test
    fun `некорректная координата не создает hover`() {
        assertEquals(0, starAtPointer(-1f, 30f))
        assertEquals(0, starAtPointer(Float.NaN, 30f))
        assertEquals(0, starAtPointer(10f, 0f))
    }
}

package com.aniblaze.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Поиск с опечатками: ближайшее известное название, без ложных «исправлений». */
class TypoSearchTest {
    private val known = listOf(
        "Реинкарнация безработного: История о приключениях в другом мире",
        "Наруто", "Наруто: Ураганные хроники", "Тетрадь смерти", "Клинок, рассекающий демонов", "Ванпанчмен",
    )

    @Test fun `опечатки в двух словах - находится нужное, короткое название раньше длинного`() {
        assertEquals("Реинкарнация безработного: История о приключениях в другом мире", TypoSearch.closest("реинкорнация безработнава", known).first())
        assertEquals(listOf("Наруто", "Наруто: Ураганные хроники"), TypoSearch.closest("нарута", known).take(2))
        assertEquals("Тетрадь смерти", TypoSearch.closest("тетрать смерти", known).first())
    }

    @Test fun `непохожее и слишком короткое - пусто`() {
        assertTrue(TypoSearch.closest("евангелион", known).isEmpty())
        assertTrue(TypoSearch.closest("на", known).isEmpty())
    }

    @Test fun `расстояние - перестановка соседних букв стоит одну правку`() {
        assertEquals(1, TypoSearch.damerau("нарту", "нарут"))
        assertEquals(0, TypoSearch.damerau("а", "а"))
        assertEquals(3, TypoSearch.damerau("abc", ""))
    }
}

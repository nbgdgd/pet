package com.aniblaze.desktop.ui

import kotlin.test.Test
import org.junit.Assert.assertEquals

/**
 * Склонение числительных на экране настроек.
 *
 * До перекомпоновки там стояло «тайтл(ов)» — форма, которой в живом языке нет: она
 * читается как заглушка, забытая в готовом экране. Правило русского счёта не сводится
 * к «последняя цифра», и проверяется здесь ровно то место, где оно ломается.
 */
class SettingsPluralTest {

    private fun titles(count: Int) = "$count ${ruPlural(count, "тайтл", "тайтла", "тайтлов")}"

    @Test
    fun `единица, малое число и множество`() {
        assertEquals("1 тайтл", titles(1))
        assertEquals("2 тайтла", titles(2))
        assertEquals("4 тайтла", titles(4))
        assertEquals("5 тайтлов", titles(5))
        assertEquals("0 тайтлов", titles(0))
    }

    @Test
    fun `подростковые числа — исключение`() {
        // Кончаются на 1..4, но требуют «тайтлов». Наивная проверка последней цифры
        // выдала бы здесь «11 тайтл» и «12 тайтла».
        assertEquals("11 тайтлов", titles(11))
        assertEquals("12 тайтлов", titles(12))
        assertEquals("13 тайтлов", titles(13))
        assertEquals("14 тайтлов", titles(14))
    }

    @Test
    fun `правило повторяется в каждой сотне`() {
        assertEquals("21 тайтл", titles(21))
        assertEquals("22 тайтла", titles(22))
        assertEquals("25 тайтлов", titles(25))
        assertEquals("101 тайтл", titles(101))
        assertEquals("111 тайтлов", titles(111))
        assertEquals("112 тайтлов", titles(112))
        assertEquals("121 тайтл", titles(121))
    }
}

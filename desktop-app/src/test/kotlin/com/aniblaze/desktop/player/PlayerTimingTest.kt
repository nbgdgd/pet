package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.OpeningRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerTimingTest {
    private val opening = OpeningRange(startMs = 60_000L, endMs = 150_000L)

    @Test
    fun `opening button appears five seconds before exact start`() {
        assertFalse(shouldShowOpeningButton(opening, 54_999L))
        assertTrue(shouldShowOpeningButton(opening, 55_000L))
    }

    @Test
    fun `opening button remains through range and disappears at exact end`() {
        assertTrue(shouldShowOpeningButton(opening, 60_000L))
        assertTrue(shouldShowOpeningButton(opening, 149_999L))
        assertFalse(shouldShowOpeningButton(opening, 150_000L))
    }

    @Test
    fun `missing or invalid timing never displays an opening button`() {
        assertFalse(shouldShowOpeningButton(null, 70_000L))
        assertFalse(shouldShowOpeningButton(OpeningRange(30_000L, 30_000L), 30_000L))
        assertFalse(shouldShowOpeningButton(OpeningRange(-1L, 30_000L), 10_000L))
    }

    // ---- автопропуск ----

    @Test
    fun `автопропуск срабатывает внутри точного интервала`() {
        assertTrue(shouldAutoSkipOpening(opening, positionMs = 61_000L, alreadySkipped = false, enabled = true))
    }

    @Test
    fun `автопропуск не трогает занятый интервал`() {
        // ГЛАВНОЕ ИСПРАВЛЕНИЕ. Занятый у другой серии интервал промахивается по старту
        // на десятки секунд — ЗАМЕРЕНО на «Чёрной кошке и классе ведьм»: серия 1
        // начинает опенинг на 2.1 с, серия 2 — на 42.5 с. Автопропуск по такому
        // интервалу молча срезает у зрителя полминуты НАСТОЯЩЕЙ серии, и он даже не
        // поймёт, что произошло. Кнопку показать можно — решение остаётся за ним.
        val borrowed = opening.copy(approximate = true)
        assertFalse(shouldAutoSkipOpening(borrowed, positionMs = 61_000L, alreadySkipped = false, enabled = true))
    }

    @Test
    fun `автопропуск выключен настройкой — не срабатывает`() {
        assertFalse(shouldAutoSkipOpening(opening, 61_000L, alreadySkipped = false, enabled = false))
    }

    @Test
    fun `автопропуск срабатывает один раз за серию`() {
        assertFalse(shouldAutoSkipOpening(opening, 61_000L, alreadySkipped = true, enabled = true))
    }

    @Test
    fun `автопропуск не срабатывает у самого конца интервала`() {
        // Иначе перемотка, приземлившаяся на миллисекунду раньше конца, зациклится.
        assertFalse(shouldAutoSkipOpening(opening, 149_500L, alreadySkipped = false, enabled = true))
    }

    @Test
    fun `автопропуск не срабатывает до опенинга и после него`() {
        assertFalse(shouldAutoSkipOpening(opening, 59_999L, alreadySkipped = false, enabled = true))
        assertFalse(shouldAutoSkipOpening(opening, 150_001L, alreadySkipped = false, enabled = true))
    }

    @Test
    fun `нет интервала — нечего пропускать`() {
        assertFalse(shouldAutoSkipOpening(null, 61_000L, alreadySkipped = false, enabled = true))
    }

    // ---- окно показа кнопки ----

    // ---- пропуск не имеет права тащить назад ----

    @Test
    fun `после пропуска кнопка исчезает`() {
        // ЗАМЕРЕНО В ЖУРНАЛЕ. Хвост окна показа держал кнопку ещё двадцать секунд
        // ПОСЛЕ конца интервала, а цель у неё фиксированная — endMs. Выглядело это
        // так: нажал, перепрыгнул на 119242, посмотрел одиннадцать секунд, нажал
        // снова (кнопка-то на месте) — и тебя отбросило обратно на 119242.
        //
        //     00:35:23  play.seek targetMs=119242
        //     00:35:34  play.seek targetMs=119242   ← назад на одиннадцать секунд
        //     00:35:36  play.seek targetMs=119242   ← и ещё раз
        val borrowed = opening.copy(approximate = true)
        assertFalse(shouldShowOpeningButton(borrowed, 150_000L), "на конце интервала кнопке уже нечего делать")
        assertFalse(shouldShowOpeningButton(borrowed, 160_000L))
        assertFalse(shouldShowOpeningButton(opening, 150_000L))
    }

    @Test
    fun `цель пропуска никогда не позади текущей позиции`() {
        // Второй рубеж: даже если кнопку кто-то покажет не вовремя, нажатие не имеет
        // права увести назад. «Пропустить» — это всегда вперёд.
        assertEquals(150_000L, openingSkipTarget(opening, positionMs = 60_000L))
        assertNull(openingSkipTarget(opening, positionMs = 150_000L), "позади конца интервала пропускать нечего")
        assertNull(openingSkipTarget(opening, positionMs = 160_000L))
        assertNull(openingSkipTarget(null, positionMs = 10_000L))
    }

    @Test
    fun `у занятого интервала запас только спереди`() {
        // Прежние 45 секунд запаса спереди появились как костыль под плохого донора:
        // интервал занимался у ПЕРВОЙ серии и уезжал на сорок секунд вперёд, поэтому
        // кнопку приходилось показывать почти с начала серии. Донор исправлен, и
        // такой ширины больше не нужно — она только держала кнопку на экране всю
        // первую минуту.
        val borrowed = opening.copy(approximate = true)
        // Запас только СПЕРЕДИ: настоящий опенинг может начаться раньше занятого
        // интервала, и тогда кнопка нужна заранее. Хвоста нет — за концом интервала
        // кнопка может только утащить назад (см. тест выше).
        assertFalse(shouldShowOpeningButton(borrowed, 39_999L))
        assertTrue(shouldShowOpeningButton(borrowed, 40_000L))
        assertTrue(shouldShowOpeningButton(borrowed, 149_999L))
        assertFalse(shouldShowOpeningButton(borrowed, 150_000L))
    }
}

package com.aniblaze.desktop.ui

import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Разделитель «клик по карточке» и «протяжка ленты».
 *
 * Обе роли теперь у одной левой кнопки, и вся цена ошибки видна пользователю сразу:
 * порог сработал слишком рано — карточка не открывается от обычного клика; слишком
 * поздно — после протяжки открывается тайтл, на который просто попал курсор.
 */
class DragGateTest {

    @Test
    fun `дрожание руки на пару пикселей остаётся кликом`() {
        val gate = DragGate()
        listOf(1f, -2f, 1.5f, -1f, 2f).forEach { assertEquals(0f, gate.push(it), 0f) }
        assertFalse("клик не должен превращаться в протяжку", gate.engaged)
    }

    @Test
    fun `движение туда-обратно само себя гасит`() {
        // Копится смещение, а не пройденный путь: по сумме модулей эти шаги давно
        // перевалили бы порог, хотя курсор стоит там же, где нажали.
        val gate = DragGate()
        repeat(20) {
            gate.push(6f)
            gate.push(-6f)
        }
        assertFalse(gate.engaged)
    }

    @Test
    fun `после порога жест становится протяжкой навсегда`() {
        val gate = DragGate()
        assertEquals(0f, gate.push(DRAG_SLOP_PX - 1f), 0f)
        assertTrue(gate.push(2f) != 0f)
        assertTrue(gate.engaged)
        // Возврат курсора назад кликом уже не делает — иначе после протяжки влево и
        // обратно карточка под курсором внезапно открывалась бы.
        gate.push(-100f)
        assertTrue(gate.engaged)
    }

    @Test
    fun `накопленное до порога не теряется`() {
        // Иначе лента трогается с отставанием ровно на порог и «прилипает» к курсору
        // только со второго движения.
        val gate = DragGate()
        gate.push(5f)
        val first = gate.push(5f)
        assertEquals(10f, first, 0.001f)
    }

    @Test
    fun `после порога сдвиг идёт один в один`() {
        val gate = DragGate()
        gate.push(DRAG_SLOP_PX)
        assertEquals(13f, gate.push(13f), 0f)
        assertEquals(-4f, gate.push(-4f), 0f)
        assertEquals(0f, gate.push(0f), 0f)
    }

    @Test
    fun `порог одинаков в обе стороны`() {
        val left = DragGate()
        left.push(-DRAG_SLOP_PX)
        val right = DragGate()
        right.push(DRAG_SLOP_PX)
        assertTrue(left.engaged)
        assertTrue(right.engaged)
    }

    @Test
    fun `один большой скачок курсора сразу считается протяжкой`() {
        // Мышь на высоком DPI выдаёт крупные шаги: первого же события может хватить.
        val gate = DragGate()
        assertEquals(40f, gate.push(40f), 0.001f)
        assertTrue(gate.engaged)
    }
}

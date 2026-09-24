package com.aniblaze.desktop.ui

import com.aniblaze.desktop.TitleWatch
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Подпись и гашение карточки — та часть [PosterCard], которую можно проверить без
 * Compose: сколько строк отдать названию и когда обложку гасить.
 *
 * Числа ширины настоящие: посчитаны из раскладки [PosterGrid] (отступы 16 dp по краям,
 * 12 dp между колонками) для окон, в которых приложением реально пользуются.
 */
class PosterCardLogicTest {

    /** Ширина ячейки сетки в dp — ровно та же формула, что в PosterGrid. */
    private fun cell(windowDp: Int, columns: Int): Int =
        (windowDp - 32 - 12 * (columns - 1)) / columns

    @Test
    fun `fixed setting always resolves to exact requested columns`() {
        listOf(640f, 1200f, 1920f).forEach { width ->
            (3..6).forEach { requested ->
                assertEquals("width=$width", requested, posterColumnCount(requested, width))
            }
        }
    }

    @Test
    fun `fewer columns make cards and text larger`() {
        val width = 1500f
        val three = posterCardWidthDp(width, posterColumnCount(3, width))
        val six = posterCardWidthDp(width, posterColumnCount(6, width))
        assertTrue(three > six * 2f)
        assertTrue(posterCardMetrics(three.toInt()).titleSp > posterCardMetrics(six.toInt()).titleSp)
        assertTrue(posterCardMetrics(three.toInt()).secondarySp > posterCardMetrics(six.toInt()).secondarySp)
    }

    @Test
    fun `auto reacts to resize and every fixed row fills available width`() {
        val narrow = posterColumnCount(0, 760f)
        val wide = posterColumnCount(0, 1600f)
        assertTrue(wide > narrow)
        assertTrue(wide <= 12)

        (3..6).forEach { columns ->
            val cellWidth = posterCardWidthDp(1500f, columns)
            val occupied = cellWidth * columns + 12f * (columns - 1) + 32f
            assertEquals(1500f, occupied, 0.01f)
        }
    }

    @Test
    fun `узкая ячейка получает меньше строк чем широкая`() {
        // Плотная адаптивная сетка в окне 1200 dp — около 8 колонок и 135 dp на карточку.
        assertEquals(1, posterTitleLines(cell(1200, 8)))
        // Карточка карусели (150 dp) и обычная адаптивная ячейка — две строки.
        assertEquals(2, posterTitleLines(150))
        assertEquals(2, posterTitleLines(cell(1600, 8)))
        // Три колонки на большом мониторе — ячейка за 500 dp, три строки.
        assertEquals(3, posterTitleLines(cell(1600, 3)))
    }

    @Test
    fun `число строк только растёт с шириной`() {
        // Сетка не должна получать больше строк от того, что ячейка СУЗИЛАСЬ:
        // именно так подпись и начинала прыгать при смене числа колонок.
        val widths = (60..900 step 5).toList()
        widths.zipWithNext().forEach { (narrow, wide) ->
            assertTrue(
                "строк при $wide dp меньше, чем при $narrow dp",
                posterTitleLines(wide) >= posterTitleLines(narrow),
            )
        }
    }

    @Test
    fun `строк всегда хотя бы одна и не больше трёх`() {
        // Ноль строк — подпись исчезла бы вовсе; четыре — подпись выше постера.
        listOf(0, 1, 149, 260, 4000).forEach { width ->
            assertTrue("ширина $width", posterTitleLines(width) in 1..3)
        }
    }

    @Test
    fun `частично просмотренный тайтл не гасится никогда`() {
        // Остановились на третьей серии из двенадцати — это ровно тот тайтл, к
        // которому возвращаются, и прятать его нельзя даже с включённой настройкой.
        val halfway = TitleWatch(episode = 3, total = 12, finished = false, fraction = 0.4f)
        assertFalse(watchedOut(dimWatchedEnabled = true, watch = halfway))
        assertEquals(PosterDim(1f, 1f), posterDim(unfinishedLast = false, watchedOut = false, hovered = false))
    }

    @Test
    fun `досмотренный гасится только при включённой настройке`() {
        val finished = TitleWatch(episode = 12, total = 12, finished = true, fraction = 0f)
        assertTrue(watchedOut(dimWatchedEnabled = true, watch = finished))
        assertFalse(watchedOut(dimWatchedEnabled = false, watch = finished))
        // Тайтл, который вообще не открывали, гасить не за что.
        assertFalse(watchedOut(dimWatchedEnabled = true, watch = null))
    }

    @Test
    fun `под курсором обложка всегда в полную силу`() {
        // Наведение обязано возвращать цвет при ЛЮБОМ поводе гашения — иначе
        // разглядеть карточку нечем.
        listOf(true, false).forEach { unfinished ->
            listOf(true, false).forEach { watched ->
                assertEquals(
                    PosterDim(1f, 1f),
                    posterDim(unfinishedLast = unfinished, watchedOut = watched, hovered = true),
                )
            }
        }
    }

    @Test
    fun `досмотренное гасится слабее чем недосмотренная новая серия`() {
        val watched = posterDim(unfinishedLast = false, watchedOut = true, hovered = false)
        val unfinished = posterDim(unfinishedLast = true, watchedOut = false, hovered = false)
        // Метка истории не должна кричать громче подсказки «есть что посмотреть».
        assertTrue(watched.saturation > unfinished.saturation)
        assertTrue(watched.alpha > unfinished.alpha)
        // И при этом гашение обязано быть заметным, иначе настройка бессмысленна.
        assertTrue(watched.saturation < 1f)
        assertTrue(watched.alpha < 1f)
    }

    @Test
    fun `при споре признаков побеждает недосмотренная серия`() {
        // Признаки считаются из разных следов (episodeCounts против watchIndex) и в
        // принципе могут разойтись; тогда сильнее гасит тот, что зовёт смотреть.
        assertEquals(
            posterDim(unfinishedLast = true, watchedOut = false, hovered = false),
            posterDim(unfinishedLast = true, watchedOut = true, hovered = false),
        )
    }
}

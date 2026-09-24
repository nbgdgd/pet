package com.aniblaze.desktop.player

import kotlin.test.Test
import org.junit.Assert.assertEquals

/**
 * С какой миллисекунды стартует серия.
 *
 * Стережёт жалобу «нажал „следующая серия“ — она открылась почти в самом конце».
 * Причина была в порядке веток: `resumeNext` проверялся ПЕРВЫМ, а этот флаг поднимают
 * сторож зависания, сторож «нет кадров», перебор качеств и восстановление
 * поверхности — все они срабатывают в том числе у конца серии. Флаг доживал до смены
 * серии, и новая уходила на позицию предыдущей.
 */
class ResumeTargetTest {

    /** Двадцать три минуты — конец предыдущей серии. */
    private val endOfPrevious = 1_400_000L

    @Test
    fun `новая серия не наследует позицию предыдущей ни при каких флагах`() {
        // Худший случай: доигрывали конец, сторож успел поднять resumeNext, и точка
        // возобновления прошлой серии ещё висит.
        val target = resumeTargetMs(
            firstLoad = false,
            episodeChanged = true,
            resumeNext = true,
            position = endOfPrevious,
            startPositionMs = 0L,
            pendingStartMs = endOfPrevious,
        )
        assertEquals("новая серия обязана начинаться с нуля", 0L, target)
    }

    @Test
    fun `впервые открытая серия начинается с нуля`() {
        assertEquals(
            0L,
            resumeTargetMs(
                firstLoad = true, episodeChanged = false, resumeNext = false,
                position = 0L, startPositionMs = 0L, pendingStartMs = 0L,
            ),
        )
    }

    @Test
    fun `начатая ранее серия продолжается со своей точки`() {
        assertEquals(
            540_000L,
            resumeTargetMs(
                firstLoad = true, episodeChanged = false, resumeNext = false,
                position = 0L, startPositionMs = 540_000L, pendingStartMs = 540_000L,
            ),
        )
    }

    @Test
    fun `переход на другую серию берёт её собственную сохранённую точку`() {
        // У следующей серии свой прогресс — 3 минуты; позиция прошлой не участвует.
        assertEquals(
            180_000L,
            resumeTargetMs(
                firstLoad = false, episodeChanged = true, resumeNext = true,
                position = endOfPrevious, startPositionMs = 180_000L, pendingStartMs = endOfPrevious,
            ),
        )
    }

    @Test
    fun `смена качества продолжает с текущего места`() {
        // Серия та же (episodeChanged = false) — вот здесь position и нужен.
        assertEquals(
            612_000L,
            resumeTargetMs(
                firstLoad = false, episodeChanged = false, resumeNext = true,
                position = 612_000L, startPositionMs = 0L, pendingStartMs = 0L,
            ),
        )
    }

    @Test
    fun `повторный проход эффекта не откатывает серию на ноль`() {
        // Эффект play срабатывает дважды подряд при открытии серии, и на втором
        // проходе position ещё ноль. pendingStartMs держит точку до приезда.
        assertEquals(
            537_000L,
            resumeTargetMs(
                firstLoad = false, episodeChanged = false, resumeNext = false,
                position = 0L, startPositionMs = 537_000L, pendingStartMs = 537_000L,
            ),
        )
    }

    @Test
    fun `замена зависшего экземпляра продолжает с места остановки`() {
        // Форма ровно та, что складывается после vlc.abandon: экземпляр брошен,
        // поэтому loadedMediaKey пуст (firstLoad), position обнулена вместе с ним, а
        // место остановки живёт в pendingStartMs. На диске при этом лежит СТАРЫЙ, более
        // ранний прогресс — брать надо не его.
        assertEquals(
            989_614L,
            resumeTargetMs(
                firstLoad = true, episodeChanged = false, resumeNext = false,
                position = 0L, startPositionMs = 743_227L, pendingStartMs = 989_614L,
            ),
        )
    }

    @Test
    fun `отрицательные значения не уезжают ниже нуля`() {
        assertEquals(
            0L,
            resumeTargetMs(
                firstLoad = false, episodeChanged = false, resumeNext = false,
                position = -5L, startPositionMs = 0L, pendingStartMs = -1L,
            ),
        )
    }
}

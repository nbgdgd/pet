package com.aniblaze.desktop

import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Потолок списка «Продолжить просмотр».
 *
 * Замер на живом state.json: 60 записей прогресса весили 39 957 байт — 666 байт на
 * запись, потому что каждая тянет за собой ВЕСЬ [PersistedAnime] (описание, постер,
 * жанры), а не одну ссылку на тайтл. И ровно 60 записей там и лежало: список стоял
 * ПОЛНЫМ, то есть каждая новая начатая серия молча выбрасывала самую старую точку
 * просмотра — тайтл, начатый месяц назад, исчезал из «Продолжить» без следа.
 */
class ProgressCapTest {

    private fun entry(id: String, segment: Int = 1) = ProgressEntry(
        anime = PersistedAnime(id = id, title = id, poster = ""),
        segment = segment,
        positionMs = 60_000,
        durationMs = 1_400_000,
        updatedAt = 1L,
    )

    @Test
    fun `шестьдесят первая начатая серия не вытесняет самую старую`() {
        // Ровно состояние живого файла: список дорос до старого потолка, и следующая
        // серия обязана ДОБАВИТЬСЯ, а не заменить собой давно начатый тайтл.
        val old = (1..60).map { entry("t$it") }
        val capped = capProgress(listOf(entry("новая")) + old)
        assertEquals(61, capped.size)
        assertTrue("самый старый тайтл потерян", capped.any { it.anime.id == "t60" })
    }

    @Test
    fun `потолок выбран по весу записи, а не на глаз`() {
        // 666 байт на запись (39 957 Б / 60 записей). Весь state.json переписывается
        // целиком каждые ~5 секунд просмотра, поэтому потолок ограничен не диском, а
        // ценой одной перезаписи: держим список прогресса в пределах трети мегабайта.
        assertTrue("потолок ниже замеренной потребности", PROGRESS_LIMIT >= 400)
        assertTrue("список прогресса перевесит треть мегабайта", PROGRESS_LIMIT * 666 <= 350_000)
    }

    @Test
    fun `на потолке уходит самое старое, а не самое свежее`() {
        // Список хранится «самое свежее первым», значит резать надо ХВОСТ.
        val overflow = (1..PROGRESS_LIMIT + 5).map { entry("t$it") }
        val capped = capProgress(overflow)
        assertEquals(PROGRESS_LIMIT, capped.size)
        assertEquals("t1", capped.first().anime.id)
        assertEquals("t$PROGRESS_LIMIT", capped.last().anime.id)
    }
}

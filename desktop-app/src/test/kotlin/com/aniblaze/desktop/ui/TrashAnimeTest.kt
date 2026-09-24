package com.aniblaze.desktop.ui

import kotlin.test.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * «Шлакометр» на оценках MyAnimeList.
 *
 * Числа ниже — настоящие, сняты с Shikimori (его id и есть id MAL) для тайтлов из
 * списка владельца. Порог откалиброван по СЛУЧАЙНОЙ выборке каталога (391 сериал,
 * вышедшие): медиана 6.90, нижняя четверть — ниже 6.38.
 *
 * Тест отдельно стережёт ошибку первой версии, которая искала штампы в названии и
 * записывала в шлак «Реинкарнацию безработного» — лучший тайтл списка по мнению
 * зрителей (MAL 8.20).
 */
class TrashAnimeTest {

    /** (название, оценка MAL, всего голосов, из них 4 и ниже) — снято с shikimori.one. */
    private data class Real(val title: String, val score: Double, val votes: Int, val low: Int)

    private val favourites = listOf(
        Real("Реинкарнация безработного", 8.20, 44282, 1328),   // 3.0% низких
        Real("О моём перерождении в слизь", 8.13, 74713, 2914), // 3.9%
        Real("Необыкновенный неудачник", 7.49, 20954, 1572),    // 7.5%
        Real("Крестьянин 999 уровня", 7.07, 7876, 906),         // 11.5%
        Real("Изгнанный реинкарнированный рыцарь", 6.84, 660, 127), // 19.2%
        Real("Расхититель гробниц", 6.75, 347, 41),             // 11.8%
        Real("Невеста демона", 7.04, 171, 27),                  // 15.8%, но голосов мало
        Real("Табакошка", 0.0, 0, 0),                           // оценок нет вовсе
    )

    private fun trash(r: Real) = TrashAnime.isTrash(r.score, r.low, r.votes)
    private fun byTitle(name: String) = favourites.first { it.title.startsWith(name) }

    @Test
    fun `жанр не равен качеству — исекай с высокой оценкой не шлак`() {
        // Ровно та ошибка, из-за которой словарную версию пришлось выбросить.
        assertFalse("MAL 8.20 — лучший в списке", trash(byTitle("Реинкарнация безработного")))
        assertFalse("MAL 8.13", trash(byTitle("О моём перерождении")))
        assertFalse("MAL 7.49", trash(byTitle("Необыкновенный")))
    }

    @Test
    fun `много низких оценок ловится даже при средней оценке`() {
        // 6.84 — ещё не нижняя четверть, но каждый пятый поставил «4 и ниже».
        assertTrue("19.2% низких при 660 голосах", trash(byTitle("Изгнанный")))
    }

    @Test
    fun `нижняя четверть каталога считается шлаком`() {
        assertTrue("ниже 6.38 — нижняя четверть", TrashAnime.isTrash(6.10, 500, 9000))
        assertFalse("6.75 в четверть не попадает и низких немного", trash(byTitle("Расхититель")))
    }

    @Test
    fun `тайтлы с горсткой голосов не судятся вовсе`() {
        // 15.8% низких, но всего 171 голос — на таком объёме это шум.
        assertFalse("мало голосов — не приговор", trash(byTitle("Невеста")))
        assertFalse("оценок нет вообще", trash(byTitle("Табакошка")))
        assertFalse(TrashAnime.hasVerdict(TrashAnime.MIN_VOTES - 1))
        assertTrue(TrashAnime.hasVerdict(TrashAnime.MIN_VOTES))
    }

    @Test
    fun `доля считается без деления на ноль`() {
        assertTrue(TrashAnime.lowShare(0, 0) == 0.0)
        assertTrue(TrashAnime.lowShare(25, 100) == 25.0)
    }

    @Test
    fun `приговор меняется вместе с долей`() {
        val steps = listOf(0, 10, 20, 40, 60, 80).map { TrashAnime.verdict(it) }
        assertTrue("приговоры повторяются: $steps", steps.toSet().size == steps.size)
        assertTrue(TrashAnime.verdict(0).contains("Ни одного"))
    }

    @Test
    fun `средняя сравнивается с медианой каталога`() {
        assertTrue(TrashAnime.tasteVerdict(8.2).contains("выше"))
        assertTrue(TrashAnime.tasteVerdict(6.9).contains("вровень"))
        assertTrue(TrashAnime.tasteVerdict(5.5).contains("ниже"))
        assertTrue("без данных подписи быть не должно", TrashAnime.tasteVerdict(0.0).isEmpty())
    }

    @Test
    fun `на списке владельца плашка что-то различает`() {
        val judged = favourites.count { TrashAnime.hasVerdict(it.votes) }
        val flagged = favourites.count { trash(it) }
        assertTrue("оценено $judged из ${favourites.size}", judged == 6)
        // Не «всё подряд» и не «ничего» — иначе шкала бессмысленна.
        assertTrue("помечено $flagged", flagged in 1..(judged / 2))
    }
}

package com.aniblaze.desktop

import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Галочка «просмотрено» на карточке.
 *
 * Проверяется не сама отметка, а то, что видит человек ПОСЛЕ неё: метка на постере
 * считается [buildWatchIndex] по двум разным следам, и наивная отметка её не зажигает.
 * Поэтому каждый тест ставит отметку и смотрит на итоговый [TitleWatch].
 */
class TitleWatchedMarkTest {

    private fun title(id: String) = PersistedAnime(id = id, title = id, poster = "")

    private fun entry(id: String, segment: Int, fraction: Float) = ProgressEntry(
        anime = title(id),
        segment = segment,
        positionMs = (1_400_000 * fraction).toLong(),
        durationMs = 1_400_000,
        updatedAt = 1_000L,
    )

    private fun watchOf(state: PersistedState, id: String) = buildWatchIndex(state)[id]

    @Test
    fun `отметка зажигает «Просмотрено» у тайтла с известным числом серий`() {
        val before = PersistedState(episodeCounts = mapOf("a" to 12))
        assertNull(watchOf(before, "a"))

        val after = markTitleWatched(before, title("a"), watched = true)
        val watch = watchOf(after, "a")!!
        assertTrue(watch.finished)
        assertEquals(12, watch.episode)
        assertEquals(1f, watch.overall, 0.001f)
    }

    @Test
    fun `отмечаются все серии, а не одна последняя`() {
        // Одной последней хватило бы, чтобы карточка позеленела, но список серий
        // остался бы почти пустым, а «продолжить» предложило бы первую серию.
        val after = markTitleWatched(PersistedState(episodeCounts = mapOf("a" to 5)), title("a"), watched = true)
        assertEquals(setOf("a#1", "a#2", "a#3", "a#4", "a#5"), after.watched)
    }

    @Test
    fun `брошенная серия не мешает отметке`() {
        // Главная ловушка: незаконченная серия перебивает досмотренные, и без её
        // удаления finished остался бы false — галочка бы не загоралась вовсе.
        val before = PersistedState(
            watched = setOf("a#1"),
            progress = listOf(entry("a", 2, 0.4f)),
            episodeCounts = mapOf("a" to 12),
        )
        assertFalse(watchOf(before, "a")!!.finished)

        val after = markTitleWatched(before, title("a"), watched = true)
        assertTrue(watchOf(after, "a")!!.finished)
        assertTrue("запись о брошенной серии осталась", after.progress.isEmpty())
    }

    @Test
    fun `тайтл с неизвестным числом серий тоже отмечается`() {
        // Ни разу не открывали — числа серий нет. Одной отметки достаточно: при
        // неизвестном общем числе досмотренным считается любой дошедший до конца.
        val after = markTitleWatched(PersistedState(), title("a"), watched = true)
        assertEquals(setOf("a#1"), after.watched)
        assertTrue(watchOf(after, "a")!!.finished)
    }

    @Test
    fun `снятие отметки убирает тайтл из просмотренных совсем`() {
        val marked = markTitleWatched(PersistedState(episodeCounts = mapOf("a" to 3)), title("a"), watched = true)
        val cleared = markTitleWatched(marked, title("a"), watched = false)
        assertTrue(cleared.watched.isEmpty())
        assertNull(watchOf(cleared, "a"))
    }

    @Test
    fun `снятие отметки стирает и брошенную серию`() {
        // «Снять отметку» значит «я это не смотрел», а не «я остановился на второй».
        val before = PersistedState(
            watched = setOf("a#1"),
            progress = listOf(entry("a", 2, 0.4f)),
            episodeCounts = mapOf("a" to 12),
        )
        val cleared = markTitleWatched(before, title("a"), watched = false)
        assertTrue(cleared.watched.isEmpty())
        assertTrue(cleared.progress.isEmpty())
        assertNull(watchOf(cleared, "a"))
    }

    @Test
    fun `соседние тайтлы не задеты`() {
        val before = PersistedState(
            watched = setOf("b#1", "b#2", "ab#1"),
            progress = listOf(entry("b", 3, 0.5f)),
            episodeCounts = mapOf("a" to 2, "b" to 12),
        )
        val after = markTitleWatched(before, title("a"), watched = true)
        assertTrue(after.watched.containsAll(setOf("b#1", "b#2", "ab#1")))
        assertEquals(1, after.progress.size)
        assertFalse(watchOf(after, "b")!!.finished)
    }

    @Test
    fun `похожий идентификатор не считается тем же тайтлом`() {
        // Отметки сравниваются по префиксу «id#», а не по «начинается с id»: иначе
        // снятие отметки у «a» унесло бы и «ab».
        val before = PersistedState(watched = setOf("ab#1", "ab#2"), episodeCounts = mapOf("ab" to 2))
        val after = markTitleWatched(before, title("a"), watched = false)
        assertEquals(setOf("ab#1", "ab#2"), after.watched)
        assertTrue(watchOf(after, "ab")!!.finished)
    }

    @Test
    fun `повторная отметка ничего не ломает и не плодит записей`() {
        val once = markTitleWatched(PersistedState(episodeCounts = mapOf("a" to 3)), title("a"), watched = true)
        val twice = markTitleWatched(once, title("a"), watched = true)
        assertEquals(once.watched, twice.watched)
        assertTrue(watchOf(twice, "a")!!.finished)
    }

    // ---- отметка обязана доходить до рекомендаций ----------------------------

    private fun card(id: String, genres: String, studio: String = "", year: Int = 2020) =
        PersistedAnime(id = id, title = id, poster = "", genres = genres, studio = studio, year = year)

    @Test
    fun `отмеченный тайтл влияет на вкус`() {
        // Ровно та поломка, из-за которой отметка «ничего не делала»: набор watched —
        // это одни строки «id#серия», и Recommender о таком тайтле не знал вовсе,
        // потому что карточку с жанрами взять было неоткуда.
        val before = PersistedState(episodeCounts = mapOf("a" to 12))
        assertEquals(0, Recommender.buildTaste(before).samples)

        val after = markTitleWatched(before, card("a", "экшен, фэнтези", studio = "MAPPA"), watched = true)
        val taste = Recommender.buildTaste(after)
        assertEquals(1, taste.samples)
        assertTrue("жанры отмеченного не попали во вкус", taste.genres.containsKey("экшен"))
        assertTrue("студия отмеченного не попала во вкус", taste.studios.containsKey("MAPPA"))
    }

    @Test
    fun `отмеченный тайтл больше не предлагается`() {
        // Второй симптом: тайтл оставался в рекомендациях после отметки, потому что в
        // «уже видел» его тоже никто не записывал.
        val after = markTitleWatched(PersistedState(), card("a", "экшен"), watched = true)
        assertTrue("отмеченный тайтл не попал в «уже видел»", "a" in Recommender.buildTaste(after).seenIds)
    }

    @Test
    fun `снятие отметки возвращает тайтл в кандидаты`() {
        // Иначе «снять отметку» выглядело бы сломанным ровно так же, как раньше сама
        // отметка: нажал — и ничего не вернулось.
        val marked = markTitleWatched(PersistedState(), card("a", "экшен"), watched = true)
        val cleared = markTitleWatched(marked, card("a", "экшен"), watched = false)
        assertFalse("a" in Recommender.buildTaste(cleared).seenIds)
    }

    @Test
    fun `отметка кладёт тайтл в недавние, не плодя дублей`() {
        val once = markTitleWatched(PersistedState(), card("a", "экшен"), watched = true)
        val twice = markTitleWatched(once, card("a", "экшен"), watched = true)
        assertEquals(listOf("a"), twice.history.map { it.id })
    }

    @Test
    fun `отметка не приписывает человеку просмотренного времени`() {
        // Счётчик серий вырасти обязан — это заявил сам человек. А сколько часов он
        // просидел у экрана, мы не знаем, и придумывать это статистике нельзя.
        val before = PersistedState(watchedMs = 7_200_000, watchedByDay = mapOf("2026-08-18" to 7_200_000))
        val after = markTitleWatched(before, title("a"), watched = true)
        assertEquals(before.watchedMs, after.watchedMs)
        assertEquals(before.watchedByDay, after.watchedByDay)
    }
}

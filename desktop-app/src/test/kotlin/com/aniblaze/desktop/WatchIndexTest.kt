package com.aniblaze.desktop

import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Метка на постере: «Просмотрено» или «Остановились: N серия».
 *
 * Собрать её из одного следа нельзя, и тесты ниже сторожат ровно эти ловушки:
 * досмотренная серия СВОЮ ЗАПИСЬ ПРОГРЕССА УДАЛЯЕТ (остаётся только отметка в
 * `watched`), поэтому по прогрессу «досмотрено до конца» неотличимо от «не начато»;
 * а отметки без общего числа серий не говорят, конец это или середина.
 */
class WatchIndexTest {

    private fun title(id: String) = PersistedAnime(id = id, title = id, poster = "")

    private fun state(
        watched: Set<String> = emptySet(),
        progress: List<ProgressEntry> = emptyList(),
        counts: Map<String, Int> = emptyMap(),
    ) = PersistedState(watched = watched, progress = progress, episodeCounts = counts)

    private fun entry(id: String, segment: Int, fraction: Float, at: Long) = ProgressEntry(
        anime = title(id),
        segment = segment,
        positionMs = (1_400_000 * fraction).toLong(),
        durationMs = 1_400_000,
        updatedAt = at,
    )

    @Test
    fun `досмотренная до титров серия не считается брошенной`() {
        // Серия засчитывается просмотренной с 90 % (saveProgress), а брошенной здесь
        // переставала считаться только с 95 %. В зазор попадают ровно титры: человек
        // досмотрел серию до эндинга, она уже в `watched` — и та же серия одновременно
        // значилась брошенной, потому что запись о прогрессе перебивает досмотренные.
        // Наружу это «Остановились: 3 серия» на досмотренной третьей серии.
        val index = buildWatchIndex(
            state(
                watched = setOf("a#1", "a#2", "a#3"),
                progress = listOf(entry("a", segment = 3, fraction = 0.93f, at = 100)),
                counts = mapOf("a" to 3),
            ),
        )
        val watch = index["a"]!!
        assertTrue("серия досмотрена до титров — тайтл просмотрен", watch.finished)
        assertEquals(3, watch.episode)
    }

    @Test
    fun `пересмотр досмотренной серии с начала — всё ещё остановка`() {
        // Оборотная сторона правила выше: отметки о просмотре мало, нужна ещё и доля.
        // Иначе «вернулся пересматривать первую» уводило бы метку на самую дальнюю.
        val index = buildWatchIndex(
            state(
                watched = setOf("a#1", "a#2", "a#3"),
                progress = listOf(entry("a", segment = 1, fraction = 0.3f, at = 100)),
                counts = mapOf("a" to 12),
            ),
        )
        assertEquals(1, index["a"]!!.episode)
    }

    @Test
    fun `по-настоящему брошенная серия остаётся брошенной`() {
        // Обратная сторона: отметки о просмотре у этой серии НЕТ, значит она честно
        // брошена на середине и подпись про неё нужна.
        val index = buildWatchIndex(
            state(
                watched = setOf("a#1", "a#2"),
                progress = listOf(entry("a", segment = 3, fraction = 0.4f, at = 100)),
                counts = mapOf("a" to 12),
            ),
        )
        val watch = index["a"]!!
        assertTrue(!watch.finished)
        assertEquals(3, watch.episode)
        assertEquals(0.4f, watch.fraction, 0.001f)
    }

    @Test
    fun `все известные серии отмечены — просмотрено`() {
        val index = buildWatchIndex(state(watched = setOf("a#1", "a#2", "a#3"), counts = mapOf("a" to 3)))
        val watch = index["a"]!!
        assertTrue(watch.finished)
        assertEquals(3, watch.episode)
        assertEquals(1f, watch.overall, 0.001f)
    }

    @Test
    fun `серии остались — остановились на последней досмотренной`() {
        val index = buildWatchIndex(state(watched = setOf("a#1", "a#2"), counts = mapOf("a" to 12)))
        val watch = index["a"]!!
        assertTrue("две из двенадцати — это не конец", !watch.finished)
        assertEquals(2, watch.episode)
        // Позади ровно две серии из двенадцати.
        assertEquals(2f / 12f, watch.overall, 0.001f)
    }

    @Test
    fun `брошенная серия важнее номера досмотренной`() {
        // Досмотрели до седьмой, потом вернулись пересматривать первую — «остановились»
        // именно там, где человек сейчас, а не на самой дальней серии.
        val index = buildWatchIndex(
            state(
                watched = setOf("a#1", "a#2", "a#3", "a#4", "a#5", "a#6", "a#7"),
                progress = listOf(entry("a", segment = 1, fraction = 0.3f, at = 900L)),
                counts = mapOf("a" to 12),
            ),
        )
        val watch = index["a"]!!
        assertEquals(1, watch.episode)
        assertTrue(!watch.finished)
    }

    @Test
    fun `из нескольких брошенных берётся самая свежая, а не самая дальняя`() {
        val index = buildWatchIndex(
            state(
                progress = listOf(
                    entry("a", segment = 9, fraction = 0.5f, at = 100L),
                    entry("a", segment = 4, fraction = 0.2f, at = 500L),
                ),
                counts = mapOf("a" to 12),
            ),
        )
        assertEquals(4, index["a"]!!.episode)
    }

    @Test
    fun `почти досмотренная серия не считается брошенной`() {
        // Записи с долей выше 0.95 сохраняются как «уже конец» — тянуть по ним
        // «остановились» нельзя, иначе финал серии выглядел бы как пауза.
        val index = buildWatchIndex(
            state(
                watched = setOf("a#1"),
                progress = listOf(entry("a", segment = 1, fraction = 0.99f, at = 100L)),
                counts = mapOf("a" to 1),
            ),
        )
        assertTrue(index["a"]!!.finished)
    }

    @Test
    fun `фильм — одна доля, без номера серии`() {
        val index = buildWatchIndex(state(progress = listOf(entry("tmdb:1", segment = 1, fraction = 0.42f, at = 1L))))
        val watch = index["tmdb:1"]!!
        assertEquals(0, watch.total)
        assertEquals(0.42f, watch.overall, 0.01f)
    }

    @Test
    fun `нетронутый тайтл не получает метки вовсе`() {
        assertNull(buildWatchIndex(state(counts = mapOf("a" to 12)))["a"])
    }
    @Test
    fun `прыжок в финал не делает тайтл просмотренным`() {
        // Настоящее состояние 24.08 по «Наруто»: 51 серия из 220, и среди них последняя.
        // По правилу «дошли до последнего номера» карточка писала «Просмотрено», пока
        // 169 серий стояли нетронутыми. Считается КОЛИЧЕСТВО, а не номер.
        val index = buildWatchIndex(state(watched = setOf("a#1", "a#2", "a#12"), counts = mapOf("a" to 12)))
        val watch = index["a"]!!
        assertTrue("три из двенадцати — это не «просмотрено»", !watch.finished)
        assertEquals(12, watch.episode)
    }

    @Test
    fun `все серии подряд — просмотрено`() {
        val index = buildWatchIndex(
            state(watched = (1..12).map { "a#$it" }.toSet(), counts = mapOf("a" to 12)),
        )
        assertTrue(index["a"]!!.finished)
    }

}

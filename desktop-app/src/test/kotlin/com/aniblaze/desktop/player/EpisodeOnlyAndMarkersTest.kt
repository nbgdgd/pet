package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.model.TitleComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Только комментарии к текущей серии» и отметки опенинга/эндинга на дорожке. */
class EpisodeOnlyAndMarkersTest {
    private fun c(id: Long, episode: Int) =
        // Текст — нормальной длины: короткие и однословные реплики чат режет сам.
        TitleComment(id = id, author = "a$id", avatar = "", message = "Хорошая серия, мне понравилась концовка номер $id", timestamp = id, votes = 0, episode = episode)

    @Test fun `строгий режим оставляет только реплики с пометкой этой серии`() {
        val pool = listOf(c(1, 0), c(2, 1), c(3, 1), c(4, 2), c(5, 3))
        val strict = chatPicksForEpisode(pool, episode = 1, limit = 50, episodeOnly = true)
        assertEquals(listOf(2L, 3L), strict.map { it.id }.sorted())
        // Обычный режим: без пометки и не позже текущей — как раньше.
        val loose = chatPicksForEpisode(pool, episode = 1, limit = 50)
        assertEquals(listOf(1L, 2L, 3L), loose.map { it.id }.sorted())
        // Серия неизвестна — строгий режим ведёт себя как обычный.
        assertEquals(listOf(1L), chatPicksForEpisode(pool, episode = 0, limit = 50, episodeOnly = true).map { it.id })
    }

    @Test fun `интервалы пропуска ложатся на дорожку по таймингам и не вылезают за края`() {
        val length = 24 * 60_000L
        val (x0, x1) = skipRangeSpan(OpeningRange(90_000, 180_000), length, 1000f)
        assertEquals(62.5f, x0, 0.01f)
        assertEquals(125f, x1, 0.01f)
        // Эндинг уходит за конец серии — обрезается, а не рисуется за дорожкой.
        val (e0, e1) = skipRangeSpan(OpeningRange(23 * 60_000L, 26 * 60_000L), length, 1000f)
        assertEquals(1000f * 23 / 24, e0, 0.01f)
        assertEquals(1000f, e1, 0.01f)
        // Нулевой интервал всё равно виден двумя пикселями; нет длины — ничего.
        val (z0, z1) = skipRangeSpan(OpeningRange(1000, 1000), length, 1000f)
        assertTrue(z1 - z0 >= 2f)
        assertEquals(0f to 0f, skipRangeSpan(OpeningRange(0, 1000), 0L, 1000f))
    }

    private fun assertEquals(expected: Float, actual: Float, delta: Float) =
        assertTrue(kotlin.math.abs(expected - actual) <= delta, "expected $expected, was $actual")
}

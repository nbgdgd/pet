package com.aniblaze.desktop.ui

import com.aniblaze.aggregator.model.Segment
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerScreenLogicTest {
    @Test
    fun `neighbors follow sorted playable episodes rather than assuming contiguous numbers`() {
        val segments = listOf(
            segment(number = 7),
            segment(number = 2),
            segment(number = 5, playable = false),
            segment(number = 4),
        )

        assertEquals(EpisodeNeighbors(previous = 2, next = 7), playableNeighbors(segments, current = 4))
    }

    @Test
    fun `neighbors skip unavailable and duplicate episode entries`() {
        val segments = listOf(
            segment(number = 1),
            segment(number = 2, playable = false),
            segment(number = 3),
            segment(number = 3),
            segment(number = 8),
        )

        assertEquals(EpisodeNeighbors(previous = 1, next = 8), playableNeighbors(segments, current = 3))
    }

    @Test
    fun `neighbors around a missing current episode use nearest playable values`() {
        val segments = listOf(segment(number = 2), segment(number = 6), segment(number = 10))

        assertEquals(EpisodeNeighbors(previous = 2, next = 6), playableNeighbors(segments, current = 4))
    }

    @Test
    fun `first last and empty episode lists disable the correct navigation buttons`() {
        val segments = listOf(segment(number = 2), segment(number = 6))

        assertEquals(EpisodeNeighbors(previous = null, next = 6), playableNeighbors(segments, current = 2))
        assertEquals(EpisodeNeighbors(previous = 2, next = null), playableNeighbors(segments, current = 6))
        assertEquals(
            EpisodeNeighbors(previous = null, next = null),
            playableNeighbors(listOf(segment(number = 1, playable = false)), current = 1),
        )
    }

    @Test
    fun `cinema label uses catalog title and falls back to episode number`() {
        val segments = listOf(segment(number = 12, title = "Сезон 2 · Серия 4"))

        assertEquals("Сезон 2 · Серия 4", episodeLabel(segments, current = 12, cinema = true))
        assertEquals("Серия 13", episodeLabel(segments, current = 13, cinema = true))
    }

    @Test
    fun `anime label stays a concise episode number even when catalog title differs`() {
        val segments = listOf(segment(number = 3, title = "Неожиданная встреча"))

        assertEquals("Серия 3", episodeLabel(segments, current = 3, cinema = false))
    }

    @Test
    fun `resolver fallback replaces requested source with the source actually playing`() {
        val sources = listOf("AniLibria", "Anixart", "Kodik")

        assertEquals("Anixart", resolvedSourcePreference("AniLibria", "anixart", sources))
        assertEquals("AniLibria", resolvedSourcePreference("AniLibria", "unknown", sources))
        assertEquals(null, resolvedSourcePreference(null, "Anixart", sources))
    }

    @Test
    fun `resolved translation id becomes the committed player id`() {
        assertEquals("title:t17", resolvedPlayerId("title", "title:t3", translationId = 17))
        assertEquals("title:t3", resolvedPlayerId("title", "title:t3", translationId = null))
    }

    private fun segment(
        number: Int,
        title: String = "Серия $number",
        playable: Boolean = true,
    ) = Segment(
        id = "episode-$number",
        contentId = "title-1",
        number = number,
        title = title,
        playable = playable,
    )

    @Test
    fun `hud counter shows total episodes and, for ongoing, how many are out`() {
        assertEquals("Серия 3 / 12", com.aniblaze.desktop.player.episodeCounterLabel(3, 12, 12))
        assertEquals("Серия 3 / 5 из 12", com.aniblaze.desktop.player.episodeCounterLabel(3, 5, 12))
        assertEquals("Серия 3 / 5", com.aniblaze.desktop.player.episodeCounterLabel(3, 5, 0))
        assertEquals("Серия 3", com.aniblaze.desktop.player.episodeCounterLabel(3, 0, 0))
        assertEquals("Серии", com.aniblaze.desktop.player.episodeCounterLabel(null, 5, 12))
    }

    @Test
    fun `detail fact chip reads total from catalog and available from segments`() {
        assertEquals("12 серий", episodesFact(available = 12, total = 12))
        assertEquals("5 из 12 серий", episodesFact(available = 5, total = 12))
        assertEquals("0 из 12 серий", episodesFact(available = 0, total = 12))
        assertEquals("1 серия", episodesFact(available = 1, total = 0))
        assertEquals(null, episodesFact(available = 0, total = 0))
    }
}

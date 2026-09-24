package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.TitleComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeasonChatPoolTest {
    private fun comment(
        id: Long,
        episode: Int,
        author: String = "viewer-$id",
        text: String = "Настоящая реплика из серии $episode номер $id",
    ) = TitleComment(
        id = id,
        source = "anixart",
        authorId = "u-$author",
        author = author,
        avatar = "",
        message = text,
        timestamp = 1_700_000_000L + id,
        votes = 0,
        episode = episode,
    )

    @Test
    fun `every episode gets the same complete season pool`() {
        val comments = (1..12).flatMap { episode ->
            (1..3).map { item -> comment(episode * 100L + item, episode) }
        }
        val fromEpisode1 = seasonChatPicks(comments, limit = 100)
        val fromEpisode10 = seasonChatPicks(comments, limit = 100)
        assertEquals(fromEpisode1.map { it.id }, fromEpisode10.map { it.id })
        assertEquals((1..12).toSet(), fromEpisode1.map { it.episode }.toSet())
    }

    @Test
    fun `same text by different real viewers is not collapsed`() {
        val sameWords = "Серия получилась очень сильной сегодня"
        val pool = seasonChatPicks(
            listOf(
                comment(1, 1, author = "alice", text = sameWords),
                comment(2, 1, author = "bob", text = sameWords),
            ),
            limit = 20,
        )
        assertEquals(listOf(1L, 2L), pool.map { it.id })
    }

    @Test
    fun `large season samples the whole depth instead of first rows`() {
        val comments = (1L..1_000L).map { comment(it, ((it - 1) % 24 + 1).toInt()) }
        val pool = seasonChatPicks(comments, limit = 100)
        assertEquals(100, pool.size)
        assertTrue(pool.first().id <= 10L)
        assertTrue(pool.last().id >= 980L, "хвост сезона не попал в срез")
        assertTrue(pool.map { it.episode }.toSet().size >= 20, "срез потерял разнообразие серий")
    }

    @Test
    fun `background growth merges without resetting or duplicating queue`() {
        val first = listOf(comment(1, 1), comment(2, 2))
        val engine = ChatEngine(
            episodeKey = "title:episode-1",
            sourceSize = first.size,
            viewers = 100,
            intensity = ChatIntensity.NORMAL,
            alwaysQuiet = false,
            real = first,
            seed = 42L,
        )
        engine.mergeReal(first + comment(3, 10), sourceCount = 3)
        engine.mergeReal(first + comment(3, 10), sourceCount = 3)
        assertEquals(3, engine.realUsable)
        assertEquals(3, engine.sourceSize)
        assertEquals(0, engine.realShown)
    }
}

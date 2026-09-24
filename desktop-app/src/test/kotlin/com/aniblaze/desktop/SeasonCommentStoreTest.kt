package com.aniblaze.desktop

import com.aniblaze.aggregator.model.TitleComment
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.source.CommentAccumulator
import com.aniblaze.aggregator.source.CommentStop
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonCommentStoreTest {
    private fun comment(id: Long, episode: Int = 0) = TitleComment(
        id = id,
        source = "anixart",
        authorId = "user-$id",
        author = "viewer-$id",
        avatar = "https://cdn/$id.png",
        message = "Осмысленная реплика номер $id",
        timestamp = 1_700_000_000L + id,
        votes = id.toInt(),
        replyCount = 2,
        episode = episode,
    )

    @Test
    fun `season key isolates source and release`() {
        assertEquals("anixart:42", seasonCommentKey("Anixart", "42"))
        assertFalse(seasonCommentKey("anixart", "42") == seasonCommentKey("anixart", "43"))
        assertFalse(seasonCommentKey("anixart", "42") == seasonCommentKey("other", "42"))
    }

    @Test
    fun `foreign source ambiguity never mixes neighbouring seasons`() {
        val requested = Anime("al:7", "Герой — сезон 2", "", year = 2026)
        val first = Anime("ax:1", "Герой 1 сезон", "", year = 2026)
        val second = Anime("ax:2", "Герой 2 сезон", "", year = 2026)
        assertEquals(second, selectCommentRelease(requested, listOf(first, second)))

        val ambiguous = requested.copy(title = "Герой")
        assertNull(selectCommentRelease(ambiguous, listOf(first, second)))
        assertNull(selectCommentRelease(requested, listOf(first)))
    }

    @Test
    fun `exact foreign title wins before loose season guessing`() {
        val requested = Anime("al:9", "Герой: Финальная глава", "", year = 2026)
        val exact = Anime("ax:9", "Герой: Финальная глава", "", year = 2026)
        val neighbour = Anime("ax:8", "Герой: Новая глава", "", year = 2026)
        assertEquals(exact, selectCommentRelease(requested, listOf(neighbour, exact)))
    }

    @Test
    fun `season survives app restart with traversal position`() = withStore { directory ->
        val savedAt = 123_000L
        val first = SeasonCommentStore(directory) { savedAt }
        val key = seasonCommentKey("anixart", "season-10")
        first.save(
            key,
            StoredCommentSeason(
                comments = listOf(comment(1, 1), comment(2, 10)),
                nextPage = 37,
                rawSeen = 775,
                totalPages = 80,
                totalCount = 1_990,
                stop = null,
                updatedAt = savedAt,
            ),
        )

        // Новый объект имитирует новый процесс приложения.
        val loaded = assertNotNull(SeasonCommentStore(directory) { savedAt + 1 }.load(key))
        assertEquals(listOf(1L, 2L), loaded.comments.map { it.id })
        assertEquals(listOf(1, 10), loaded.comments.map { it.episode })
        assertEquals(37, loaded.nextPage)
        assertEquals(775, loaded.rawSeen)
        assertEquals("user-2", loaded.comments.last().authorId)
    }

    @Test
    fun `cached and network copies merge without duplicates`() = withStore { directory ->
        val key = seasonCommentKey("anixart", "77")
        val store = SeasonCommentStore(directory)
        store.save(key, StoredCommentSeason(listOf(comment(1), comment(2)), 2, 50, 3, 75, null, 1L))
        val accumulator = CommentAccumulator()
        accumulator.add(assertNotNull(store.load(key)).comments)
        accumulator.add(listOf(comment(2), comment(3)))
        assertEquals(listOf(1L, 2L, 3L), accumulator.snapshot().map { it.id })
    }

    @Test
    fun `fresh and stale snapshots are distinguished`() = withStore { directory ->
        val now = 10L * 60L * 60L * 1_000L
        val store = SeasonCommentStore(directory) { now }
        assertFalse(store.isStale(now - 60_000L))
        assertTrue(store.isStale(now - 7L * 60L * 60L * 1_000L))
    }

    @Test
    fun `different seasons never read each others file`() = withStore { directory ->
        val store = SeasonCommentStore(directory)
        val first = seasonCommentKey("anixart", "season-1")
        val second = seasonCommentKey("anixart", "season-2")
        store.save(first, StoredCommentSeason(listOf(comment(1)), 1, 25, 1, 1, CommentStop.LAST_PAGE, 1L))
        assertEquals(1L, assertNotNull(store.load(first)).comments.single().id)
        assertNull(store.load(second))
    }

    private fun withStore(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("aniblaze-comment-season-").toFile().canonicalFile
        try {
            block(directory)
        } finally {
            check(directory.name.startsWith("aniblaze-comment-season-"))
            directory.deleteRecursively()
        }
    }
}

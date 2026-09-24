package com.aniblaze.desktop

import com.aniblaze.desktop.player.PlaybackCheckpoint
import com.aniblaze.desktop.ui.episodeFromMediaKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.test.Test

/** Regression coverage for the episode-number/progress mismatch from the desktop player. */
class EpisodeProgressConsistencyTest {
    private val duration = 1_000_000L
    private val t0 = 1_800_000_000_000L

    private fun card(id: String) = PersistedAnime(id = id, title = id, poster = "")

    private fun resume(state: PersistedState, id: String, total: Int): Int? =
        resumeSegmentOf(
            state = state,
            contentId = id,
            prefKey = id,
            segments = (1..total).toList(),
            playable = { true },
        )

    private fun save(
        state: PersistedState,
        id: String,
        episode: Int,
        position: Long,
        at: Long = t0,
    ) = applyEpisodeProgress(
        state = state,
        card = card(id),
        segment = episode,
        positionMs = position,
        durationMs = duration,
        now = at,
        day = "2026-09-02",
    )

    @Test
    fun `частично просмотренная 10 сохраняется и после перезапуска продолжается как 10`() {
        val before = PersistedState(
            playerPrefs = mapOf("ax:show" to PlayerPref(lastSegment = 10)),
            episodeCounts = mapOf("ax:show" to 11),
            watchedAtBackfilled = true,
        )
        val saved = save(before, "ax:show", episode = 10, position = 420_000)

        // Real persistence round-trip: old JSON field names and defaults stay intact.
        val encoded = Json.encodeToString(PersistedState.serializer(), saved)
        val restored = Json.decodeFromString(PersistedState.serializer(), encoded)

        assertEquals(420_000, restored.progress.single().positionMs)
        assertEquals(10, restored.progress.single().segment)
        assertFalse(watchedKey("ax:show", 10) in restored.watched)
        assertEquals(10, resume(restored, "ax:show", total = 11))
    }

    @Test
    fun `досмотренная 10 отмечается просмотренной и кнопка предлагает существующую 11`() {
        val partial = save(
            PersistedState(
                playerPrefs = mapOf("ax:show" to PlayerPref(lastSegment = 10)),
                episodeCounts = mapOf("ax:show" to 11),
                watchedAtBackfilled = true,
            ),
            "ax:show",
            episode = 10,
            position = 420_000,
        )
        val completed = save(partial, "ax:show", episode = 10, position = 950_000, at = t0 + 10_000)

        assertTrue(watchedKey("ax:show", 10) in completed.watched)
        assertTrue(completed.progress.none { it.anime.id == "ax:show" && it.segment == 10 })
        assertEquals(11, resume(completed, "ax:show", total = 11))
    }

    @Test
    fun `после последней 10 не создаётся несуществующая 11`() {
        val completed = save(
            PersistedState(
                playerPrefs = mapOf("ax:show" to PlayerPref(lastSegment = 10)),
                episodeCounts = mapOf("ax:show" to 10),
                watchedAtBackfilled = true,
            ),
            "ax:show",
            episode = 10,
            position = 950_000,
        )

        val target = resume(completed, "ax:show", total = 10)
        assertEquals("повтор последней допустим, выдуманная 11-я — нет", 10, target)
        assertTrue(target in 1..10)
    }

    @Test
    fun `после выхода 11 она автоматически становится следующей`() {
        val whenTenWasLast = save(
            PersistedState(
                playerPrefs = mapOf("ax:show" to PlayerPref(lastSegment = 10)),
                episodeCounts = mapOf("ax:show" to 10),
                watchedAtBackfilled = true,
            ),
            "ax:show",
            episode = 10,
            position = 950_000,
        )

        assertEquals(10, resume(whenTenWasLast, "ax:show", total = 10))
        assertEquals(11, resume(whenTenWasLast, "ax:show", total = 11))
    }

    @Test
    fun `прогресс разных тайтлов не смешивается`() {
        val a = save(PersistedState(watchedAtBackfilled = true), "ax:a", 10, 300_000)
        val both = save(a, "ax:b", 3, 600_000, at = t0 + 1_000)
        val bCompleted = save(both, "ax:b", 3, 950_000, at = t0 + 2_000)

        assertEquals(300_000, bCompleted.progress.single { it.anime.id == "ax:a" }.positionMs)
        assertFalse(watchedKey("ax:a", 10) in bCompleted.watched)
        assertTrue(watchedKey("ax:b", 3) in bCompleted.watched)
    }

    @Test
    fun `медиаключ с двоеточием сохраняет номер фактически играющей серии`() {
        val checkpoint = PlaybackCheckpoint("ax:20419:10", 420_000, duration)

        assertEquals(10, episodeFromMediaKey("ax:20419", checkpoint.mediaKey))
        assertNull(episodeFromMediaKey("ax:other", checkpoint.mediaKey))
        assertNull(episodeFromMediaKey("ax:20419", "ax:20419:not-a-number"))
    }
}

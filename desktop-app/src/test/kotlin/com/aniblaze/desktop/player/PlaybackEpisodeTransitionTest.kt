package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.desktop.AppSettings
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.*

class PlaybackEpisodeTransitionTest {
    private val ep9 = "ax:1152:9"
    private val ep10 = "ax:1152:10"

    @Test fun `recorded 9 to 10 transition cannot auto skip new credits with the old clock`() {
        val guard = PlaybackEpochGuard()
        val ninth = guard.prepare(ep9).also { assertTrue(guard.activate(it)) }
        val oldPosition = 1_334_361L
        val newCredits = OpeningRange(1_315_000, 1_420_000)
        // This was the exact false-positive before the media-identity gate existed.
        assertTrue(shouldAutoSkipOpening(newCredits, oldPosition, false, true))
        assertFalse(guard.accepts(ninth, ep10), "Even before the reset effect, episode 9 cannot drive episode 10")
        val saved = assertNotNull(previousEpisodeCheckpoint(ep9, ep10, oldPosition, 1_440_022))
        assertEquals(ep9, saved.mediaKey)
        assertEquals(oldPosition, saved.positionMs, "Must not save the queued ED target as old progress")
        guard.invalidate()
        assertFalse(guard.accepts(ninth, ep9))
        val tenth = guard.prepare(ep10)
        assertFalse(guard.accepts(tenth, ep10), "Native player has not opened it yet")
        assertTrue(guard.activate(tenth))
        assertFalse(guard.accepts(ninth, ep10), "A delayed old poll is still invalid")
        assertTrue(guard.accepts(tenth, ep10))
        assertFalse(shouldAutoSkipOpening(newCredits, 0, false, true))
    }

    @Test fun `unseen next episode remains unwatched after persistence and restart`() {
        val file = Files.createTempDirectory("episode-transition-").resolve("state.json").toFile()
        val settings = AppSettings(file)
        val anime = Anime(id = "ax:1152", title = "Тест", poster = "", description = "", rating = 0.0)
        val saved = assertNotNull(previousEpisodeCheckpoint(ep9, ep10, 1_334_361, 1_440_022))
        settings.saveProgress(anime, 9, saved.positionMs, saved.durationMs)
        settings.saveProgress(anime, 10, 10_000, 1_440_105)
        settings.flush()
        val restored = AppSettings(file)
        assertFalse("ax:1152#10" in restored.state.value.watched)
        assertEquals(10_000L, restored.progressMs("ax:1152", 10))
    }

    @Test fun `returning to same episode rejects old replies from its earlier opening`() {
        val guard = PlaybackEpochGuard()
        val first = guard.prepare(ep10).also(guard::activate)
        val other = guard.prepare(ep9).also(guard::activate)
        val again = guard.prepare(ep10).also(guard::activate)
        assertFalse(guard.accepts(first, ep10))
        assertFalse(guard.accepts(other, ep10))
        assertTrue(guard.accepts(again, ep10))
    }

    @Test fun `quality restart invalidates old same-episode polls and late end events`() {
        val guard = PlaybackEpochGuard()
        val old = guard.prepare(ep10).also(guard::activate)
        val quality = guard.prepare(ep10)
        assertFalse(guard.accepts(old, ep10))
        assertFalse(guard.accepts(quality, ep10))
        guard.activate(quality)
        assertTrue(guard.accepts(quality, ep10))
        assertFalse(guard.accepts(old, ep10))
    }

    @Test fun `queued obsolete play cannot activate after a newer request`() {
        val guard = PlaybackEpochGuard()
        val old = guard.prepare(ep9)
        val current = guard.prepare(ep10)
        assertFalse(guard.activate(old))
        assertTrue(guard.activate(current))
        assertEquals(current, guard.activeEpoch())
    }

    @Test fun `pending old seek is dropped before a new stream opens`() {
        val tasks = ArrayDeque<Runnable>()
        val seeks = mutableListOf<Long>()
        val command = ConflatedLongCommand(Executor { tasks.add(it) }) { seeks.add(it) }
        command.submit(1_420_000)
        command.clear()
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertTrue(seeks.isEmpty())
        command.submit(92_233)
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(listOf(92_233L), seeks)
    }

    @Test fun `resume remains specific to the new episode while retry keeps current progress`() {
        assertEquals(300_000, resumeTargetMs(false, true, true, 1_420_000, 300_000, 1_420_000))
        assertEquals(300_000, resumeTargetMs(true, false, false, 0, 0, 300_000))
        assertEquals(450_000, resumeTargetMs(false, false, true, 450_000, 300_000, 0))
        assertNull(previousEpisodeCheckpoint(ep10, ep10, 450_000, 1_440_000))
        assertNull(previousEpisodeCheckpoint(null, ep10, 0, 0))
    }

    @Test fun `pause permits analysis but playback buffering end and stale media do not`() {
        assertTrue(canAnalyzeTimings(true, false, false, true))
        assertFalse(canAnalyzeTimings(true, true, false, true))
        assertFalse(canAnalyzeTimings(false, false, false, true), "buffering is not user pause")
        assertFalse(canAnalyzeTimings(true, false, true, true))
        assertFalse(canAnalyzeTimings(true, false, false, false))
    }

    @Test fun `opening at zero waits for duration and native seek readiness before consuming the skip`() {
        val opening = OpeningRange(0, 92_233)
        assertTrue(shouldAutoSkipOpening(opening, 0, false, true))
        assertEquals(0, guardedSeekTarget(opening.endMs, 0), "The old command was silently clamped to zero")
        assertFalse(canAutoSkipMedia(true, 0, true, true, false))
        assertFalse(canAutoSkipMedia(true, 1_440_000, false, true, false))
        assertFalse(canAutoSkipMedia(true, 1_440_000, true, false, false))
        assertFalse(canAutoSkipMedia(false, 1_440_000, true, true, false))
        assertTrue(canAutoSkipMedia(true, 1_440_000, true, true, false))
        assertTrue(canAutoSkipMedia(true, 1_440_000, true, false, true), "Resume inside OP may be paused")
        assertEquals(92_233, guardedSeekTarget(opening.endMs, 1_440_000))
    }
}

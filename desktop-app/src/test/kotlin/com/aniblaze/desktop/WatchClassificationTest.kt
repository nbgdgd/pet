package com.aniblaze.desktop

import com.aniblaze.desktop.ui.buildStats
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.PersonCredit
import com.aniblaze.aggregator.model.StudioCredit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.nio.file.Files
import kotlin.test.Test

class WatchClassificationTest {
    private val card = PersistedAnime(
        id = "ax:test", title = "Test", poster = "", year = 2024,
        episodesTotal = 12, episodesAvailable = 12, airingStatus = 1,
    )

    private fun checkpoint(
        state: PersistedState,
        position: Long,
        at: Long,
        measured: Long,
        duration: Long = 1_000_000L,
    ) = applyEpisodeProgress(state, card, 1, position, duration, at, "2026-09-07", measured)

    @Test fun `opening and fifty percent are in progress not watched`() {
        val opened = checkpoint(PersistedState(history = listOf(card)), 10_000, 10_000, 0)
        val half = checkpoint(opened, 500_000, 20_000, 10_000)
        assertFalse("ax:test#1" in half.watched)
        assertEquals(500_000L, half.progress.single().positionMs)
        assertEquals(0, buildStats(half).titles)
    }

    @Test fun `seek straight to end does not mark watched`() {
        val started = checkpoint(PersistedState(history = listOf(card)), 20_000, 10_000, 0)
        val sought = checkpoint(started, 995_000, 12_000, 2_000)
        val ended = checkpoint(sought, 1_000_000, 13_000, 0)
        val confirmed = applyEpisodeEnded(ended, card.id, 1, 14_000)
        assertFalse("ax:test#1" in confirmed.watched)
        assertEquals(1f, confirmed.progress.single().fraction, 0.001f)
    }

    @Test fun `late checkpoint does not recreate resume point for watched episode`() {
        val watched = PersistedState(
            history = listOf(card),
            watched = setOf("ax:test#1"),
        )

        val delayed = checkpoint(watched, 999_000, 10_000, 0)

        assertTrue("ax:test#1" in delayed.watched)
        assertTrue(delayed.progress.none { it.anime.id == card.id && it.segment == 1 })
    }

    @Test fun `end event removes stale resume point from already watched episode`() {
        val stale = PersistedState(
            history = listOf(card),
            watched = setOf("ax:test#1"),
            progress = listOf(
                ProgressEntry(card, 1, 999_000, 1_000_000, 10_000, verifiedPlaybackMs = 49_000),
            ),
        )

        val ended = applyEpisodeEnded(stale, card.id, 1, 20_000)

        assertTrue("ax:test#1" in ended.watched)
        assertTrue(ended.progress.none { it.anime.id == card.id && it.segment == 1 })
    }

    @Test fun `stale watched resume cleanup preserves a genuine rewatch`() {
        val state = PersistedState(
            watched = setOf("ax:test#1", "ax:test#2"),
            progress = listOf(
                ProgressEntry(card, 1, 999_000, 1_000_000, 10_000, verifiedPlaybackMs = 49_000),
                ProgressEntry(card, 2, 500_000, 1_000_000, 20_000, verifiedPlaybackMs = 500_000),
                ProgressEntry(card, 3, 999_000, 1_000_000, 30_000, verifiedPlaybackMs = 0),
            ),
        )

        val cleaned = removeStaleWatchedResumePoints(state)

        assertEquals(listOf(2, 3), cleaned.progress.map { it.segment })
    }

    @Test fun `startup cleanup is persisted and preserves a genuine rewatch after restart`() {
        val file = Files.createTempDirectory("aniblaze-watch-cleanup-").resolve("state.json").toFile()
        val stale = PersistedState(
            watched = setOf("ax:test#1", "ax:test#2"),
            watchedAt = mapOf("ax:test#1" to 10_000L, "ax:test#2" to 20_000L),
            watchedAtBackfilled = true,
            vostMergeMigrated = true,
            progress = listOf(
                ProgressEntry(card, 1, 850_000, 1_000_000, 10_000, verifiedPlaybackMs = 49_000),
                ProgressEntry(card, 2, 500_000, 1_000_000, 20_000, verifiedPlaybackMs = 500_000),
            ),
        )
        file.writeText(Json.encodeToString(PersistedState.serializer(), stale))

        val loaded = AppSettings(file).state.value
        assertEquals(listOf(2), loaded.progress.map { it.segment })

        val persisted = Json.decodeFromString(PersistedState.serializer(), file.readText())
        assertEquals(listOf(2), persisted.progress.map { it.segment })

        val restarted = AppSettings(file).state.value
        assertEquals(listOf(2), restarted.progress.map { it.segment })
        assertEquals(500_000L, restarted.progress.single().positionMs)
    }

    @Test fun `reliable end event completes a genuine near end resume`() {
        val resumed = checkpoint(PersistedState(history = listOf(card)), 950_000, 10_000, 0)
        val played = checkpoint(resumed, 980_000, 40_000, 30_000)
        assertFalse("ax:test#1" in played.watched)
        val ended = applyEpisodeEnded(played, card.id, 1, 41_000)
        assertTrue("ax:test#1" in ended.watched)
        assertTrue(ended.progress.none { it.anime.id == card.id && it.segment == 1 })
    }

    @Test fun `real playback through ninety percent marks episode`() {
        var state = PersistedState(history = listOf(card))
        var at = 0L
        for (position in 10_000L..900_000L step 10_000L) {
            at += 10_000
            state = checkpoint(state, position, at, if (position == 10_000L) 0 else 10_000)
        }
        assertTrue("ax:test#1" in state.watched)
        assertTrue(state.progress.none { it.anime.id == card.id && it.segment == 1 })
        assertEquals(1, buildStats(state).titles)
    }

    @Test fun `one of twelve is not completed and twelve of twelve is completed`() {
        val one = PersistedState(
            history = listOf(card), watched = setOf("ax:test#1"),
            episodeCounts = mapOf(card.id to 12),
        )
        assertEquals(1, buildStats(one).titles)
        assertEquals(0, buildStats(one).finishedTitles)

        val all = one.copy(watched = (1..12).map { "ax:test#$it" }.toSet())
        assertEquals(1, buildStats(all).finishedTitles)
    }

    @Test fun `eleven of twelve and caught up ongoing are not completed`() {
        val eleven = PersistedState(
            history = listOf(card), watched = (1..11).map { "ax:test#$it" }.toSet(),
            episodeCounts = mapOf(card.id to 12),
        )
        assertEquals(0, buildStats(eleven).finishedTitles)

        val ongoing = card.copy(airingStatus = 2, episodesTotal = 24, episodesAvailable = 12)
        val caughtUp = eleven.copy(
            history = listOf(ongoing), watched = (1..12).map { "ax:test#$it" }.toSet(),
        )
        assertEquals(0, buildStats(caughtUp).finishedTitles)
    }

    @Test fun `verified playback survives restart and duration correction`() {
        val first = checkpoint(PersistedState(history = listOf(card)), 100_000, 100_000, 0)
        val moving = checkpoint(first, 120_000, 120_000, 20_000)
        val restored = Json.decodeFromString(
            PersistedState.serializer(),
            Json.encodeToString(PersistedState.serializer(), moving),
        )
        assertEquals(20_000L, restored.progress.single().verifiedPlaybackMs)
        val corrected = checkpoint(restored, 140_000, 140_000, 20_000, duration = 1_020_000L)
        assertEquals(40_000L, corrected.progress.single().verifiedPlaybackMs)
    }

    @Test fun `studio and director pages participate in normal back navigation`() {
        val nav = NavController()
        val title = Anime(id = "ax:test", title = "Test", poster = "")
        nav.openTitle(title, isCinema = false)
        nav.openStudio(StudioCredit(11, "Madhouse"))
        nav.openPerson(PersonCredit(1, "Director"))
        assertTrue(nav.current is Screen.Person)
        assertTrue(nav.back())
        assertTrue(nav.current is Screen.Studio)
        assertTrue(nav.back())
        assertTrue(nav.current is Screen.Detail)
    }
}

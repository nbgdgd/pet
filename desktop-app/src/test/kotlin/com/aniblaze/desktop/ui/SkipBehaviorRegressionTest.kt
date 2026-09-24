package com.aniblaze.desktop.ui

import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.source.AniskipTimings
import com.aniblaze.desktop.player.guardedSeekTarget
import com.aniblaze.desktop.player.openingSkipTarget
import com.aniblaze.desktop.player.shouldAutoSkipOpening
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OP/ED behaviour independently of VLC/network timing. */
class SkipBehaviorRegressionTest {
    private val opening = OpeningRange(startMs = 60_000L, endMs = 150_000L)
    private val ending = OpeningRange(startMs = 1_380_000L, endMs = 1_480_000L)

    @Test
    fun `auto skip OP works at normal start and after a saved-position resume`() {
        assertTrue(shouldAutoSkipOpening(opening, positionMs = 60_000L, alreadySkipped = false, enabled = true))
        assertTrue(shouldAutoSkipOpening(opening, positionMs = 104_000L, alreadySkipped = false, enabled = true))
        assertEquals(150_000L, openingSkipTarget(opening, positionMs = 104_000L))
    }

    @Test
    fun `start after OP does not seek backwards`() {
        assertFalse(shouldAutoSkipOpening(opening, positionMs = 150_000L, alreadySkipped = false, enabled = true))
        assertNull(openingSkipTarget(opening, positionMs = 150_000L))
    }

    @Test
    fun `manual seek back into already skipped OP cannot create a skip loop`() {
        assertFalse(shouldAutoSkipOpening(opening, positionMs = 75_000L, alreadySkipped = true, enabled = true))
    }

    @Test
    fun `exact ED uses the same stable auto-skip rule`() {
        assertTrue(shouldAutoSkipOpening(ending, positionMs = 1_410_000L, alreadySkipped = false, enabled = true))
        assertEquals(1_480_000L, openingSkipTarget(ending, positionMs = 1_410_000L))
    }

    @Test
    fun `manual ED skip preserves post-credit scene and end event tail`() {
        // ED ends twenty seconds before the real end: do not remove that post-credit.
        assertEquals(1_480_000L, guardedSeekTarget(requestedMs = 1_480_000L, durationMs = 1_500_000L))
        // If timing touches the final frame, keep three seconds so VLC still finishes normally.
        assertEquals(1_497_000L, guardedSeekTarget(requestedMs = 1_500_000L, durationMs = 1_500_000L))
    }

    @Test
    fun `missing or borrowed OP ED ranges never auto skip`() {
        assertFalse(shouldAutoSkipOpening(null, positionMs = 60_000L, alreadySkipped = false, enabled = true))
        assertFalse(
            shouldAutoSkipOpening(
                ending.copy(approximate = true),
                positionMs = 1_410_000L,
                alreadySkipped = false,
                enabled = true,
            ),
        )
    }

    @Test
    fun `late timing is bound to its own playing episode`() {
        val episodeOne = AniskipTimings.SkipTimings(opening = OpeningRange(10_000L, 90_000L))
        val episodeTwo = AniskipTimings.SkipTimings(ending = OpeningRange(1_300_000L, 1_400_000L))
        val timings = mapOf(1 to episodeOne, 2 to episodeTwo)

        assertEquals(episodeOne, skipTimingsForEpisode(timings, episode = 1))
        assertEquals(episodeTwo, skipTimingsForEpisode(timings, episode = 2))
        assertEquals(AniskipTimings.SkipTimings.EMPTY, skipTimingsForEpisode(timings, episode = 3))
    }
}

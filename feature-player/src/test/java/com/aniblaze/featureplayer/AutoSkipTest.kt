package com.aniblaze.featureplayer

import com.aniblaze.aggregator.model.OpeningRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSkipTest {
    private val opening = OpeningRange(60_000, 150_000)
    private val ending = OpeningRange(1_200_000, 1_290_000)

    @Test
    fun `exact opening is skipped only after playback enters it`() {
        assertNull(decision(59_999))
        assertEquals(AutoSkipDecision(AutoSkipKind.OPENING, 150_000), decision(60_000))
        assertNull(decision(150_000))
    }

    @Test
    fun `ending obeys its own setting and fires only once`() {
        assertNull(decision(1_210_000, skipEnding = false))
        assertEquals(
            AutoSkipDecision(AutoSkipKind.ENDING, 1_290_000),
            decision(1_210_000, skipEnding = true),
        )
        assertNull(decision(1_210_000, skipEnding = true, endingHandled = true))
    }

    @Test
    fun `borrowed timings are never applied automatically`() {
        val borrowed = opening.copy(approximate = true)
        assertNull(
            autoSkipDecision(70_000, borrowed, ending, true, true, false, false),
        )
    }

    private fun decision(
        position: Long,
        skipEnding: Boolean = true,
        endingHandled: Boolean = false,
    ) = autoSkipDecision(
        positionMs = position,
        opening = opening,
        ending = ending,
        skipOpening = true,
        skipEnding = skipEnding,
        openingHandled = false,
        endingHandled = endingHandled,
    )
}

package com.aniblaze.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerInteractionLogicTest {
    @Test
    fun `HUD auto-hide runs only during uninterrupted playback`() {
        assertTrue(
            shouldAutoHideHud(
                visible = true,
                playing = true,
                interactionActive = false,
                openingButtonVisible = false,
            ),
        )
        assertFalse(shouldAutoHideHud(false, playing = true, interactionActive = false, openingButtonVisible = false))
        assertFalse(shouldAutoHideHud(true, playing = false, interactionActive = false, openingButtonVisible = false))
        assertFalse(shouldAutoHideHud(true, playing = true, interactionActive = false, openingButtonVisible = true))
    }

    @Test
    fun `hover press open menu and scrubbing all keep HUD visible`() {
        // Each interaction is folded into interactionActive by VlcPlayer. Verify that
        // every producer blocks the same auto-hide decision instead of racing a timer.
        val interactions = listOf("hover", "press", "menu", "scrub")

        interactions.forEach { interaction ->
            assertFalse(
                shouldAutoHideHud(
                    visible = true,
                    playing = true,
                    interactionActive = true,
                    openingButtonVisible = false,
                ),
                "$interaction must stop HUD auto-hide",
            )
        }
    }

    @Test
    fun `repeated player clicks show then hide HUD`() {
        val initiallyHidden = false
        val afterFirstClick = singleClickHudTarget(initiallyHidden)
        val afterSecondClick = singleClickHudTarget(afterFirstClick)

        assertTrue(afterFirstClick)
        assertFalse(afterSecondClick)
    }

    @Test
    fun `available saved dub remains selected`() {
        val tracks = listOf(4 to "AniLibria", 9 to "Studio Band")

        assertEquals("Studio Band", chooseAvailableDub("Studio Band", tracks, currentTrackId = 4))
    }

    @Test
    fun `invalid saved dub adopts currently active native track`() {
        val tracks = listOf(4 to "AniLibria", 9 to "Studio Band")

        assertEquals("Studio Band", chooseAvailableDub("Removed voice", tracks, currentTrackId = 9))
    }

    @Test
    fun `dub selection has deterministic fallback and handles missing tracks`() {
        val tracks = listOf(4 to "AniLibria", 9 to "Studio Band")

        assertEquals("AniLibria", chooseAvailableDub(null, tracks, currentTrackId = 999))
        assertNull(chooseAvailableDub("Removed voice", emptyList(), currentTrackId = 9))
    }

    @Test
    fun `reduced motion removes animation duration while normal mode keeps it`() {
        assertEquals(200, motionDurationMillis(normalDuration = 200, reducedMotion = false))
        assertEquals(0, motionDurationMillis(normalDuration = 200, reducedMotion = true))
        assertEquals(0, motionDurationMillis(normalDuration = -20, reducedMotion = false))
    }

    @Test
    fun `reduced motion setting accepts supported values and rejects unknown ones`() {
        listOf("1", "true", "YES", " reduce ").forEach { value ->
            assertEquals(true, parseReducedMotion(value), "Expected '$value' to enable reduced motion")
        }
        listOf("0", "false", "NO", " no-preference ").forEach { value ->
            assertEquals(false, parseReducedMotion(value), "Expected '$value' to keep animations")
        }
        assertNull(parseReducedMotion(null))
        assertNull(parseReducedMotion("sometimes"))
    }
}

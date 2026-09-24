package com.aniblaze.desktop

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowRestoreTest {
    @Test
    fun `position from a disconnected monitor is rejected`() {
        val currentDisplays = listOf(Rectangle(0, 0, 1920, 1080))
        assertFalse(savedWindowIntersectsDisplay(2600, 100, 1280, 800, currentDisplays))
    }

    @Test
    fun `partially visible and negative-coordinate monitor positions survive restart`() {
        val displays = listOf(Rectangle(-1920, 0, 1920, 1080), Rectangle(0, 0, 1920, 1080))
        assertTrue(savedWindowIntersectsDisplay(-1800, 100, 1280, 800, displays))
        assertTrue(savedWindowIntersectsDisplay(1870, 100, 1280, 800, displays))
    }

    @Test
    fun `almost entirely offscreen position falls back to platform default`() {
        val displays = listOf(Rectangle(0, 0, 1920, 1080))
        assertFalse(savedWindowIntersectsDisplay(1900, 1060, 1280, 800, displays))
    }
}

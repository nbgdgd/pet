package com.aniblaze.player

import kotlin.math.roundToInt

/**
 * Pure gesture math, kept UI-framework-free so it is trivially unit-testable.
 * The Compose layer feeds raw drag deltas in pixels and receives intents.
 */
object PlayerGestures {

    /** Seconds of seek per full screen-width horizontal drag. */
    private const val SECONDS_PER_WIDTH = 90f

    sealed interface Zone {
        /** Left half of the screen controls brightness. */
        data object Brightness : Zone
        /** Right half controls volume. */
        data object Volume : Zone
    }

    fun zoneFor(touchX: Float, screenWidth: Float): Zone =
        if (touchX < screenWidth / 2f) Zone.Brightness else Zone.Volume

    /** Converts a horizontal drag (px) into a seek delta in milliseconds. */
    fun horizontalSeekMs(dragX: Float, screenWidth: Float): Long {
        if (screenWidth <= 0f) return 0
        val seconds = (dragX / screenWidth) * SECONDS_PER_WIDTH
        return (seconds * 1000).roundToInt().toLong()
    }

    /**
     * Converts a vertical drag (px, downward positive) into a 0..1 level delta.
     * Dragging up increases the level, so the sign is inverted.
     */
    fun verticalLevelDelta(dragY: Float, screenHeight: Float): Float {
        if (screenHeight <= 0f) return 0f
        return -(dragY / screenHeight)
    }

    /** Double-tap skip amount in milliseconds. */
    const val DOUBLE_TAP_SKIP_MS = 10_000L

    /** Available long-press speed steps. */
    val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
}

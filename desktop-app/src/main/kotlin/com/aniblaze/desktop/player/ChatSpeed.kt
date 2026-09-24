package com.aniblaze.desktop.player

/** Message cadence only; unrelated to playback speed or the scene-analysis clock. */
internal val CHAT_SPEED_PRESETS = listOf(0.5f to "0,5×", 1f to "1×", 1.5f to "1,5×", 2f to "2×")
internal fun normalizeChatSpeed(speed: Float): Float = if (speed.isFinite()) speed.coerceIn(0.5f, 2f) else 1f

/** Give real paragraphs time to be read, not the same slot as a two-word reaction. */
internal fun chatReadingTimeMs(text: String, speed: Float): Long {
    val characters = text.count { !it.isWhitespace() }
    return ((1_500L + characters * 55L).coerceIn(2_500L, 90_000L) / normalizeChatSpeed(speed)).toLong()
}

internal fun chatScrollDurationMs(speed: Float): Int = (450 / normalizeChatSpeed(speed)).toInt().coerceIn(225, 900)

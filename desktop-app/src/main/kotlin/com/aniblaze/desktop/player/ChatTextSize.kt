package com.aniblaze.desktop.player

/** Message typography only: header, controls and dock geometry do not scale. */
internal val CHAT_FONT_PRESETS = listOf(11 to "Малый", 13 to "Обычный", 16 to "Крупный", 20 to "Макс.")
internal fun chatLineHeight(fontSize: Int): Float = fontSize * 1.35f

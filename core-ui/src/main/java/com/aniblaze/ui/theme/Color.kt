package com.aniblaze.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * OLED-first palette built around the app icon's orange→purple flame, with a
 * graphite surface ramp and translucent fills for the glassmorphism layer.
 */

// Base / surfaces — true-black for OLED; surfaces carry a faint cool-violet
// undertone so shadows read cinematic rather than flat grey.
val OledBlack = Color(0xFF000000)
val Surface1 = Color(0xFF0C0B12)
val Surface2 = Color(0xFF15141D)
val Surface3 = Color(0xFF1F1E2B)

// Accents — a deliberate ember→violet duo (the "cinema glow"), with a magenta
// bridge for the brand gradient and a cyan spark for highlights.
val AccentOrange = Color(0xFFFF6A3D) // ember
val AccentPurple = Color(0xFF9D5CFF) // electric violet
val AccentPink = Color(0xFFFF4D9D)   // magenta bridge
val AccentCyan = Color(0xFF2DE2E6)

// Text
val TextPrimary = Color(0xFFF7F7FA)
val TextSecondary = Color(0xFF9C9CAB)
val TextTertiary = Color(0xFF5E5E6B)
val ErrorRed = Color(0xFFFF453A)

/**
 * Галка «досмотрено». Зелёный, а не оранжевый: оранжевый в приложении означает
 * «внимание сюда», а досмотренное — ровно наоборот, уже сделанное.
 */
val WatchedGreen = Color(0xFF4ADE80)

// Glassmorphism fills / hairlines
val GlassFill = Color(0x1AFFFFFF)
val GlassFillStrong = Color(0x26FFFFFF)
val GlassBorder = Color(0x24FFFFFF)
val Scrim = Color(0x99000000)

// Glow effects (tuned to the new accents)
val GlowOrange = Color(0x33FF6A3D)
val GlowPurple = Color(0x339D5CFF)
val GlowCyan = Color(0x332DE2E6)

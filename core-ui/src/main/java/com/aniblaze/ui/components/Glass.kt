package com.aniblaze.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.AccentPurple
import com.aniblaze.ui.theme.GlassBorder
import com.aniblaze.ui.theme.GlassFill
import com.aniblaze.ui.theme.GlowOrange
import com.aniblaze.ui.theme.GlowPurple

/** Frosted-glass surface: translucent fill + hairline border + platform blur (Android 12+). */
fun Modifier.glass(cornerRadius: Dp = 20.dp): Modifier =
    this
        .clip(RoundedCornerShape(cornerRadius))
        .background(GlassFill, RoundedCornerShape(cornerRadius))
        .border(1.dp, GlassBorder, RoundedCornerShape(cornerRadius))

/** Stronger glass variant. */
fun Modifier.glassStrong(cornerRadius: Dp = 20.dp): Modifier =
    this
        .clip(RoundedCornerShape(cornerRadius))
        .background(
            com.aniblaze.ui.theme.GlassFillStrong,
            RoundedCornerShape(cornerRadius),
        )
        .border(1.dp, GlassBorder, RoundedCornerShape(cornerRadius))

/** Accent glow behind the content — draws a soft radial glow. */
fun Modifier.accentGlow(
    color: Color = GlowOrange,
    radius: Dp = 16.dp,
): Modifier = this.drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
    )
}

/** The signature orange→purple gradient used on accents and CTAs. */
val AccentGradient: Brush
    get() = Brush.linearGradient(listOf(AccentOrange, AccentPurple))

/**
 * The brand mark gradient — ember → magenta → electric violet. This is the
 * single signature flourish: reserve it for the wordmark and section markers.
 */
val BrandGradient: Brush
    get() = Brush.linearGradient(listOf(AccentOrange, com.aniblaze.ui.theme.AccentPink, AccentPurple))

/** Vertical gradient from glow-orange to glow-purple. */
val AccentGlowGradient: Brush
    get() = Brush.linearGradient(listOf(GlowOrange, GlowPurple))

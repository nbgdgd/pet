package com.aniblaze.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AniBlazeColors = darkColorScheme(
    primary = AccentOrange,
    onPrimary = OledBlack,
    secondary = AccentPurple,
    onSecondary = TextPrimary,
    tertiary = AccentCyan,
    background = OledBlack,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
)

/**
 * Clean, Inter/SF-Pro-style typography. We lean on the system sans (no bundled
 * font) but tune weights, sizes and tracking for a crisp, modern hierarchy.
 */
private val sans = FontFamily.SansSerif
private val AniBlazeTypography = Typography(
    displaySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun AniBlazeTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AniBlazeColors,
        typography = AniBlazeTypography,
        content = content,
    )
}

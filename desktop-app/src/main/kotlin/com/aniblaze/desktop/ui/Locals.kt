package com.aniblaze.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.aniblaze.desktop.AppSettings

/**
 * App settings exposed via a CompositionLocal so leaf composables (e.g. poster
 * cards' right-click menu) can offer favourite/history actions without every
 * screen threading [AppSettings] through its call chain.
 */
val LocalAppSettings = staticCompositionLocalOf<AppSettings?> { null }

package com.aniblaze.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window

/**
 * Paints the native Windows title bar and window border in the app's dark theme via
 * DWM. This removes the default light caption and the thin white top border that
 * Windows 11 draws around decorated windows. No-op on non-Windows / older builds —
 * the DWM attributes are simply ignored there. JNA is already on the classpath (vlcj).
 */
object DwmDark {
    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer?, attr: Int, value: Pointer, size: Int): Int
    }

    private val dwm: Dwmapi? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull()
    }

    // DWM attribute ids.
    private const val USE_IMMERSIVE_DARK_MODE = 20 // Win10 2004+
    private const val BORDER_COLOR = 34            // Win11 22000+
    private const val CAPTION_COLOR = 35           // Win11 22000+
    private const val TEXT_COLOR = 36              // Win11 22000+

    // COLORREF is 0x00BBGGRR. App OLED near-black caption/border, white text.
    private const val DARK = 0x000A0A0A
    private const val WHITE = 0x00FFFFFF

    fun apply(window: Window) {
        val api = dwm ?: return
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        fun set(attr: Int, v: Int) {
            val mem = Memory(4); mem.setInt(0, v)
            runCatching { api.DwmSetWindowAttribute(hwnd, attr, mem, 4) }
        }
        set(USE_IMMERSIVE_DARK_MODE, 1)
        set(CAPTION_COLOR, DARK)
        set(BORDER_COLOR, DARK)
        set(TEXT_COLOR, WHITE)
    }
}

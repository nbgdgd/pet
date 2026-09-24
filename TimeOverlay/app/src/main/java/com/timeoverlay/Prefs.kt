package com.timeoverlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** Единственный источник состояния: настройки, позиция оверлея, флаг включения. */
class Prefs(context: Context) {

    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 0..100 → размер текста [MIN_TEXT_SP]..[MAX_TEXT_SP]. */
    var sizePercent: Int
        get() = sp.getInt(KEY_SIZE, 35)
        set(value) = sp.edit().putInt(KEY_SIZE, value.coerceIn(0, 100)).apply()

    /** 0 — квадрат, 100 — пилюля. */
    var cornerPercent: Int
        get() = sp.getInt(KEY_CORNER, 100)
        set(value) = sp.edit().putInt(KEY_CORNER, value.coerceIn(0, 100)).apply()

    var opacityPercent: Int
        get() = sp.getInt(KEY_OPACITY, 85)
        set(value) = sp.edit().putInt(KEY_OPACITY, value.coerceIn(MIN_OPACITY, 100)).apply()

    var bgColor: Int
        get() = sp.getInt(KEY_BG_COLOR, Color.BLACK)
        set(value) = sp.edit().putInt(KEY_BG_COLOR, value).apply()

    var textColor: Int
        get() = sp.getInt(KEY_TEXT_COLOR, Color.WHITE)
        set(value) = sp.edit().putInt(KEY_TEXT_COLOR, value).apply()

    var graceSeconds: Int
        get() = sp.getInt(KEY_GRACE, 30)
        set(value) = sp.edit().putInt(KEY_GRACE, value).apply()

    var hideOnLauncher: Boolean
        get() = sp.getBoolean(KEY_HIDE_LAUNCHER, true)
        set(value) = sp.edit().putBoolean(KEY_HIDE_LAUNCHER, value).apply()

    var autostart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(value) = sp.edit().putBoolean(KEY_AUTOSTART, value).apply()

    var posX: Int
        get() = sp.getInt(KEY_POS_X, 24)
        set(value) = sp.edit().putInt(KEY_POS_X, value).apply()

    var posY: Int
        get() = sp.getInt(KEY_POS_Y, 200)
        set(value) = sp.edit().putInt(KEY_POS_Y, value).apply()

    val textSizeSp: Float
        get() = MIN_TEXT_SP + (MAX_TEXT_SP - MIN_TEXT_SP) * sizePercent / 100f

    val graceMillis: Long get() = graceSeconds * 1000L

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val NAME = "time_overlay"

        const val KEY_ENABLED = "enabled"
        const val KEY_SIZE = "size_percent"
        const val KEY_CORNER = "corner_percent"
        const val KEY_OPACITY = "opacity_percent"
        const val KEY_BG_COLOR = "bg_color"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_GRACE = "grace_seconds"
        const val KEY_HIDE_LAUNCHER = "hide_on_launcher"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_POS_X = "pos_x"
        const val KEY_POS_Y = "pos_y"

        const val MIN_TEXT_SP = 10f
        const val MAX_TEXT_SP = 34f

        /** Ниже оверлей уже не разглядеть — незачем давать пользователю потерять его. */
        const val MIN_OPACITY = 10

        val PALETTE = intArrayOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFFF5252.toInt(),
            0xFFFFB300.toInt(),
            0xFF4CAF50.toInt(),
            0xFF29B6F6.toInt(),
            0xFF7C4DFF.toInt(),
            0xFFFF4081.toInt()
        )
    }
}

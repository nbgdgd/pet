package com.timeoverlay

import java.util.Locale

/** Форматирование длительности: до часа `4:37`, дальше `1:12:04`. */
object TimeFormat {

    fun format(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Для статистики: `3 ч 12 мин`, `12 мин`, `45 с`. */
    fun formatLong(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return when {
            hours > 0 -> "$hours ч $minutes мин"
            minutes > 0 -> "$minutes мин"
            else -> "$total с"
        }
    }
}

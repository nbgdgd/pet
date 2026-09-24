package com.timeoverlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Определяет приложение на переднем плане через события UsageStats.
 *
 * Каждый опрос берёт события с прошлого запроса (с нахлёстом, чтобы не терять пограничные)
 * и запоминает последнее «приложение вышло на передний план». Если событий нет — держит
 * предыдущее значение, иначе таймер прыгал бы на каждом тике.
 */
class ForegroundAppTracker(context: Context) {

    private val usageStats =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private var lastPackage: String? = null
    private var lastQueryEnd = 0L

    /** @param nowWallClock [System.currentTimeMillis] — UsageStats работает только с ним. */
    fun currentPackage(nowWallClock: Long): String? {
        val begin = if (lastQueryEnd == 0L) nowWallClock - FIRST_LOOKBACK else lastQueryEnd - OVERLAP
        try {
            val events = usageStats.queryEvents(begin, nowWallClock)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                @Suppress("DEPRECATION")
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastPackage = event.packageName
                }
            }
        } catch (e: Exception) {
            // Разрешение отозвали или система отказала — оставляем последнее известное значение.
        }
        lastQueryEnd = nowWallClock
        return lastPackage
    }

    fun forget() {
        lastPackage = null
    }

    private companion object {
        const val FIRST_LOOKBACK = 60_000L
        const val OVERLAP = 2_000L
    }
}

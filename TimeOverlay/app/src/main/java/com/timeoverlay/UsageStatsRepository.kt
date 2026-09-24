package com.timeoverlay

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.util.Calendar

/** Строка статистики: приложение и сколько в нём проведено за период. */
data class AppUsage(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val millis: Long
)

enum class StatsPeriod { TODAY, WEEK, MONTH }

/**
 * Общее время по приложениям из системной статистики Android.
 *
 * Своё время сервис не копит: система и так считает его точнее и хранит месяцами.
 * Показываются только запускаемые приложения — иначе список забивают системные пакеты.
 */
class UsageStatsRepository(context: Context) {

    private val app = context.applicationContext
    private val usageStats =
        app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packages: PackageManager = app.packageManager

    fun load(period: StatsPeriod, now: Long = System.currentTimeMillis()): List<AppUsage> {
        val begin = beginOf(period, now)
        val raw = try {
            usageStats.queryAndAggregateUsageStats(begin, now)
        } catch (e: Exception) {
            emptyMap()
        }
        return raw.values
            .filter { it.totalTimeInForeground >= MIN_MILLIS }
            .mapNotNull { toAppUsage(it.packageName, it.totalTimeInForeground) }
            .sortedByDescending { it.millis }
    }

    private fun toAppUsage(packageName: String, millis: Long): AppUsage? {
        if (packages.getLaunchIntentForPackage(packageName) == null) return null
        val info = try {
            packages.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        return AppUsage(
            packageName = packageName,
            label = packages.getApplicationLabel(info).toString(),
            icon = runCatching { packages.getApplicationIcon(info) }.getOrNull(),
            millis = millis
        )
    }

    private fun beginOf(period: StatsPeriod, now: Long): Long = when (period) {
        StatsPeriod.TODAY -> Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        StatsPeriod.WEEK -> now - 7 * DAY
        StatsPeriod.MONTH -> now - 30 * DAY
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L

        /** Меньше минуты — шум, только мешает читать список. */
        const val MIN_MILLIS = 60_000L
    }
}

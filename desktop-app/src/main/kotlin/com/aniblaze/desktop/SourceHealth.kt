package com.aniblaze.desktop

import androidx.compose.runtime.mutableStateMapOf

/**
 * Здоровье источников: сколько запросов за последний час удалось и сколько упало, и
 * когда источник в последний раз отвечал. Пишется из репозитория на каждый ответ
 * ленты и потока, читается настройками — там же кнопка «проверить».
 *
 * Состояние Compose: панель настроек перерисовывается по мере поступления ответов.
 * Память ограничена: события старше часа выбрасываются при каждой записи.
 */
object SourceHealth {
    data class Snapshot(val ok: Int, val failed: Int, val lastOkAt: Long, val lastFailAt: Long, val lastError: String) {
        val total: Int get() = ok + failed
        /** Доля отказов 0..1; нет данных — 0. */
        val failureShare: Double get() = if (total == 0) 0.0 else failed.toDouble() / total
    }

    private class Log {
        val events = ArrayDeque<Pair<Long, Boolean>>()
        var lastOkAt = 0L
        var lastFailAt = 0L
        var lastError = ""
    }

    private val logs = HashMap<String, Log>()
    /** Снимки для интерфейса — по одному на источник. */
    val snapshots = mutableStateMapOf<String, Snapshot>()

    @Synchronized fun record(source: String, ok: Boolean, error: String = "", now: Long = System.currentTimeMillis()) {
        if (source.isBlank()) return
        val log = logs.getOrPut(source) { Log() }
        log.events.addLast(now to ok)
        while (log.events.isNotEmpty() && now - log.events.first().first > WINDOW_MS) log.events.removeFirst()
        if (ok) log.lastOkAt = now else { log.lastFailAt = now; if (error.isNotBlank()) log.lastError = error.take(160) }
        snapshots[source] = Snapshot(
            ok = log.events.count { it.second },
            failed = log.events.count { !it.second },
            lastOkAt = log.lastOkAt,
            lastFailAt = log.lastFailAt,
            lastError = log.lastError,
        )
    }

    fun snapshot(source: String): Snapshot? = snapshots[source]

    /** «2 мин назад» / «только что» / «давно». */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        if (at <= 0) return "ещё не отвечал"
        val minutes = (now - at) / 60_000
        return when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            minutes < 24 * 60 -> "${minutes / 60} ч назад"
            else -> "давно"
        }
    }

    private const val WINDOW_MS = 60 * 60_000L
}

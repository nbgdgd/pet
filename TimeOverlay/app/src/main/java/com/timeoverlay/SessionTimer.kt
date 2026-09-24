package com.timeoverlay

/**
 * Считает длительность текущей сессии в приложении.
 *
 * Приложение, ушедшее с переднего плана, «паркуется» вместе с накопленным временем.
 * Возврат к нему в пределах [graceMillis] продолжает старый счёт, возврат позже — начинает с нуля.
 *
 * Класс не зависит от Android: время подаётся снаружи, поэтому логика покрыта юнит-тестами.
 */
class SessionTimer(initialGraceMillis: Long) {

    var graceMillis: Long = initialGraceMillis

    private var currentPackage: String? = null
    private var accumulated = 0L
    private var resumedAt = 0L
    private val parked = HashMap<String, Parked>()

    private class Parked(val accumulated: Long, val pausedAt: Long)

    val isRunning: Boolean get() = currentPackage != null

    /**
     * @param pkg пакет на переднем плане, `null` — пауза (экран погас, лаунчер, свои настройки).
     * @param now монотонные миллисекунды.
     */
    fun onForeground(pkg: String?, now: Long) {
        if (pkg == currentPackage) return

        park(now)
        prune(now)
        if (pkg == null) return

        val previous = parked.remove(pkg)
        accumulated = if (previous != null && now - previous.pausedAt <= graceMillis) {
            previous.accumulated
        } else {
            0L
        }
        resumedAt = now
        currentPackage = pkg
    }

    fun elapsed(now: Long): Long =
        if (currentPackage == null) 0L else accumulated + (now - resumedAt)

    fun reset() {
        currentPackage = null
        accumulated = 0L
        resumedAt = 0L
        parked.clear()
    }

    private fun park(now: Long) {
        val current = currentPackage ?: return
        parked[current] = Parked(accumulated + (now - resumedAt), now)
        currentPackage = null
        accumulated = 0L
        resumedAt = 0L
    }

    /** Выбрасывает записи, вернуться в которые уже поздно. */
    private fun prune(now: Long) {
        parked.entries.removeAll { now - it.value.pausedAt > graceMillis }
    }
}

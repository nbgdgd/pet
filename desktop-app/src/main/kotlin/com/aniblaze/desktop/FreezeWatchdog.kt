package com.aniblaze.desktop

import com.aniblaze.desktop.player.PlayerDiagnostics
import java.lang.management.ManagementFactory
import javax.swing.SwingUtilities

/**
 * Ловит зависания интерфейса С ПОЛИЧНЫМ — со снимком всех стеков.
 *
 * Зачем понадобился. Поток встал, а в журнале на этом месте дыра: опрос плеера
 * пишет каждые 400 мс и вдруг молчит 12.9 секунды, после чего декодер VLC уже
 * мёртв. Причину по журналу не найти по определению — писать его в этот момент
 * некому. Проверено и отброшено: CDN (отдаёт 29× быстрее реального времени),
 * ссылка (HTTP 200), сон Windows (событий питания нет), сборщик мусора (куча
 * 160 МБ из 282).
 *
 * Сторож живёт в ОТДЕЛЬНОМ потоке и раз в секунду просит поток интерфейса
 * отметиться. Не отметился дольше [THRESHOLD_MS] — снимаем стеки всех потоков и
 * кладём в журнал. В следующий раз будет видно, кто именно держал очередь.
 */
object FreezeWatchdog {

    /** Дольше этого без ответа — считаем зависанием и снимаем стеки. */
    private const val THRESHOLD_MS = 5_000L

    /** Между двумя снимками одного и того же зависания. */
    private const val REPEAT_MS = 30_000L

    @Volatile private var lastPong = System.nanoTime()
    @Volatile private var lastDumpAt = 0L

    fun start() {
        val thread = Thread {
            while (true) {
                runCatching {
                    // Ставим в очередь интерфейса метку. Если очередь занята, метка
                    // не обновится — ровно это нам и нужно измерить.
                    SwingUtilities.invokeLater { lastPong = System.nanoTime() }
                    Thread.sleep(1_000)
                    val stuckMs = (System.nanoTime() - lastPong) / 1_000_000
                    if (stuckMs < THRESHOLD_MS) return@runCatching
                    val now = System.nanoTime()
                    if ((now - lastDumpAt) / 1_000_000 < REPEAT_MS) return@runCatching
                    lastDumpAt = now
                    dump(stuckMs)
                }
            }
        }
        thread.isDaemon = true
        thread.name = "aniblaze-freeze-watchdog"
        thread.priority = Thread.MAX_PRIORITY
        thread.start()
        PlayerDiagnostics.log("freeze.watchdog.start", "thresholdMs=$THRESHOLD_MS")
    }

    private fun dump(stuckMs: Long) {
        PlayerDiagnostics.log("freeze.detected", "uiStuckMs=$stuckMs")
        runCatching {
            val bean = ManagementFactory.getThreadMXBean()
            // Только живые потоки со стеком; глубины в 24 кадра хватает, чтобы
            // увидеть и виновника, и того, кого он ждёт.
            for (info in bean.dumpAllThreads(true, true)) {
                val stack = info.stackTrace
                if (stack.isEmpty()) continue
                // Пишем целиком те потоки, что реально могут держать интерфейс, и
                // одну строку про остальные — иначе журнал утонет.
                val interesting = info.threadName.let {
                    it.startsWith("AWT-EventQueue") || it.startsWith("aniblaze-vlc") ||
                        it.startsWith("media-player") || it.startsWith("Thread-") ||
                        it.startsWith("DefaultDispatcher")
                }
                val head = "thread=${info.threadName}; state=${info.threadState}" +
                    (info.lockName?.let { "; ждёт=$it" } ?: "") +
                    (info.lockOwnerName?.let { "; держит=$it" } ?: "")
                if (!interesting) {
                    PlayerDiagnostics.log("freeze.thread", "$head; top=${stack.firstOrNull()}")
                    continue
                }
                PlayerDiagnostics.log("freeze.thread", head)
                stack.take(24).forEach { frame ->
                    PlayerDiagnostics.log("freeze.frame", "  ${info.threadName} | $frame")
                }
            }
            val runtime = Runtime.getRuntime()
            PlayerDiagnostics.log(
                "freeze.memory",
                "used=${(runtime.totalMemory() - runtime.freeMemory()) / 1048576}MB; " +
                    "total=${runtime.totalMemory() / 1048576}MB; max=${runtime.maxMemory() / 1048576}MB",
            )
        }.onFailure { PlayerDiagnostics.failure("freeze.dump.failed", it) }
        // Хвост журнала важнее скорости: зависание — редкое событие, и потерять
        // его снимок из-за переполненной очереди было бы обидно.
        PlayerDiagnostics.flush()
    }
}

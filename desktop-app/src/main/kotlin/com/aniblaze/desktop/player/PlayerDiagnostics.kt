package com.aniblaze.desktop.player

import java.io.File
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Small, append-only diagnostic trace for the desktop player. It deliberately
 * records state transitions rather than every frame or pointer movement, so a
 * user can reproduce a problem without the trace affecting playback.
 *
 * Записи УХОДЯТ В ОЧЕРЕДЬ, а сам файл пишет отдельный поток. Раньше [log] делал
 * синхронный `appendText` прямо на вызывающем потоке — то есть на потоке Compose,
 * — и любая заминка диска подвешивала интерфейс. Родственная беда уже случилась
 * на System.err (см. комментарий в VlcPlayer): блокирующий ввод-вывод на UI-потоке
 * вешает приложение намертво. Диагностика не имеет права быть такой ценой.
 */
object PlayerDiagnostics {
    private val directory = File(
        System.getenv("APPDATA") ?: System.getProperty("user.home"),
        "AniBlaze/logs",
    )
    val file = File(directory, "player-diagnostics.log")

    /** Ограниченная очередь: при переполнении строки ОТБРАСЫВАЮТСЯ, а не тормозят
     *  вызывающего. Потерять диагностику допустимо, подвесить UI — нет. */
    private val queue = ArrayBlockingQueue<String>(4096)

    private val writer = Thread {
        val batch = ArrayList<String>(256)
        while (true) {
            runCatching {
                // Блокируемся ТОЛЬКО здесь, в своём потоке.
                batch.add(queue.take())
                queue.drainTo(batch, 255)
                directory.mkdirs()
                file.appendText(batch.joinToString(""))
            }
            batch.clear()
        }
    }.apply { isDaemon = true; name = "aniblaze-diagnostics" }

    fun startSession() {
        runCatching {
            directory.mkdirs()
            if (file.length() > 2L * 1024L * 1024L) {
                File(directory, "player-diagnostics.previous.log").delete()
                file.renameTo(File(directory, "player-diagnostics.previous.log"))
            }
            file.appendText("\n=== AniBlaze session ${Instant.now()} ===\n")
        }
        if (!writer.isAlive) runCatching { writer.start() }
        val source = runCatching {
            PlayerDiagnostics::class.java.protectionDomain.codeSource.location.toExternalForm()
        }.getOrDefault("unknown")
        val version = System.getProperty("aniblaze.buildId")
            ?: PlayerDiagnostics::class.java.`package`?.implementationVersion
            ?: System.getProperty("jpackage.app-version")
            ?: "dev"
        val runtime = Runtime.getRuntime()
        log(
            "app.start",
            "build=$version; pid=${ProcessHandle.current().pid()}; java=${System.getProperty("java.version")}; " +
                "heapMaxMB=${runtime.maxMemory() / 1048576}; source=$source; os=${System.getProperty("os.name")}",
        )
        val suspiciousArgs = ProcessHandle.current().info().arguments().orElse(emptyArray())
            .filter { it.contains("AniBlaze-PC-updated", ignoreCase = true) || it == "-cp" }
        if (suspiciousArgs.isNotEmpty()) {
            // Эти параметры стоят ПОСЛЕ main class и JVM их не применяет, но они
            // доказывают, что старый ярлык всё ещё передаёт мусорные аргументы.
            log("runtime.legacyArguments", "count=${suspiciousArgs.size}; ignored=true")
        }
    }

    fun log(event: String, details: String = "") {
        val line = buildString {
            append(Instant.now())
            append(" | ")
            append(Thread.currentThread().name)
            append(" | ")
            append(event)
            if (details.isNotBlank()) {
                append(" | ")
                append(details.replace('\n', ' ').replace('\r', ' '))
            }
            append('\n')
        }
        // offer, НЕ put: очередь полна — молча теряем строку, но не ждём.
        queue.offer(line)
    }

    fun failure(event: String, throwable: Throwable?) {
        log(event, "${throwable?.javaClass?.simpleName ?: "Unknown"}: ${throwable?.message.orEmpty()}")
    }

    /** Дать писателю дописать хвост (вызывается при выходе). */
    fun flush() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (queue.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
    }
}

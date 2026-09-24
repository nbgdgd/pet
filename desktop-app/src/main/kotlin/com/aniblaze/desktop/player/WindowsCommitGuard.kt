package com.aniblaze.desktop.player

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import java.util.concurrent.atomic.AtomicLong

/**
 * Не даёт Skia войти в нативную аллокацию, когда Windows исчерпала commit/pagefile.
 *
 * В hs_err за 31.08 свободный AvailPageFile был 6–241 МБ при почти пустом Java heap;
 * `Image.makeRaster` в таком состоянии бросает C++-исключение за пределами Kotlin и
 * завершает весь процесс. Пропустить несколько видеокадров безопаснее, чем упасть.
 */
internal object WindowsCommitGuard {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val lastCheckNanos = AtomicLong(0L)
    private val lastWarningNanos = AtomicLong(0L)

    @Volatile private var availableCommit = Long.MAX_VALUE

    fun canAllocateFrame(frameBytes: Long): Boolean {
        if (!windows) return true
        val now = System.nanoTime()
        val previous = lastCheckNanos.get()
        // Пока запаса много — спрашиваем систему раз в 750 мс. Когда он тает,
        // спрашиваем на каждый кадр: hs_err от 14.09 показал AvailPageFile 74 МБ при
        // проверке раз в 750 мс — между двумя опросами запас успел уйти, и makeRaster
        // упал с bad_alloc, а это C++-исключение, которое JVM не переживает.
        val interval = if (availableCommit < LOW_COMMIT_BYTES) 0L else CHECK_INTERVAL_NANOS
        if (now - previous >= interval && lastCheckNanos.compareAndSet(previous, now)) {
            availableCommit = queryAvailableCommit()
        }
        val allowed = hasHeadroom(availableCommit, frameBytes)
        if (!allowed) logPressure(now, frameBytes)
        return allowed
    }

    internal fun hasHeadroom(availableBytes: Long, requestedBytes: Long): Boolean {
        if (availableBytes == Long.MAX_VALUE) return true
        val reserve = maxOf(MIN_COMMIT_RESERVE_BYTES, requestedBytes.coerceAtLeast(0L) * FRAME_RESERVE_MULTIPLIER)
        return availableBytes > reserve
    }

    private fun queryAvailableCommit(): Long = runCatching {
        val status = WinBase.MEMORYSTATUSEX()
        if (Kernel32.INSTANCE.GlobalMemoryStatusEx(status)) status.ullAvailPageFile.toLong()
        else Long.MAX_VALUE
    }.getOrDefault(Long.MAX_VALUE)

    private fun logPressure(now: Long, frameBytes: Long) {
        val previous = lastWarningNanos.get()
        if (now - previous < WARNING_INTERVAL_NANOS || !lastWarningNanos.compareAndSet(previous, now)) return
        PlayerDiagnostics.log(
            "frames.commitPressure",
            "availableMB=${availableCommit / MIB}; frameMB=${frameBytes / MIB}; action=drop",
        )
    }

    private const val MIB = 1024L * 1024L
    // Гигабайт, а не 512 МБ: кроме кадра, память в этот же момент просят декодер VLC,
    // Skia под композицию и Coil под постеры, и всем им bad_alloc так же смертелен.
    private const val MIN_COMMIT_RESERVE_BYTES = 1024L * MIB
    private const val FRAME_RESERVE_MULTIPLIER = 16L

    /** Ниже этого запаса опрашиваем систему на каждый кадр. */
    private const val LOW_COMMIT_BYTES = 2048L * MIB
    private const val CHECK_INTERVAL_NANOS = 750_000_000L
    private const val WARNING_INTERVAL_NANOS = 10_000_000_000L
}

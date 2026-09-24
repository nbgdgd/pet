package com.aniblaze.desktop

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * Keeps the monitor and machine awake while a video is playing. VLC renders into a
 * Compose callback surface (no native video window), so Windows never sees "a video
 * is playing" and the display idle-timer fires mid-episode — the screen blanks even
 * though playback continues. We tell Windows the display is still needed via
 * `SetThreadExecutionState`. No-op on non-Windows. JNA is already on the classpath.
 */
object DisplayAwake {
    private interface Kernel32 : Library {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    private val k32: Kernel32? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    private const val ES_CONTINUOUS = -0x80000000        // 0x80000000
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    /**
     * Удержание живёт на СВОЁМ потоке, а не на том, кто вызвал.
     *
     * `SetThreadExecutionState` — функция ПОТОКОВАЯ: Windows помнит требование за
     * тем потоком, который его выставил, и забывает вместе с ним. Вызов шёл из
     * цикла опроса плеера, то есть из корутины, а она свободно переезжает между
     * потоками пула. Требование оказывалось размазано по случайным рабочим потокам
     * и пропадало, как только пул их перебирал, — то есть держало экран как
     * получится, а не пока идёт кино.
     *
     * Отдельный демон-поток выставляет требование один раз и просто живёт.
     */
    private val holder = Thread {
        var held = false
        while (true) {
            val want = wanted
            if (want != held) {
                val flags = if (want) {
                    ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED
                } else {
                    ES_CONTINUOUS
                }
                runCatching { k32?.SetThreadExecutionState(flags) }
                held = want
            }
            // Windows сбрасывает требование при некоторых переходах питания, поэтому
            // раз в минуту подтверждаем его заново, даже если ничего не менялось.
            runCatching { if (held) k32?.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED) }
            runCatching { Thread.sleep(60_000) }
        }
    }.apply {
        isDaemon = true
        name = "aniblaze-display-awake"
    }

    @Volatile private var wanted = false
    private var started = false

    @Synchronized private fun ensureStarted() {
        if (started) return
        started = true
        runCatching { holder.start() }
    }

    /** Assert "display + system needed" and hold it until [release]. Idempotent. */
    fun keep() {
        if (k32 == null) return
        wanted = true
        ensureStarted()
        holder.interrupt() // применить сразу, не дожидаясь минутного круга
    }

    /** Clear the hold so the monitor can sleep normally again. Idempotent. */
    fun release() {
        if (k32 == null || !started) return
        wanted = false
        holder.interrupt()
    }
}

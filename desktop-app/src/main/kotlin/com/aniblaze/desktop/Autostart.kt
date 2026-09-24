package com.aniblaze.desktop

import java.io.File

/**
 * «Запускать с Windows»: an HKCU Run entry pointing at the INSTALLED exe with
 * `--tray`, so a reboot brings the notification watcher back without opening a
 * window. The registry itself is the setting — nothing to persist in state.json.
 */
object Autostart {

    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "AniBlaze"

    /** The running exe, when this IS the packaged app (not `gradlew run`). */
    fun exePath(): String? = ProcessHandle.current().info().command().orElse(null)
        ?.takeIf { it.endsWith("AniBlaze.exe", ignoreCase = true) && File(it).exists() }

    fun isEnabled(): Boolean = runCatching {
        val p = ProcessBuilder("reg", "query", KEY, "/v", NAME).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("AniBlaze.exe", ignoreCase = true)
    }.getOrDefault(false)

    fun set(enabled: Boolean): Boolean = runCatching {
        if (enabled) {
            val exe = exePath() ?: return false
            // No quotes around the path: %LOCALAPPDATA% contains no spaces, and the
            // Run key parses unquoted "path args" fine — while nested quotes through
            // ProcessBuilder+reg mangle reliably.
            ProcessBuilder("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", "$exe --tray", "/f")
                .start().waitFor() == 0
        } else {
            ProcessBuilder("reg", "delete", KEY, "/v", NAME, "/f").start().waitFor()
            true
        }
    }.getOrDefault(false)
}

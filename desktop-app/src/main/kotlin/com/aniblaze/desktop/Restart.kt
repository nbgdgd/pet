package com.aniblaze.desktop

import kotlin.system.exitProcess

/**
 * Persists the current state, relaunches the app in a fresh process, then exits the
 * current one — used after changing a setting that only takes effect at startup
 * (the active cinema source).
 *
 * Prefers the jpackage launcher path (set on the packaged .exe) since Windows does
 * not reliably expose a running JVM's own argv via [ProcessHandle]; falls back to
 * re-running the current command for `gradlew run`. If no relaunch path can be
 * determined, the current process is left running (the setting is already saved,
 * so a manual restart applies it) rather than closing to nothing.
 */
fun restartApplication(settings: AppSettings) {
    restartApplication(
        flush = settings::flush,
        launch = ::launchApplication,
        exit = { exitProcess(0) },
    )
}

/**
 * Injectable restart transaction used by the UI and its regression test.
 *
 * The settings writer is asynchronous. Exiting immediately after a setting change
 * used to race that writer, so the freshly launched process could read the old
 * cinema source. A failed flush deliberately stops the restart: [AppSettings.flush]
 * leaves [AppSettings.persistenceError] populated and the existing dialog explains
 * that the change is not durable yet.
 */
internal fun restartApplication(
    flush: () -> Boolean,
    launch: () -> Boolean,
    exit: () -> Unit,
): Boolean {
    if (!flush()) return false
    if (!launch()) return false
    exit()
    return true
}

private fun launchApplication(): Boolean = runCatching {
        val launcher = System.getProperty("jpackage.app-path")
        val builder = if (!launcher.isNullOrBlank()) {
            ProcessBuilder(launcher)
        } else {
            val info = ProcessHandle.current().info()
            val cmd = info.command().orElse(null) ?: return@runCatching false
            val args = info.arguments().orElse(emptyArray()).toList()
            if (args.isEmpty()) return@runCatching false // can't reconstruct the launch
            ProcessBuilder(listOf(cmd) + args)
        }
        builder.start()
        true
    }.getOrDefault(false)

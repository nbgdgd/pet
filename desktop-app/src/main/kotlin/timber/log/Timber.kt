package timber.log

/**
 * Minimal JVM stand-in for `com.jakewharton.timber.Timber` (which wraps
 * `android.util.Log` and is unavailable on desktop). Same call signatures as
 * used across the ported aggregator sources, so those files compile and run
 * unmodified — just logs to stdout/stderr instead of logcat.
 */
object Timber {
    fun d(message: String, vararg args: Any?) = log("D", format(message, args))
    fun w(message: String, vararg args: Any?) = log("W", format(message, args))
    fun w(t: Throwable, message: String, vararg args: Any?) = log("W", format(message, args), t)
    fun e(message: String, vararg args: Any?) = log("E", format(message, args))
    fun e(t: Throwable, message: String, vararg args: Any?) = log("E", format(message, args), t)

    private fun format(message: String, args: Array<out Any?>): String =
        runCatching { if (args.isEmpty()) message else String.format(message, *args) }.getOrDefault(message)

    private fun log(level: String, message: String, t: Throwable? = null) {
        println("[$level] AniBlaze: $message")
        t?.printStackTrace()
    }
}

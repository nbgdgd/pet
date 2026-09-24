package com.aniblaze.aggregator.source

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small session cache for cinema metadata, never for expiring video URLs. */
internal class CinemaMetadataCache<T : Any>(
    private val capacity: Int = 64,
    private val ttlMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry<T>(val value: T, val at: Long)
    private val entries = LinkedHashMap<String, Entry<T>>(16, .75f, true)
    // Bounded lock storage. Same-key detail, schedule and prefetch share one request.
    private val locks = Array(16) { Mutex() }

    suspend fun get(key: String, load: suspend () -> T?): T? {
        fun cached(): T? = synchronized(entries) {
            entries[key]?.takeIf { clock() - it.at < ttlMs }?.value
        }
        cached()?.let { return it }
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cached() ?: load()?.also { value ->
                synchronized(entries) {
                    entries[key] = Entry(value, clock())
                    while (entries.size > capacity) entries.remove(entries.keys.first())
                }
            }
        }
    }
}

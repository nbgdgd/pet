package com.aniblaze.network

import okhttp3.Dns
import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves hostnames by trying each [resolvers] entry in order and using the first
 * that answers, falling back to [fallback] (the system resolver) only if every
 * encrypted resolver is unreachable or empty. Successful answers are cached briefly
 * so we don't repeat a TLS handshake for every new connection.
 *
 * Why: many RF providers poison plain DNS for blocked hosts (e.g. the Cloudflare
 * proxy), handing back a black-hole IP. Asking an honest encrypted resolver
 * sidesteps that. Different providers block different transports/endpoints, so we
 * keep an ordered list (DoT to Mullvad first — verified reachable on a blocking RF
 * network — then DoH variants) with the system resolver as a last resort.
 */
class AniblazeDns(
    private val resolvers: List<Dns>,
    private val fallback: Dns = Dns.SYSTEM,
    private val ttlMillis: Long = 5 * 60 * 1000L,
) : Dns {

    private class Entry(val expiresAt: Long, val addresses: List<InetAddress>)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (it.expiresAt > now) return it.addresses }

        for (resolver in resolvers) {
            try {
                val answers = resolver.lookup(hostname)
                if (answers.isNotEmpty()) {
                    cache[hostname] = Entry(now + ttlMillis, answers)
                    return answers
                }
            } catch (e: Exception) {
                Timber.w("DNS resolver failed for %s (%s) — trying next", hostname, e.message)
            }
        }
        // Don't cache the (possibly poisoned) system result — keep retrying encrypted next time.
        return fallback.lookup(hostname)
    }
}

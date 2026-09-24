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
                val answers = preferIpv4(resolver.lookup(hostname))
                if (answers.isNotEmpty()) {
                    cache[hostname] = Entry(now + ttlMillis, answers)
                    return answers
                }
            } catch (e: Exception) {
                Timber.w("DNS resolver failed for %s (%s) — trying next", hostname, e.message)
            }
        }
        // Don't cache the (possibly poisoned) system result — keep retrying encrypted next time.
        return preferIpv4(fallback.lookup(hostname))
    }

    /**
     * IPv4 вперёд, IPv6 следом.
     *
     * Системный резолвер отдаёт оба семейства вперемешку, и OkHttp пробует их по
     * порядку. Беда в том, что у части хостов AAAA есть, а по нему НИКТО НЕ ОТВЕЧАЕТ:
     * замерено 19.08.2026 — `api.anixart.tv` и раздающие поток `p12/p14.solodcdn.com`
     * публикуют IPv6, соединение по которому отлетает мгновенно, а по IPv4 те же хосты
     * отвечают за доли секунды. Пока шестой шёл первым, каждый такой запрос начинался
     * с ожидания впустую — отсюда и сотни SocketTimeoutException в журнале за вечер.
     *
     * Шестой НЕ выбрасывается: сеть, где живёт только он, существует, и остаться там
     * вовсе без адреса хуже, чем подождать. Меняется только порядок попыток.
     */
    private fun preferIpv4(addresses: List<InetAddress>): List<InetAddress> =
        if (addresses.size < 2) addresses else addresses.sortedBy { it !is java.net.Inet4Address }
}

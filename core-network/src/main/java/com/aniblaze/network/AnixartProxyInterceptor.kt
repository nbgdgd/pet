package com.aniblaze.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * Keeps the Anixart catalog working from Russia.
 *
 * RKN blocked `api.anixart.tv` (and every other anixart.* domain — they share one
 * IP) on 2025-06-05, so a direct connection from most RF networks hangs/resets.
 * Posters (s.anixmirai.com) and video (kodikplayer.com) are on separate, un-blocked
 * hosts, so only the API host needs help.
 *
 * Different RF providers block different things — some drop the Anixart IP, some
 * also drop Cloudflare/workers.dev. So requests to the API host are tried against
 * an ordered list of reverse-proxies ([PROXY_HOSTS]) and finally the origin itself.
 * The first host that answers wins; whatever is reachable on that particular
 * network gets used. Users outside RF normally succeed on the first proxy (or could
 * use the origin directly — either works).
 *
 * Put the most reliable proxy first (a VPS on a clean, non-Cloudflare IP). Leave the
 * list empty to disable proxying entirely (talk to the origin directly, as before).
 */
class AnixartProxyInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != API_HOST) {
            return chain.proceed(request)
        }

        var lastError: IOException? = null
        // DIRECT FIRST. On a network where the origin is reachable (mobile data, VPN,
        // outside RF) proxying only adds a hop and a dependency; the proxy exists purely
        // as a fallback for networks that block the origin.
        try {
            val direct = chain.proceed(request)
            if (direct.code < 500) return direct
            direct.close()
            Timber.w("origin returned HTTP %d — falling back to proxy", direct.code)
        } catch (e: IOException) {
            lastError = e
            Timber.w("origin unreachable (%s) — falling back to proxy", e.message)
        }

        // Fallback chain. A 5xx (e.g. a suspended free-tier proxy returning 503) is NOT
        // an IOException, so treat it as a failure too — otherwise one dead proxy at the
        // head of the list bricks the whole catalog.
        for (host in PROXY_HOSTS) {
            if (host.isBlank()) continue
            try {
                val resp = chain.proceed(request.withHost(host))
                if (resp.code < 500) return resp
                resp.close()
                Timber.w("proxy %s returned HTTP %d — trying next", host, resp.code)
            } catch (e: IOException) {
                lastError = e
                Timber.w("proxy %s failed (%s) — trying next", host, e.message)
            }
        }
        throw lastError ?: IOException("Anixart unreachable directly and via proxy")
    }

    /** Same path/query/body/method, swapped onto [host]. */
    private fun Request.withHost(host: String): Request {
        val rerouted = url.newBuilder().host(host).build()
        return newBuilder().url(rerouted).build()
    }

    companion object {
        private const val API_HOST = "api.anixart.tv"

        /**
         * Reverse-proxy hosts for api.anixart.tv, tried in order. Put a VPS on a
         * clean non-Cloudflare IP first; the Cloudflare Worker is a secondary that
         * works on networks which don't block workers.dev.
         */
        val PROXY_HOSTS = listOf(
            "aniblaze-api.no9875806.workers.dev", // Cloudflare Worker
        )
    }
}

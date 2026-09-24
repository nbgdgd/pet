package com.aniblaze.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds a realistic desktop browser User-Agent and common headers so that
 * source web pages serve the same markup they would to a regular browser.
 * Many anime mirror sites cloak or block requests lacking these headers.
 */
class UserAgentInterceptor(
    private val userAgent: String = DEFAULT_UA,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Only add the header if the caller didn't already set it, so per-request
        // overrides (e.g. a JSON Accept) still win.
        val b = original.newBuilder().header("User-Agent", userAgent)
        if (original.header("Accept") == null) {
            b.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        }
        // Full Chrome request-header set. Cloudflare (kinogo.biz) fingerprints these
        // Client Hints + Sec-Fetch headers and challenges requests that lack them —
        // adding them takes the "Кино" catalog from ~50% "just a moment" pages to
        // reliably serving real markup. Harmless for the JSON anime APIs.
        b.header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
        return chain.proceed(b.build())
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

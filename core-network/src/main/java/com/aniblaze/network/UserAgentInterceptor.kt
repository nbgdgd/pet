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
        val request = original.newBuilder()
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

package com.aniblaze.aggregator.lampa

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Сетевой периметр для недоверенного Lampa-плагина. */
internal object LampaNetworkPolicy {
    const val MAX_PLUGIN_BYTES = 2 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024

    fun validateHttps(url: String): HttpUrl {
        val parsed = url.toHttpUrlOrNull() ?: throw SecurityException("Lampa: invalid URL")
        if (parsed.scheme != "https") throw SecurityException("Lampa: only HTTPS is allowed")
        val host = parsed.host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw SecurityException("Lampa: local hosts are blocked")
        }
        return parsed
    }

    /** Повторяет проверку на уровне DNS OkHttp, закрывая DNS rebinding к localhost/LAN. */
    fun sandboxed(base: OkHttpClient): OkHttpClient {
        val delegate = base.dns
        return base.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = delegate.lookup(hostname)
                if (addresses.isEmpty() || addresses.any { !it.isPublicAddress() }) {
                    throw SecurityException("Lampa: private network address is blocked")
                }
                return addresses
            }
        }).build()
    }

    fun readLimited(body: ResponseBody?, limit: Int): String? {
        body ?: return null
        val declared = body.contentLength()
        if (declared > limit) throw SecurityException("Lampa: response is too large")
        val bytes = body.byteStream().use { it.readNBytes(limit + 1) }
        if (bytes.size > limit) throw SecurityException("Lampa: response is too large")
        return bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }

    private fun InetAddress.isPublicAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return false
        }
        val raw = address
        if (this is Inet4Address && raw.size == 4) {
            val a = raw[0].toInt() and 0xff
            val b = raw[1].toInt() and 0xff
            // Carrier-grade NAT and documentation/benchmark ranges must not become
            // an escape hatch to services on the host network.
            if (a == 100 && b in 64..127) return false
            if (a == 198 && b in 18..19) return false
        }
        if (this is Inet6Address && raw.isNotEmpty()) {
            val first = raw[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return false // fc00::/7 unique-local
        }
        return true
    }
}

package com.aniblaze.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * User-configured HTTP/SOCKS proxy for parser/addon traffic (blocked domains
 * on filtered networks). Values mirror [SettingsDataStore] and are pushed in
 * by the app process on start; OkHttp consults the selector per request, so
 * toggling applies live without rebuilding the client.
 */
@Singleton
class ProxyConfig @Inject constructor() {
    @Volatile var enabled: Boolean = false
    @Volatile var host: String = ""
    @Volatile var port: Int = 0
    @Volatile var type: String = "http"
    @Volatile var user: String = ""
    @Volatile var pass: String = ""

    fun update(
        enabled: Boolean,
        host: String,
        port: Int,
        type: String,
        user: String,
        pass: String,
    ) {
        this.enabled = enabled
        this.host = host.trim()
        this.port = port.coerceIn(0, 65535)
        this.type = if (type == "socks") "socks" else "http"
        this.user = user
        this.pass = pass
    }

    fun currentProxy(): Proxy? {
        if (!enabled || host.isBlank() || port !in 1..65535) return null
        val kind = if (type == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return runCatching { Proxy(kind, InetSocketAddress(host, port)) }.getOrNull()
    }

    fun hasAuth(): Boolean = user.isNotBlank()
}

/** Routes OkHttp traffic through [ProxyConfig] when it yields a proxy. */
internal class ConfigProxySelector(private val config: ProxyConfig) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> =
        listOf(config.currentProxy() ?: Proxy.NO_PROXY)

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        Timber.w(ioe, "[Proxy] connection failed for %s", uri.host)
    }
}

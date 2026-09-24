package com.aniblaze.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // Off by default — per-request logging is pure overhead on the hot path.
            level = HttpLoggingInterceptor.Level.NONE
        }

    /**
     * DNS-over-HTTPS resolvers, so RF providers that poison plain DNS for blocked
     * hosts can't black-hole the proxy/posters/streams. Mullvad first (verified
     * reachable on a blocking RF network), then AdGuard; each is bootstrapped with
     * its own IPs so it needs no system DNS to start. Falls back to the system
     * resolver only if every DoH endpoint is unreachable.
     */
    @Provides
    @Singleton
    fun provideDns(): Dns {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        fun doh(url: String, vararg ips: String) = DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(*ips.map { InetAddress.getByName(it) }.toTypedArray())
            .build()
        return AniblazeDns(
            resolvers = listOf(
                // DoT (port 853) first — verified reachable on a blocking RF network
                // where DoH (443) was not. This mirrors Android's working Private DNS.
                DotDns(serverHost = "dns.mullvad.net", serverIp = "194.242.2.2"),
                // DoH fallbacks for networks where 853 is blocked but 443 isn't.
                doh("https://dns.mullvad.net/dns-query", "194.242.2.2"),
                doh("https://dns.adguard-dns.com/dns-query", "94.140.14.14", "94.140.15.15"),
            ),
            fallback = Dns.SYSTEM,
        )
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        logging: HttpLoggingInterceptor,
        dns: Dns,
        proxyConfig: ProxyConfig,
    ): OkHttpClient {
        // Home load fires ~20 requests at once, all to one host. The default cap of 5
        // per host serialises them into slow waves; raise it so the screen fills faster.
        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 12
        }
        // Disk cache + a short forced max-age so re-opening a title/episode is instant
        // instead of re-hitting the network (Anixart sends no cache headers itself).
        val cache = Cache(File(context.cacheDir, "http_cache"), 30L * 1024 * 1024)
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cache(cache)
            .dns(dns)
            // User proxy (Settings → Сеть): routes blocked parser/addon hosts
            // around SNI/IP filters. Consulted per request, so toggling is live.
            .proxySelector(ConfigProxySelector(proxyConfig))
            .proxyAuthenticator { _, response ->
                // 407 = proxy challenge only; never answer origin 401s with
                // proxy credentials, and never retry the same challenge twice.
                if (response.code == 407 && proxyConfig.hasAuth() &&
                    response.request.header("Proxy-Authorization") == null
                ) {
                    response.request.newBuilder()
                        .header(
                            "Proxy-Authorization",
                            okhttp3.Credentials.basic(proxyConfig.user, proxyConfig.pass),
                        )
                        .build()
                } else {
                    null
                }
            }
            // Outermost: reroutes the Anixart API through a proxy when it's blocked
            // (RF), so the catalog/home still load. Direct otherwise.
            .addInterceptor(AnixartProxyInterceptor())
            .addInterceptor(UserAgentInterceptor())
            // Endpoints that MUST stay fresh (e.g. /release/random) always hit the network.
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.encodedPath.contains("random")) {
                    chain.proceed(req.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build())
                } else {
                    chain.proceed(req)
                }
            }
            .addInterceptor(logging)
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val response = chain.proceed(req)
                // Force-cache idempotent GETs, but never the random endpoint.
                if (req.method == "GET" && !req.url.encodedPath.contains("random")) {
                    response.newBuilder()
                        .removeHeader("Pragma")
                        .removeHeader("Cache-Control")
                        .header("Cache-Control", "public, max-age=300")
                        .build()
                } else {
                    response
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

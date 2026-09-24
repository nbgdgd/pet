package com.aniblaze.aggregator.lampa

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-facing wrapper around [LampaRuntime]. Rhino's Context is thread-bound, so ALL
 * runtime work runs on one dedicated thread; callers submit and await. The plugin is
 * downloaded and loaded once per URL and reused.
 */
@Singleton
class LampaExtractor @Inject constructor(private val okHttp: OkHttpClient) {

    @Volatile private var exec = newExecutor()
    private val sandboxHttp = LampaNetworkPolicy.sandboxed(okHttp)

    private var runtime: LampaRuntime? = null
    private var loadedUrl: String? = null
    private var componentName: String = "online_mod"
    private val kinopoiskIds = ConcurrentHashMap<String, String>()

    private fun newExecutor() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lampa-runtime").apply { isDaemon = true }
    }

    @Synchronized
    private fun <T> onThread(block: () -> T): T {
        val current = exec
        val future = current.submit(Callable { block() })
        return try {
            future.get(RUNTIME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            current.shutdownNow()
            runtime = null
            loadedUrl = null
            exec = newExecutor()
            throw timeout
        } catch (failed: ExecutionException) {
            throw (failed.cause ?: failed)
        }
    }

    // Runs ON the lampa thread (no onThread wrapper — safe to call from within one).
    private fun loadInternal(pluginUrl: String): String? {
        if (runtime != null && loadedUrl == pluginUrl) return componentName
        runCatching { runtime?.close() }
        runtime = null; loadedUrl = null
        val safeUrl = LampaNetworkPolicy.validateHttps(pluginUrl)
        val js = sandboxHttp.newCall(Request.Builder().url(safeUrl).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            LampaNetworkPolicy.readLimited(response.body, LampaNetworkPolicy.MAX_PLUGIN_BYTES)
        } ?: return null
        val rt = LampaRuntime(okHttp)
        val loadError = rt.loadPlugin(js)
        if (loadError != null) {
            rt.close()
            return null
        }
        val name = rt.components.keys.firstOrNull() ?: run { rt.close(); return null }
        runtime = rt; loadedUrl = pluginUrl; componentName = name
        return name
    }

    /** Load (once) the plugin at [pluginUrl]; returns the component name it registered, or null. */
    fun load(pluginUrl: String): String? = onThread { loadInternal(pluginUrl) }

    /**
     * Resolve every dub for [movie], preferring [balancer] but falling back to the
     * token-free balancers that reliably work (rezka/cdnmovies/vibix) if the chosen
     * one returns nothing — so e.g. collaps (needs a premium token) still plays.
     */
    fun resolve(pluginUrl: String, balancer: String, movie: Map<String, Any?>, season: Int = 0): List<LampaRuntime.LampaStream> = onThread {
        val rt = runtime.takeIf { loadedUrl == pluginUrl } ?: run { loadInternal(pluginUrl); runtime } ?: return@onThread emptyList<LampaRuntime.LampaStream>()
        val name = componentName
        for (bal in resolveOrder(balancer)) {
            val r = runCatching { rt.resolve(name, bal, movie, season) }.getOrDefault(emptyList())
            if (r.isNotEmpty()) return@onThread r
        }
        emptyList()
    }

    /** Resolve one concrete series episode. A plugin renders episode cards as a
     * list; those cards are not voiceovers and must never be returned to the UI as
     * translations. */
    fun resolveEpisode(
        pluginUrl: String,
        balancer: String,
        movie: Map<String, Any?>,
        season: Int,
        episode: Int,
    ): LampaRuntime.LampaStream? = onThread {
        val rt = runtime.takeIf { loadedUrl == pluginUrl }
            ?: run { loadInternal(pluginUrl); runtime }
            ?: return@onThread null
        for (candidate in resolveOrder(balancer)) {
            val streams = runCatching { rt.resolve(componentName, candidate, movie, season) }
                .getOrDefault(emptyList())
            streams.firstOrNull { seriesPosition(it.title) == season to episode }?.let { return@onThread it }
        }
        null
    }

    /** TMDB exposes IMDb but not KinoPoisk ids, while CDNVideoHub requires a real
     * KinoPoisk id. Resolve the public IMDb↔KinoPoisk relation once and cache it;
     * passing TMDB's numeric id as a KinoPoisk id silently returns another title. */
    fun kinopoiskIdForImdb(imdbId: String): String? {
        val imdb = imdbId.trim().takeIf { it.matches(Regex("tt\\d+")) } ?: return null
        kinopoiskIds[imdb]?.let { return it }
        val query = "SELECT ?kp WHERE { ?item wdt:P345 \"$imdb\"; wdt:P2603 ?kp. } LIMIT 1"
        val url = "https://query.wikidata.org/sparql?query=" +
            URLEncoder.encode(query, Charsets.UTF_8.name()) + "&format=json"
        val body = runCatching {
            okHttp.newCall(
                Request.Builder().url(url)
                    .header("Accept", "application/sparql-results+json")
                    .header("User-Agent", "AniBlaze/1.0")
                    .build(),
            ).execute().use { response -> response.body?.string().takeIf { response.isSuccessful } }
        }.getOrNull() ?: return null
        val id = Regex(""""kp"\s*:\s*\{[^{}]*"value"\s*:\s*"(\d+)"""")
            .find(body)?.groupValues?.getOrNull(1) ?: return null
        kinopoiskIds[imdb] = id
        return id
    }

    /**
     * Resolve a SERIES: run the plugin (which fetches the cdnvideohub playlist with
     * all seasons/episodes/dubs) and return that raw playlist JSON. The direct
     * playlist endpoint rejects our requests, but the plugin has the right auth.
     */
    fun seriesPlaylist(pluginUrl: String, balancer: String, movie: Map<String, Any?>): String? = onThread {
        val rt = runtime.takeIf { loadedUrl == pluginUrl } ?: run { loadInternal(pluginUrl); runtime } ?: return@onThread null
        for (bal in resolveOrder(balancer)) {
            runCatching { rt.resolve(componentName, bal, movie) }
            rt.lastPlaylist?.let { return@onThread it }
        }
        null
    }

    private companion object {
        // Live, token-free sources of the current online_mod plugin. The old names
        // rezka/cdnmovies/vibix are disabled upstream (rezka was renamed to rezka2);
        // the plugin silently falls back to its default (cdnvideohub) for them.
        val FALLBACK = listOf("cdnvideohub", "kodik", "rezka2", "filmix")
        const val RUNTIME_TIMEOUT_SECONDS = 35L

        /** Maps legacy stored balancer names to the ones the current plugin has. */
        fun effectiveBalancer(balancer: String): String = when (balancer) {
            "rezka" -> "rezka2" // renamed upstream; legacy stored prefs keep working
            else -> balancer
        }

        /** Requested balancer (aliased) first, then the live fallbacks. */
        fun resolveOrder(balancer: String): List<String> =
            LinkedHashSet<String>().apply {
                add(effectiveBalancer(balancer))
                addAll(FALLBACK)
            }.toList()

        fun seriesPosition(title: String): Pair<Int, Int>? {
            val season = Regex("""(?:^|\s)S\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
            val episode = Regex("""(?:torrent_serial_episode|episode|эпизод|серия)\D*(\d+)""", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
            return season to episode
        }
    }

    fun close() = runCatching { onThread { runtime?.close() }; exec.shutdownNow() }.let {}
}

package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Plays a title through a video BALANCER instead of the source's own stream.
 *
 * Why this exists: our normal path hands libVLC whatever the catalog source points
 * at — for anime that is a Kodik HLS playlist, and every seek re-opens segments and
 * refills the cache (the black-screen-while-seeking problem). Balancers serve the
 * same titles from their own CDN, which is what makes players like wparty feel
 * smooth. wparty's own bundle shows how they are addressed:
 *
 *     kodik  → /find-player?shikimoriID=<id>   (or kinopoiskID=<id>)
 *     alloha → …&kp=<kinopoisk id>
 *     vibix  → /embed-kp/<kinopoisk id>
 *
 * The `shikimoriID` form is the important one: anime has no KinoPoisk id, which is
 * why plugin balancers returned nothing for it before.
 *
 * Every request goes through the AniBlaze Cloudflare proxy — the balancer hosts do
 * not resolve at all on the user's network (a direct request returns no response).
 */
class BalancerSource(
    private val http: HttpClient,
    private val kodik: KodikExtractor,
) {
    /**
     * Resolve [title] episode [episode] through the Kodik balancer.
     * Returns null when the title is unknown to Shikimori or the balancer has no
     * player for it, so the caller can fall back to the direct stream.
     */
    suspend fun resolve(title: String, episode: Int): ContentResult? = withContext(Dispatchers.IO) {
        val malId = shikimoriId(title) ?: return@withContext null
        val payload = findPlayer(malId, episode) ?: return@withContext null
        val link = payload.optString("link").ifBlank { return@withContext null }
        val embed = if (link.startsWith("//")) "https:$link" else link
        val variants = kodik.extract(embed)
        if (variants.isEmpty()) {
            Timber.w("[Balancer] kodik returned no streams for %s ep %d", title, episode)
            return@withContext null
        }
        ContentResult(
            location = variants.first().url,
            quality = variants.first().quality,
            source = "Kodik (балансер)",
            referer = "https://kodikplayer.com/",
            variants = variants,
            translations = translations(payload),
        )
    }

    /** Shikimori ids ARE MyAnimeList ids, and Kodik indexes anime by them. */
    private suspend fun shikimoriId(title: String): Int? {
        val q = java.net.URLEncoder.encode(title.take(80), "UTF-8")
        val body = http.getHtml(proxied("shikimori.one", "/api/animes?search=$q&limit=1")) ?: return null
        return runCatching {
            org.json.JSONArray(body).optJSONObject(0)?.optInt("id", 0)?.takeIf { it > 0 }
        }.getOrNull()
    }

    private suspend fun findPlayer(malId: Int, episode: Int): JSONObject? {
        val path = "/find-player?shikimoriID=$malId&episode=$episode"
        val body = http.getHtml(proxied("kodikapi.com", path)) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()?.takeIf { it.has("link") }
    }

    private fun translations(payload: JSONObject): List<Translation>? {
        val list = payload.optJSONArray("translations") ?: return null
        val out = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            Translation(id, t.optString("title").ifBlank { "Озвучка" })
        }
        return out.takeIf { it.size > 1 }
    }

    /** Balancer hosts are unreachable directly on RF networks — always go via the proxy. */
    private fun proxied(host: String, pathAndQuery: String): String =
        "$PROXY/p/$host$pathAndQuery"

    private companion object {
        const val PROXY = "https://aniblaze-api.no9875806.workers.dev"
    }
}

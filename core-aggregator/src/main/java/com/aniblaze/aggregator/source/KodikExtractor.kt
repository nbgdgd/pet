package com.aniblaze.aggregator.source

import android.util.Base64
import com.aniblaze.aggregator.model.StreamVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a kodikplayer.com embed URL (the kind Anixart hands out) into a list of
 * playable HLS qualities.
 *
 * The Kodik *API* (kodikapi.com) is dead, but the *player* (kodikplayer.com) is
 * alive. The flow, reverse-engineered from app.player_single.js and verified
 * end-to-end:
 *   1. GET the embed page, read the signed params (d/pd/ref + *_sign, type/id/hash).
 *   2. POST them to /ftor.
 *   3. Each quality's `src` is ROT+18-ciphered then base64 — decode to the m3u8.
 */
@Singleton
class KodikExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    suspend fun extract(rawEmbedUrl: String): List<StreamVariant> = withContext(Dispatchers.IO) {
        runCatching {
            val embedUrl = normalize(rawEmbedUrl)
            val html = get(embedUrl, referer = ANIXART_REF) ?: return@runCatching emptyList()
            val params = parseParams(embedUrl, html) ?: run {
                Timber.w("[Kodik] could not parse embed params")
                return@runCatching emptyList()
            }
            val links = postFtor(embedUrl, params) ?: return@runCatching emptyList()
            parseLinks(links)
        }.onFailure { Timber.w(it, "[Kodik] extract failed for %s", rawEmbedUrl) }
            .getOrDefault(emptyList())
    }

    private fun normalize(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> "https://$url"
    }

    private fun parseParams(embedUrl: String, html: String): Map<String, String>? {
        // /seria/208036/<hash>/720p  ->  type=seria, id=208036, hash=<hash>
        val path = embedUrl.substringAfter("kodikplayer.com").substringBefore('?')
        val seg = path.split('/').filter { it.isNotBlank() }
        if (seg.size < 3) return null
        val type = seg[0]
        val id = seg[1]
        val hash = seg[2]

        fun v(name: String) = Regex("""var\s+$name\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1)

        val p = mutableMapOf(
            "hash" to hash,
            "id" to id,
            "type" to type,
            "bad_user" to "true",
            "info" to "{}",
            "cdn_is_working" to "true",
        )
        for (key in listOf("d", "d_sign", "pd", "pd_sign", "ref", "ref_sign")) {
            v(key)?.let { p[key] = it }
        }
        return p
    }

    private fun postFtor(embedUrl: String, params: Map<String, String>): JSONObject? {
        val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url("https://kodikplayer.com$FTOR")
            .post(form)
            .header("User-Agent", UA)
            .header("Referer", embedUrl)
            .header("Origin", "https://kodikplayer.com")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("[Kodik] /ftor HTTP %d", response.code)
                return null
            }
            return response.body?.string()?.let { JSONObject(it) }
        }
    }

    private fun parseLinks(json: JSONObject): List<StreamVariant> {
        val links = json.optJSONObject("links") ?: return emptyList()
        val out = mutableListOf<StreamVariant>()
        for (key in links.keys()) {
            val height = key.filter { it.isDigit() }.toIntOrNull() ?: continue
            val src = links.optJSONArray(key)?.optJSONObject(0)?.optString("src").orEmpty()
            if (src.isBlank()) continue
            val url = decodeSrc(src) ?: continue
            out.add(StreamVariant(quality = "${height}p", url = url))
        }
        return out.distinctBy { it.quality }.sortedByDescending { it.quality.dropLast(1).toIntOrNull() ?: 0 }
    }

    /** ROT+18 over [A-Za-z], then base64 — the kodikplayer `src` cipher. */
    private fun decodeSrc(src: String): String? = runCatching {
        val rotated = buildString {
            for (c in src) {
                append(
                    when (c) {
                        in 'a'..'z' -> 'a' + ((c - 'a' + 18) % 26)
                        in 'A'..'Z' -> 'A' + ((c - 'A' + 18) % 26)
                        else -> c
                    },
                )
            }
        }
        val decoded = String(Base64.decode(rotated, Base64.DEFAULT))
        when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("http") -> decoded
            else -> "https://$decoded"
        }
    }.getOrNull()

    private fun get(url: String, referer: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            return if (response.isSuccessful) response.body?.string() else null
        }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val ANIXART_REF = "https://anixart.tv/"
        const val FTOR = "/ftor"
    }
}

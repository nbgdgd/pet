package com.aniblaze.torrent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class TorrentCandidate(
    val infoHash: String,
    val fileIndex: Int?,
    val trackers: List<String>,
    val label: String,
    val score: Int,
) {
    fun magnet(): String = buildString {
        append("magnet:?xt=urn:btih:").append(infoHash)
        trackers.distinct().take(20).forEach { tracker ->
            append("&tr=").append(URLEncoder.encode(tracker, StandardCharsets.UTF_8.name()))
        }
    }
}

/** Minimal client for the documented Stremio `/stream/{type}/{id}.json` contract. */
internal class StremioTorrentIndex(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun find(
        addonUrl: String,
        imdbId: String,
        isSeries: Boolean,
        season: Int,
        episode: Int,
    ): List<TorrentCandidate> {
        val base = normalizedBase(addonUrl) ?: return emptyList()
        if (!imdbId.matches(Regex("tt\\d{5,12}", RegexOption.IGNORE_CASE))) return emptyList()
        val type = if (isSeries) "series" else "movie"
        val videoId = if (isSeries) "$imdbId:$season:$episode" else imdbId
        val url = "$base/stream/$type/$videoId.json"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val raw = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body ?: return emptyList()
            if (body.contentLength() > MAX_RESPONSE_BYTES) return emptyList()
            val source = body.source()
            val buffer = Buffer()
            while (buffer.size <= MAX_RESPONSE_BYTES) {
                val read = source.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1 - buffer.size))
                if (read == -1L) break
            }
            if (buffer.size > MAX_RESPONSE_BYTES) return emptyList()
            buffer.readUtf8()
        }
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        return (root["streams"] as? JsonArray).orEmpty()
            .mapNotNull { parseCandidate(it as? JsonObject ?: return@mapNotNull null) }
            .distinctBy { it.infoHash.lowercase() to it.fileIndex }
            .sortedByDescending { it.score }
    }

    private fun parseCandidate(obj: JsonObject): TorrentCandidate? {
        val hash = obj.string("infoHash")?.trim()?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: return null
        val name = obj.string("name").orEmpty()
        val description = obj.string("description") ?: obj.string("title").orEmpty()
        val label = listOf(name, description).filter(String::isNotBlank).joinToString(" · ").take(160)
        val trackers = (obj["sources"] as? JsonArray).orEmpty().mapNotNull { item ->
            item.jsonPrimitive.contentOrNull?.removePrefix("tracker:")
                ?.takeIf { it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://") }
        }
        return TorrentCandidate(
            infoHash = hash,
            fileIndex = obj["fileIdx"]?.jsonPrimitive?.intOrNull,
            trackers = trackers,
            label = qualityLabel(label),
            score = rank(label),
        )
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun normalizedBase(raw: String): String? {
        var value = raw.trim().trimEnd('/')
        if (value.endsWith("/manifest.json", true)) value = value.dropLast("/manifest.json".length)
        val parsed = value.toHttpUrlOrNull() ?: return null
        val local = parsed.host == "127.0.0.1" || parsed.host == "localhost"
        if (!parsed.isHttps && !local) return null
        return value
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        internal fun qualityLabel(text: String): String = when {
            text.contains("2160", true) || text.contains("4k", true) -> "2160p · Torrent"
            text.contains("1080", true) -> "1080p · Torrent"
            text.contains("720", true) -> "720p · Torrent"
            text.contains("480", true) -> "480p · Torrent"
            else -> "Torrent"
        }

        internal fun rank(text: String): Int {
            val lower = text.lowercase()
            var score = when {
                "1080" in lower -> 500
                "720" in lower -> 400
                "2160" in lower || "4k" in lower -> 300 // avoid huge 4K as an automatic fallback
                "480" in lower -> 200
                else -> 100
            }
            if ("web-dl" in lower || "webrip" in lower || "bluray" in lower) score += 80
            if ("camrip" in lower || " telesync" in lower || " ts " in " $lower ") score -= 300
            val seeds = Regex("(?:seed(?:er)?s?|пиры|сиды|👤)\\D{0,5}(\\d+)", RegexOption.IGNORE_CASE)
                .find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            // A mobile stream should not pick a 35-GB remux merely because it has
            // two more peers than a normal WEB-DL. Torrentio includes the release
            // size in its title; use it only as a ranking hint, never as metadata.
            val sizeMatch = Regex("(\\d+(?:[.,]\\d+)?)\\s*(gb|gib|mb|mib)", RegexOption.IGNORE_CASE)
                .find(lower)
            val sizeGb = sizeMatch?.let { match ->
                val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
                if (match.groupValues[2].startsWith("m", true)) value / 1024.0 else value
            }
            val mobileSizeScore = when {
                sizeGb == null -> 0
                sizeGb > 20.0 -> -260
                sizeGb > 12.0 -> -180
                sizeGb > 7.0 -> -90
                sizeGb in 0.35..6.0 -> 35
                else -> 0
            }
            return score + seeds.coerceAtMost(200) + mobileSizeScore
        }
    }
}

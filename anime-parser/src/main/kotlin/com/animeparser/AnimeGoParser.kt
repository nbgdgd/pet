package com.animeparser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class AnimeGoParser(private val client: OkHttpClient = defaultClient()) {

    suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        try {
            val url = "https://animego.me/search?q=${encode(query)}"
            println("[AnimeParser] search url=$url")
            val html = get(url)
            println("[AnimeParser] search OK html.length=${html.length}")
            val doc = Jsoup.parse(html)
            val results = doc.select(".anime-card, .media-item, .anime-item").mapNotNull { card ->
                val link = card.select("a[href]").first() ?: return@mapNotNull null
                val href = link.attr("href")
                val id = href.substringAfterLast("-").takeIf { it.isNotEmpty() } ?: href
                Anime(
                    id = id,
                    title = card.select(".anime-card__title, .media-title, h5, h6").text().ifEmpty { card.text().take(80) },
                    image = card.select("img").attr("src").ifEmpty { card.select("img").attr("data-src") },
                    url = if (href.startsWith("http")) href else "https://animego.me$href",
                    score = card.select(".rating, .score").text(),
                )
            }
            if (results.isNotEmpty()) {
                println("[AnimeParser] search OK count=${results.size} first=${results.first().title}")
                return@withContext results
            }
            println("[AnimeParser] search no .anime-card hits, trying fallback a[href*=/anime/]")
            val fallback = doc.select("a[href*=/anime/]").mapNotNull { a ->
                val href = a.attr("href")
                if (!href.contains("/anime/")) return@mapNotNull null
                Anime(
                    id = href.substringAfterLast("-"),
                    title = a.text().take(80),
                    url = if (href.startsWith("http")) href else "https://animego.me$href",
                )
            }
            println("[AnimeParser] search fallback count=${fallback.size}")
            fallback
        } catch (e: Exception) {
            println("[AnimeParser] search ERROR: ${e.message}")
            emptyList()
        }
    }

    suspend fun getAnime(url: String): Anime? = withContext(Dispatchers.IO) {
        try {
            println("[AnimeParser] getAnime url=$url")
            val html = get(url)
            println("[AnimeParser] getAnime OK html.length=${html.length}")
            val doc = Jsoup.parse(html)
            val title = doc.select("h1").text()
            val status = doc.select(".status, .anime-status").text()
            val episodesRaw = doc.select(".episodes, [data-episodes]").attr("data-episodes").ifEmpty {
                doc.select("span:contains(Эпизодов)").text().filter { it.isDigit() }
            }
            println("[AnimeParser] getAnime title='$title' status='$status'")
            Anime(
                id = url.substringAfterLast("-"),
                title = title,
                image = doc.select("img[src*=/uploads/]").attr("src"),
                description = doc.select(".description, [itemprop=description]").text(),
                score = doc.select(".rating, .score").text(),
                genres = doc.select(".genres a, [itemprop=genre]").eachText(),
                status = status,
                year = doc.select(".year, .anime-year").text(),
                url = url,
            )
        } catch (e: Exception) {
            println("[AnimeParser] getAnime ERROR: ${e.message}")
            null
        }
    }

    suspend fun getVoices(url: String): List<VoiceOption> = withContext(Dispatchers.IO) {
        try {
            println("[AnimeParser] getVoices url=$url")
            val html = get(url)
            println("[AnimeParser] getVoices OK html.length=${html.length}")
            val doc = Jsoup.parse(html)
            val voices = mutableListOf<VoiceOption>()

            // Extract from data attributes (AnimeGO format)
            for (el in doc.select("[data-translation-id], [data-voice]")) {
                val id = el.attr("data-translation-id").ifEmpty { el.attr("data-voice") }
                val name = el.text().ifEmpty { el.attr("title") }
                val embed = el.attr("data-embed").ifEmpty { el.attr("data-src") }
                val playerType = when {
                    embed.contains("aniboom") -> "aniboom"
                    embed.contains("kodik") -> "kodik"
                    embed.contains("cvh") -> "cvh"
                    else -> "kodik"
                }
                if (id.isNotEmpty()) {
                    voices.add(VoiceOption(id, name, playerType, embed))
                }
            }
            println("[AnimeParser] getVoices data-attr voices=${voices.size}")

            // Fallback: extract from JSON script tag
            if (voices.isEmpty()) {
                val scripts = doc.select("script:containsData(translation_id), script:containsData(episodes)")
                println("[AnimeParser] getVoices script fallback, found ${scripts.size} scripts")
                for (script in scripts) {
                    val matcher = Pattern.compile("\"translation_id\"\\s*:\\s*\"([^\"]+)\"").matcher(script.html())
                    while (matcher.find()) {
                        val tid = matcher.group(1)
                        val nameMatcher = Pattern.compile("\"voice\"\\s*:\\s*\"([^\"]+)\"").matcher(script.html())
                        val name = if (nameMatcher.find()) nameMatcher.group(1) else tid
                        voices.add(VoiceOption(tid, name, "aniboom", ""))
                    }
                }
                println("[AnimeParser] getVoices script fallback found ${voices.size} voices")
            }

            val distinct = voices.distinctBy { it.id }
            println("[AnimeParser] getVoices final count=${distinct.size}")
            if (distinct.isNotEmpty()) {
                println("[AnimeParser] getVoices first: id=${distinct.first().id} name=${distinct.first().name} type=${distinct.first().playerType}")
            }
            distinct
        } catch (e: Exception) {
            println("[AnimeParser] getVoices ERROR: ${e.message}")
            emptyList()
        }
    }

    suspend fun getAniboomStream(voiceId: String, animeId: String, episode: Int): VideoStream? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.aniboom.xyz/v1/stream?translation_id=$voiceId&episode=$episode&anime_id=$animeId"
            println("[AnimeParser] aniboom url=$url")
            val body = get(url)
            println("[AnimeParser] aniboom OK body.length=${body.length}")
            val doc = Jsoup.parse(body)
            val mpd = doc.select("source[type*=mpd], [src$=.mpd]").attr("src").ifEmpty {
                Pattern.compile("\"mpd\"\\s*:\\s*\"([^\"]+)\"").matcher(body).let { if (it.find()) it.group(1) else "" }
            }
            val m3u8 = doc.select("source[type*=m3u8], [src$=.m3u8]").attr("src").ifEmpty {
                Pattern.compile("\"m3u8\"\\s*:\\s*\"([^\"]+)\"").matcher(body).let { if (it.find()) it.group(1) else "" }
            }
            println("[AnimeParser] aniboom mpd='$mpd' m3u8='$m3u8'")

            val streamUrl = mpd.ifEmpty { m3u8 }
            if (streamUrl.isNotEmpty()) {
                println("[AnimeParser] aniboom SUCCESS streamUrl=$streamUrl")
                VideoStream(
                    url = streamUrl,
                    quality = 720,
                    qualities = listOf(480, 720),
                    playerType = if (mpd.isNotEmpty()) "direct" else "hls",
                )
            } else {
                println("[AnimeParser] aniboom no streamUrl found")
                null
            }
        } catch (e: Exception) {
            println("[AnimeParser] aniboom ERROR: ${e.message}")
            null
        }
    }

    suspend fun getKodikEmbed(voice: VoiceOption): VideoStream? = withContext(Dispatchers.IO) {
        val embed = voice.embedUrl.ifEmpty {
            println("[AnimeParser] kodik: empty embedUrl for voice ${voice.id}")
            return@withContext null
        }
        val url = if (embed.startsWith("//")) "https:$embed" else embed
        println("[AnimeParser] kodik embed url=$url")
        VideoStream(
            url = url,
            quality = 720,
            playerType = "webview",
        )
    }

    private fun get(url: String): String {
        println("[AnimeParser] GET $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            .build()
        val response = client.newCall(request).execute()
        println("[AnimeParser] GET response code=${response.code}")
        val body = response.body?.string()
            ?: throw RuntimeException("Empty response: $url")
        println("[AnimeParser] GET response body.length=${body.length}")
        return body
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true).build()
    }
}

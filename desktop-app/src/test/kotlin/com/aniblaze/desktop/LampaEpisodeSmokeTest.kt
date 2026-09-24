package com.aniblaze.desktop

import com.aniblaze.aggregator.lampa.LampaExtractor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * LIVE smoke test for the mobile cinema episode path (the Lampa plugin emulator
 * shared with the Android app through core-aggregator's srcDir). Reproduces the
 * reported case: «Дом Дракона» (TMDB tv 94997), flattened segment 22, resolved
 * via the exact-episode fallback used by LampaCatalogSource.extractContent.
 *
 * Network-dependent by design: it fails loudly when TMDB and the plugin answer
 * but no matching episode stream is found — that is the regression it guards.
 */
class LampaEpisodeSmokeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} for $url" }
            r.body?.string().orEmpty()
        }

    /** Released (season, episode) pairs in flattened segment order, TMDB-driven. */
    private fun releasedEpisodes(tmdbId: Int): List<Pair<Int, Int>> {
        val details = get("$TMDB/tv/$tmdbId?$KEY&language=ru-RU").let { json.parseToJsonElement(it).jsonObject }
        val today = LocalDate.now()
        val seasons = details["seasons"]!!.jsonArray
            .map { it.jsonObject }
            .filter { (it["season_number"]?.jsonPrimitive?.intOrNull ?: 0) > 0 }
            .map { it["season_number"]!!.jsonPrimitive.intOrNull!! }
        val out = ArrayList<Pair<Int, Int>>()
        for (season in seasons) {
            val sd = get("$TMDB/tv/$tmdbId/season/$season?$KEY&language=ru-RU")
                .let { json.parseToJsonElement(it).jsonObject }
            sd["episodes"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { ep ->
                    val air = ep["air_date"]?.jsonPrimitive?.content.orEmpty()
                    val date = runCatching { LocalDate.parse(air) }.getOrNull()
                    date != null && !date.isAfter(today)
                }
                .forEach { ep ->
                    out += season to ep["episode_number"]!!.jsonPrimitive.intOrNull!!
                }
        }
        return out.sortedWith(compareBy({ it.first }, { it.second }))
    }

    @Test
    fun `flattened segment 22 of house of the dragon resolves to an episode stream`() {
        val tmdbId = 94997 // Дом Дракона
        val details = get("$TMDB/tv/$tmdbId?$KEY&language=ru-RU&append_to_response=external_ids")
            .let { json.parseToJsonElement(it).jsonObject }
        val ext = details["external_ids"]?.jsonObject
        val imdb = details["imdb_id"]?.jsonPrimitive?.content
            ?: ext?.get("imdb_id")?.jsonPrimitive?.content.orEmpty()
        val title = details["name"]?.jsonPrimitive?.content.orEmpty()
        val original = details["original_name"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("TMDB must name the show", title.isNotBlank())

        val extractor = LampaExtractor(http)
        val kp = runCatching { extractor.kinopoiskIdForImdb(imdb) }.getOrNull()
        println("SMOKE title='$title' imdb=$imdb kp=$kp")
        val movie = buildMap<String, Any?> {
            put("id", kp ?: imdb.ifBlank { "tmdb-$tmdbId" })
            put("tmdb_id", tmdbId.toString())
            put("imdb_id", imdb)
            put("kinopoisk_id", kp.orEmpty())
            put("title", title)
            put("name", title)
            put("original_title", original)
            put("original_name", original)
            put("first_air_date", details["first_air_date"]?.jsonPrimitive?.content.orEmpty())
            put("release_date", details["first_air_date"]?.jsonPrimitive?.content.orEmpty())
            put("number_of_seasons", details["number_of_seasons"]?.jsonPrimitive?.intOrNull ?: 0)
            put("seasons", details["number_of_seasons"]?.jsonPrimitive?.intOrNull ?: 0)
        }

        val episodes = releasedEpisodes(tmdbId)
        println("SMOKE released episodes=${episodes.size} last=${episodes.lastOrNull()}")
        assertTrue("TMDB must list released episodes", episodes.size >= 18)
        val target = episodes.getOrNull(21) // segment 22, 1-based
        println("SMOKE segment 22 -> $target")
        assertNotNull("segment 22 must exist in the TMDB episode list", target)
        val (season, episode) = target!!

        // Mechanism check: a long-released episode must resolve through the plugin.
        val s2e6 = extractor.resolveEpisode(PLUGIN, BALANCER, movie, season = 2, episode = 6)
        println("SMOKE S2E6 -> ${s2e6?.title} ${s2e6?.url?.take(80)} q=${s2e6?.qualities?.map { it.first }}")
        assertNotNull("S2E6 must resolve via the Lampa plugin (mechanism broken)", s2e6)

        // The user's case: the exact episode behind segment 22.
        val stream = extractor.resolveEpisode(PLUGIN, BALANCER, movie, season = season, episode = episode)
        println("SMOKE S${season}E$episode -> ${stream?.title} ${stream?.url?.take(80)} q=${stream?.qualities?.map { it.first }}")
        if (stream == null) {
            // A missing episode is only a code failure when the balancers actually
            // carry it. The cdnvideohub playlist is the live content inventory.
            val playlist = extractor.seriesPlaylist(PLUGIN, BALANCER, movie).orEmpty()
            val onBalancer = Regex(""""season"\s*:\s*$season\s*,\s*"episode"\s*:\s*$episode""")
                .containsMatchIn(playlist)
            assertFalse(
                "S${season}E$episode exists on the balancer but did not resolve",
                onBalancer,
            )
            println("SMOKE S${season}E$episode is not on any live balancer yet (playlist has no S$season) — nothing to resolve")
            return
        }
        assertTrue(stream!!.url.startsWith("http"))
    }

    private companion object {
        const val TMDB = "https://api.themoviedb.org/3"
        const val KEY = "api_key=4ef0d7355d9ffb5151e987764708ce96"
        const val PLUGIN = "https://nb557.github.io/plugins/online_mod.js"
        const val BALANCER = "rezka"
    }
}

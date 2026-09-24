package com.aniblaze.desktop

import com.aniblaze.aggregator.source.*
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.HttpClient
import com.aniblaze.network.UserAgentInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Opt-in live check: ANIBLAZE_RELEASE_LIVE=1. Isolated state, never the user's history. */
class RecentEpisodeLiveTest {
    @Test fun `real sources fill a completely new release journal`() = runBlocking {
        if (System.getenv("ANIBLAZE_RELEASE_LIVE") != "1") return@runBlocking
        val client = OkHttpClient.Builder().addInterceptor(UserAgentInterceptor())
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).build()
        val http = HttpClient(client)
        val source = AnixartSource(http, KodikExtractor(client), SettingsDataStore())
        val skip = AniskipTimings(http)
        val settings = AppSettings(Files.createTempDirectory("aniblaze-release-live").resolve("state.json").toFile())
        val repo = DesktopRepository(listOf(source), emptyList(), settings,
            aniskip = skip, balancer = BalancerSource(http, KodikExtractor(client)),
            airDates = EpisodeAirDates(http, skip), http = http)
        val fresh = repo.homeSection("newEpisodes", 0)
        println("LIVE empty journal: ${fresh.size} recent titles")
        fresh.take(15).forEach { println("${it.id}: ${it.title}; episode=${it.episodesAvailable}; estimated=${it.episodeEstimatedAt}") }
        assertTrue(fresh.isNotEmpty(), "Fresh ongoing feed must populate on first launch")
        assertTrue(fresh.all { it.episodesAvailable > 0 })
        assertEquals(fresh.size, fresh.map { releaseTitleKey(it.title) }.distinct().size)
        val again = repo.homeSection("newEpisodes", 0)
        assertEquals(fresh, again, "Same episodes must not renew dates or reshuffle")
        assertTrue(settings.state.value.history.isEmpty())
        settings.flush()
    }
}

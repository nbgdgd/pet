package com.aniblaze.desktop

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.source.AniskipTimings
import com.aniblaze.aggregator.source.BalancerSource
import com.aniblaze.aggregator.source.EpisodeAirDates
import com.aniblaze.aggregator.source.KodikExtractor
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PlayableMetadataSourceTest {
    private class Source(override val name: String) : ContentAggregator {
        var searches = 0
        var failuresRemaining = 0
        override suspend fun search(query: String): List<Anime> {
            searches++
            if (failuresRemaining-- > 0) throw IOException("temporary source failure")
            return listOf(Anime("$name:1", query, ""))
        }
        override suspend fun extractContent(contentId: String, segment: Int): ContentResult? = null
        override suspend fun getContentSegments(contentId: String): List<Segment> = emptyList()
        override suspend fun trending(): List<Anime> = emptyList()
        override suspend fun validateSource(contentId: String): Boolean = true
    }

    @Test fun `metadata resolution never calls a disabled playback source`() = runBlocking {
        val enabled = Source("Enabled")
        val disabled = Source("Disabled")
        val settings = AppSettings(Files.createTempDirectory("aniblaze-metadata-source").resolve("state.json").toFile())
        settings.setPrimarySource(enabled.name)
        val okHttp = OkHttpClient()
        val http = HttpClient(okHttp)
        val skip = AniskipTimings(http)
        val repository = DesktopRepository(
            aggregators = listOf(enabled, disabled), cinemaSources = emptyList(), settings = settings,
            aniskip = skip, balancer = BalancerSource(http, KodikExtractor(okHttp)),
            airDates = EpisodeAirDates(http, skip),
        )

        assertNotNull(repository.resolvePlayableMetadata(Anime("metadata:1", "Тест", "")))
        assertEquals(1, enabled.searches)
        assertEquals(0, disabled.searches)
    }

    @Test fun `failed metadata lookup is retried instead of cached as unavailable`() = runBlocking {
        val source = Source("Enabled").also { it.failuresRemaining = 1 }
        val settings = AppSettings(Files.createTempDirectory("aniblaze-metadata-retry").resolve("state.json").toFile())
        settings.setPrimarySource(source.name)
        val okHttp = OkHttpClient()
        val http = HttpClient(okHttp)
        val skip = AniskipTimings(http)
        val repository = DesktopRepository(
            aggregators = listOf(source), cinemaSources = emptyList(), settings = settings,
            aniskip = skip, balancer = BalancerSource(http, KodikExtractor(okHttp)),
            airDates = EpisodeAirDates(http, skip),
        )
        val metadata = Anime("metadata:retry", "Тест", "")

        assertFailsWith<IOException> { repository.resolvePlayableMetadata(metadata) }
        assertNotNull(repository.resolvePlayableMetadata(metadata))
        assertEquals(2, source.searches)
    }
}

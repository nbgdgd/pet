package com.aniblaze.desktop

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
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
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Скрыть просмотренное» отсеивает досмотренное на нашей стороне, по каноническому id. */
class HideWatchedFilterTest {
    private class Source(private val items: List<Anime>) : ContentAggregator {
        override val name = "Anixart"
        override val supportsFilter = true
        override suspend fun filterPage(filter: CatalogFilter, page: Int): List<Anime> = if (page == 0) items else emptyList()
        override suspend fun search(query: String): List<Anime> = emptyList()
        override suspend fun extractContent(contentId: String, segment: Int): ContentResult? = null
        override suspend fun getContentSegments(contentId: String): List<Segment> = emptyList()
        override suspend fun trending(): List<Anime> = items
        override suspend fun validateSource(contentId: String): Boolean = true
    }

    @Test fun `досмотренное скрывается, начатое и новое остаются`() = runBlocking {
        val settings = AppSettings(Files.createTempDirectory("aniblaze-hide-watched").resolve("state.json").toFile())
        settings.setSourceEnabled("Anixart", true)
        val done = Anime("ax:1", "Досмотрено", "", episodesTotal = 2)
        val half = Anime("ax:2", "Начато", "", episodesTotal = 12)
        val fresh = Anime("ax:3", "Новое", "")
        // Досмотрено целиком; у второго — одна серия из двенадцати.
        settings.setTitleWatched(done, true)
        settings.recordEpisodeCount(half.id, 12)
        settings.setWatched(half.id, 1, true)

        val okHttp = OkHttpClient()
        val http = HttpClient(okHttp)
        val skip = AniskipTimings(http)
        val repo = DesktopRepository(
            aggregators = listOf(Source(listOf(done, half, fresh))), cinemaSources = emptyList(), settings = settings,
            aniskip = skip, balancer = BalancerSource(http, KodikExtractor(okHttp)), airDates = EpisodeAirDates(http, skip),
        )
        val all = repo.animeFilterPage(CatalogFilter(), sectionKey = null, page = 0)
        assertEquals(listOf("ax:1", "ax:2", "ax:3"), all.map { it.id })
        val hidden = repo.animeFilterPage(CatalogFilter(hideWatched = true), sectionKey = null, page = 0)
        assertEquals(listOf("ax:2", "ax:3"), hidden.map { it.id })
    }
}

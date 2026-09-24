package com.aniblaze.desktop

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogSort
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

/**
 * «По рейтингу» открывался «ужасом»: страница склеивалась из нескольких источников,
 * каждый отсортировал своё, а общего порядка не было. Теперь склейка сортируется
 * в долях шкалы — 4.6 из 5 и 9.2 из 10 сравниваются честно.
 */
class FilterSortAcrossSourcesTest {
    private class Source(override val name: String, private val items: List<Anime>) : ContentAggregator {
        override val supportsFilter = true
        override suspend fun filterPage(filter: CatalogFilter, page: Int): List<Anime> = if (page == 0) items else emptyList()
        override suspend fun search(query: String): List<Anime> = emptyList()
        override suspend fun extractContent(contentId: String, segment: Int): ContentResult? = null
        override suspend fun getContentSegments(contentId: String): List<Segment> = emptyList()
        override suspend fun trending(): List<Anime> = items
        override suspend fun validateSource(contentId: String): Boolean = true
    }

    @Test fun `рейтинг - общий порядок поверх источников, в долях шкалы`() = runBlocking {
        val settings = AppSettings(Files.createTempDirectory("aniblaze-sort").resolve("state.json").toFile())
        settings.setSourceEnabled("Anixart", true)
        settings.setSourceEnabled("YummyAnime", true)
        val anixart = Source("Anixart", listOf(
            Anime("ax:1", "Хорошее", "", rating = 4.8, ratingMax = 5.0),
            Anime("ax:2", "Среднее", "", rating = 4.0, ratingMax = 5.0),
        ))
        val yummy = Source("YummyAnime", listOf(
            Anime("ya:1", "Ужасное", "", rating = 3.0, ratingMax = 10.0),
            Anime("ya:2", "Отличное", "", rating = 9.4, ratingMax = 10.0),
        ))
        val okHttp = OkHttpClient()
        val http = HttpClient(okHttp)
        val skip = AniskipTimings(http)
        val repo = DesktopRepository(
            aggregators = listOf(yummy, anixart), cinemaSources = emptyList(), settings = settings,
            aniskip = skip, balancer = BalancerSource(http, KodikExtractor(okHttp)), airDates = EpisodeAirDates(http, skip),
        )
        val page = repo.animeFilterPage(CatalogFilter(sort = CatalogSort.RATING), sectionKey = null, page = 0)
        assertEquals(listOf("Хорошее", "Отличное", "Среднее", "Ужасное"), page.map { it.title })
    }
}

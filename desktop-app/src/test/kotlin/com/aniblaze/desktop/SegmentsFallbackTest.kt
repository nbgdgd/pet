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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Серии пока недоступны» у карточки из избранного: владелец id либо выключен, либо
 * серий у него нет (анонс). Серии обязаны прийти от владельца даже выключенного, а
 * если и у него пусто — от другого включённого источника по названию.
 */
class SegmentsFallbackTest {
    private class Source(
        override val name: String,
        private val prefix: String,
        private val catalog: List<Anime>,
        private val episodes: Map<String, Int>,
    ) : ContentAggregator {
        override fun ownsContentId(contentId: String) = contentId.startsWith(prefix)
        override suspend fun search(query: String): List<Anime> = catalog.filter { it.title.contains(query, true) }
        override suspend fun extractContent(contentId: String, segment: Int): ContentResult? = null
        override suspend fun getContentSegments(contentId: String): List<Segment> =
            (1..(episodes[contentId] ?: 0)).map { Segment("$contentId#$it", contentId, it, "Серия $it") }
        override suspend fun trending(): List<Anime> = catalog
        override suspend fun validateSource(contentId: String): Boolean = true
    }

    private fun repository(settings: AppSettings, vararg sources: ContentAggregator): DesktopRepository {
        val okHttp = OkHttpClient()
        val http = HttpClient(okHttp)
        val skip = AniskipTimings(http)
        return DesktopRepository(
            aggregators = sources.toList(), cinemaSources = emptyList(), settings = settings,
            aniskip = skip, balancer = BalancerSource(http, KodikExtractor(okHttp)),
            airDates = EpisodeAirDates(http, skip),
        )
    }

    private val title = Anime("ax:1", "Чёрная кошка и класс ведьм", "", year = 2026)

    @Test fun `выключенный владелец всё равно отдаёт серии`() = runBlocking {
        val settings = AppSettings(Files.createTempDirectory("aniblaze-segments").resolve("state.json").toFile())
        val anixart = Source("Anixart", "ax:", listOf(title), mapOf("ax:1" to 4))
        val yummy = Source("YummyAnime", "ya:", emptyList(), emptyMap())
        settings.setPrimarySource("YummyAnime")
        val segments = repository(settings, anixart, yummy).segmentsDetailed("ax:1", title.title, title.year)
        assertEquals(4, segments.segments.size)
    }

    @Test fun `у владельца пусто - серии по названию у другого источника, id остаётся запрошенным`() = runBlocking {
        val settings = AppSettings(Files.createTempDirectory("aniblaze-segments2").resolve("state.json").toFile())
        val anixart = Source("Anixart", "ax:", listOf(title), mapOf("ax:1" to 0))
        val yummy = Source("YummyAnime", "ya:", listOf(title.copy(id = "ya:9")), mapOf("ya:9" to 3))
        settings.setSourceEnabled("Anixart", true)
        settings.setSourceEnabled("YummyAnime", true)
        val result = repository(settings, anixart, yummy).segmentsDetailed("ax:1", title.title, title.year)
        assertEquals(listOf(1, 2, 3), result.segments.map { it.number })
        assertTrue(result.segments.all { it.contentId == "ax:1" }, "contentId подменён: ${result.segments.first().contentId}")
        assertTrue(!result.failed, "failed=true")
    }

    @Test fun `другой год по названию не подходит`() = runBlocking {
        val settings = AppSettings(Files.createTempDirectory("aniblaze-segments3").resolve("state.json").toFile())
        val anixart = Source("Anixart", "ax:", listOf(title), mapOf("ax:1" to 0))
        val yummy = Source("YummyAnime", "ya:", listOf(title.copy(id = "ya:9", year = 2019)), mapOf("ya:9" to 3))
        settings.setSourceEnabled("Anixart", true)
        settings.setSourceEnabled("YummyAnime", true)
        val result = repository(settings, anixart, yummy).segmentsDetailed("ax:1", title.title, title.year)
        assertTrue(result.segments.isEmpty())
    }
}

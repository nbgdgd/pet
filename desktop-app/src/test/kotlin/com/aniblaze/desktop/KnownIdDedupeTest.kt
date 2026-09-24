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

/**
 * Один тайтл из двух источников: в ленте должен остаться тот id, под которым он
 * УЖЕ есть у пользователя (история/избранное/прогресс), — иначе плашка
 * «остановились на…» и звёздочка избранного пропадают, стоит включить второй
 * источник, у которого тот же тайтл идёт под своим id.
 */
class KnownIdDedupeTest {
    private class Source(override val name: String, private val items: List<Anime>) : ContentAggregator {
        override suspend fun search(query: String): List<Anime> = emptyList()
        override suspend fun extractContent(contentId: String, segment: Int): ContentResult? = null
        override suspend fun getContentSegments(contentId: String): List<Segment> = emptyList()
        override suspend fun trending(): List<Anime> = items
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

    @Test fun `дубль из второго источника уступает id, который уже в истории`() = runBlocking {
        val settings = AppSettings(Files.createTempDirectory("aniblaze-dedupe").resolve("state.json").toFile())
        val anixart = Source("Anixart", listOf(Anime("ax:609", "Наруто", "", year = 2002)))
        val yummy = Source("YummyAnime", listOf(Anime("ya:111", "Наруто", "", year = 2002)))
        settings.setSourceEnabled("Anixart", true)
        settings.setSourceEnabled("YummyAnime", true)
        settings.recordHistory(Anime("ya:111", "Наруто", ""))

        // Anixart первым в списке — без предпочтения победил бы `ax:609`.
        val merged = repository(settings, anixart, yummy).trending()
        assertEquals(listOf("ya:111"), merged.map { it.id })
    }

    @Test fun `без следа побеждает первый источник`() = runBlocking {
        val settings = AppSettings(Files.createTempDirectory("aniblaze-dedupe-plain").resolve("state.json").toFile())
        val anixart = Source("Anixart", listOf(Anime("ax:609", "Наруто", "", year = 2002)))
        val yummy = Source("YummyAnime", listOf(Anime("ya:111", "Наруто", "", year = 2002)))
        settings.setSourceEnabled("Anixart", true)
        settings.setSourceEnabled("YummyAnime", true)

        val merged = repository(settings, anixart, yummy).trending()
        assertEquals(listOf("ax:609"), merged.map { it.id })
    }
}

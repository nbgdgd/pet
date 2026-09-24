package com.aniblaze.desktop

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.*
import com.aniblaze.aggregator.source.*
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.nio.file.Files
import kotlin.test.*

class CatalogReleaseIntegrationTest {
    private class Source(override val name: String) : ContentAggregator {
        var requests = 0
        var cards = emptyList<Anime>()
        override fun ownsContentId(contentId: String) = cards.any { it.id == contentId }
        override suspend fun search(query: String) = cards
        override suspend fun trending() = cards
        override suspend fun catalog(category: String): List<Anime> { requests++; return cards }
        override suspend fun catalogPage(sort: Int, page: Int): List<Anime> { requests++; return cards }
        override suspend fun latestReleases(page: Int): List<Anime> { requests++; return if (page == 0) cards else emptyList() }
        override suspend fun extractContent(contentId: String, segment: Int): ContentResult? = null
        override suspend fun getContentSegments(contentId: String) = emptyList<Segment>()
        override suspend fun validateSource(contentId: String) = true
    }
    private fun repository(sources: List<ContentAggregator>, settings: AppSettings): DesktopRepository {
        val ok = OkHttpClient()
        val http = HttpClient(ok)
        val skip = AniskipTimings(http)
        return DesktopRepository(sources, emptyList(), settings, aniskip = skip,
            balancer = BalancerSource(http, KodikExtractor(ok)), airDates = EpisodeAirDates(http, skip))
    }
    private fun settings() = AppSettings(Files.createTempDirectory("aniblaze-catalog-test").resolve("state.json").toFile())

    @Test fun `home section survives restart without replaying provider count differences`() = runBlocking {
        val settings = settings()
        val a = Source("Anixart")
        val b = Source("AniLibria")
        a.cards = listOf(Anime("ax:1", "Один сериал", "", episodesAvailable = 10))
        b.cards = listOf(Anime("alias", "Один сериал", "", episodesAvailable = 9))
        val first = repository(listOf(a, b), settings)
        assertTrue(first.homeSection("newEpisodes", 0).isEmpty())
        a.cards = listOf(a.cards.single().copy(episodesAvailable = 11))
        val next = repository(listOf(a, b), settings)
        assertEquals(listOf("ax:1"), next.homeSection("newEpisodes", 0).map { it.id })
        b.cards = listOf(b.cards.single().copy(episodesAvailable = 11))
        val restarted = repository(listOf(a, b), settings)
        assertEquals(1, restarted.homeSection("newEpisodes", 0).size)
        assertEquals(11, restarted.recentEpisodeTitles().single().episodesAvailable)
    }
    @Test fun `recommendation pool cache avoids refetch on an unchanged taste`() = runBlocking {
        val source = Source("Anixart")
        source.cards = listOf(Anime("ax:1", "Сериал", "", genres = "спорт", episodesAvailable = 10))
        val repo = repository(listOf(source), settings())
        val first = repo.recommendationPool()
        val calls = source.requests
        assertTrue(calls > 0)
        assertEquals(first, repo.recommendationPool())
        assertEquals(calls, source.requests)
    }
    @Test fun `anixart request uses status id and parses available instead of planned episodes`() = runBlocking {
        var requestBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            requestBody = buffer.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"content":[{"id":99,"title_ru":"Тест","episodes_released":10,"episodes_total":12,"vote_count":200,"status":{"id":2}}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val source = AnixartSource(HttpClient(client), KodikExtractor(client), SettingsDataStore())
        val card = source.ongoingPage(0).single()
        assertTrue(requestBody.contains("\"status_id\":2"))
        assertFalse(requestBody.contains("\"status\":"))
        assertEquals(10, card.episodesAvailable)
        assertEquals(12, card.episodesTotal)
        assertEquals(200, card.ratingVotes)
        assertEquals(0, card.episodeReleasedAt)
    }
    @Test fun `anilibria retains genre data and counts only playable episode records`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""[{"alias":"test","name":{"main":"Тест"},"genres":[{"name":"Спорт"}],"is_ongoing":true,"episodes_total":12,"episodes":[{"ordinal":10,"hls_720":"https://example.test/10.m3u8"},{"ordinal":11}]}]"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val card = AniLibriaSource(HttpClient(client)).search("test").single()
        assertEquals("Спорт", card.genres)
        assertEquals(10, card.episodesAvailable)
        assertEquals(12, card.episodesTotal)
        assertEquals(2, card.airingStatus)
    }
    @Test fun `old undated flags do not survive as fresh announcements and count cannot oscillate`() {
        val settings = settings()
        val anime = Anime("ax:1", "Тест", "")
        settings.recordHistory(anime)
        settings.flagNewEpisode(anime.id, 12)
        assertTrue(settings.newEpisodeTitles().isEmpty())
        assertTrue(settings.unannouncedEpisodes().isEmpty())
        assertFalse(settings.recordEpisodeCount(anime.id, 10))
        assertFalse(settings.recordEpisodeCount(anime.id, 8))
        assertFalse(settings.recordEpisodeCount(anime.id, 10))
        assertTrue(settings.recordEpisodeCount(anime.id, 11))
        assertEquals(listOf(anime.id), settings.newEpisodeTitles().map { it.id })
        settings.flush()
    }
}

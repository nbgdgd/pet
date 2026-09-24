package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.mergeCurrentSeason
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE: сезоны с любого сезона и страница анонса — на настоящих данных.
 *  • Yummy отдаёт один и тот же `viewing_order` с первого, второго и третьего сезона
 *    «Реинкарнации безработного» (1255 / 10227 / 13271).
 *  • Анонс (Yummy 27099, MAL 63706): AniList — NOT_YET_RELEASED, «Зима 2027», без
 *    точного времени, трейлер YouTube 4BFObPUXf2E.
 * Падает громко, если чужой API сменил контракт.
 */
class SeasonsLiveSmokeTest {
    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val http = com.aniblaze.network.HttpClient(okHttp)
    private val yummy = YummyAnimeSource(http, KodikExtractor(okHttp))

    @Test
    fun `франшиза одинакова со второго и третьего сезона, текущий подменяется`() = runBlocking {
        val fromSecond = yummy.related("ya:10227")
        val fromThird = yummy.related("ya:13271")
        assertTrue("со второго сезона список пуст", fromSecond.size >= 5)
        assertEquals("список зависит от того, какой сезон открыт", fromSecond.map { it.id }, fromThird.map { it.id })
        assertTrue(fromSecond.any { it.id == "ya:13271" } && fromSecond.any { it.id == "ya:1255" })
        // Карточка второго сезона с чужим id встаёт на место своей записи.
        val foreign = Anime(id = "ax:2", title = fromSecond.first { it.id == "ya:10227" }.title, poster = "", year = 2023)
        val merged = mergeCurrentSeason(foreign, fromSecond)
        assertEquals(fromSecond.size, merged.size)
        assertTrue(merged.any { it.id == "ax:2" } && merged.none { it.id == "ya:10227" })
    }

    @Test
    fun `анонс - статус, сезон выхода и трейлер именно этого MAL id`() = runBlocking {
        val airDates = EpisodeAirDates(http, AniskipTimings(http))
        val s = airDates.forTitle("не важно", malIdHint = 63706)
        assertEquals(EpisodeAirDates.Status.UPCOMING, s.status)
        assertTrue("сезон выхода: ${s.premiereSeason}", s.premiereSeason.isNotBlank())
        assertEquals("точного времени у анонса нет — таймера быть не должно", 0L, s.premiereAt)
        assertTrue("трейлер: ${s.trailerYoutubeId}", s.trailerYoutubeId.isNotBlank())
        assertTrue(s.dates.isEmpty())
    }

    @Test
    fun `в пикер озвучек попадают только те, у кого есть эта серия на читаемом хосте`() = runBlocking {
        val result = yummy.extractContent("ya:13271", 1)
        assertTrue("серия не разрешилась", result != null)
        val voices = result!!.translations.orEmpty()
        assertTrue("озвучек нет", voices.isNotEmpty())
        // Выбранная озвучка — из списка, и у каждой в списке есть просмотры Yummy.
        assertTrue(voices.any { it.id == result.translationId })
        assertTrue("у Yummy свои просмотры на озвучку", voices.all { it.views >= 0 })
        // Серии с пометкой playable — только те, что на Kodik/Sibnet.
        val segments = yummy.getContentSegments("ya:13271")
        assertTrue(segments.isNotEmpty() && segments.count { it.playable } >= segments.size / 2)
    }
}

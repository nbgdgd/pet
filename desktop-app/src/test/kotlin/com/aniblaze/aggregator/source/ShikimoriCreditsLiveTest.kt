package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

class ShikimoriCreditsLiveTest {
    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val source = ShikimoriSource(HttpClient(okHttp), okHttp)

    @Test fun `title has studio and exact director`() = runBlocking {
        val title = Anime(
            id = "ax:example", title = "Провожающая в последний путь Фрирен",
            status = "Sousou no Frieren", poster = "", year = 2023,
        )
        val credits = assertNotNull(source.titleCredits(title))
        assertTrue(credits.studios.any { it.name == "Madhouse" && it.id > 0 })
        assertTrue(credits.directors.any { it.name.contains("Сайто") })

        // A second read is served by the session cache and is structurally identical.
        assertEquals(credits, source.titleCredits(title))
    }

    @Test fun `studio page and director works carry display metadata`() = runBlocking {
        val studio = com.aniblaze.aggregator.model.StudioCredit(11, "Madhouse")
        val page = source.studioTitles(studio, page = 1)
        assertTrue(page.titles.isNotEmpty())
        assertTrue(page.titles.all { it.title.isNotBlank() && it.ratingMax == 10.0 })

        val director = com.aniblaze.aggregator.model.PersonCredit(50900, "Кэйитиро Сайто")
        val details = assertNotNull(source.personDetails(director))
        assertTrue("details=$details", details.works.isNotEmpty())
        assertTrue(details.works.all { it.role == "Режиссёр" || it.role.equals("Director", true) })
    }
}

package com.aniblaze.aggregator.source

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE-проверка SameBand (sameband.studio). Поток — собственный HLS студии
 * 480/720/1080; проверяется, что он реально отдаётся.
 */
class SameBandSourceSmokeTest {

    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val source = SameBandSource(com.aniblaze.network.HttpClient(okHttp))

    @Test
    fun `каталог даёт карточки с постерами`() = runBlocking {
        val items = source.trending()
        assertTrue("каталог пуст", items.isNotEmpty())
        assertTrue("каталог подозрительно мал: ${items.size}", items.size >= 50)
        val first = items.first()
        assertTrue("id без префикса: ${first.id}", first.id.startsWith("sb:"))
        assertTrue("постер не абсолютный: ${first.poster}", first.poster.startsWith("https://"))
        val blank = items.filter { it.poster.isBlank() }
        assertTrue("карточки без постера: ${blank.map { it.title }}", blank.isEmpty())
        assertTrue("вторая страница должна быть пустой", source.catalogPage(0, 1).isEmpty())
    }

    @Test
    fun `первый тайтл каталога - серии и настоящий HLS`() = runBlocking {
        val title = source.trending().first()
        val segments = source.getContentSegments(title.id)
        assertTrue("серий не найдено у ${title.id}", segments.isNotEmpty())
        assertTrue("серии не отсортированы", segments.map { it.number } == segments.map { it.number }.sorted())
        val result = source.extractContent(title.id, segments.first().number)
        assertTrue("поток не резолвится у ${title.id}", result != null)
        val res = result!!
        assertTrue("не sameband: ${res.location}", res.location.startsWith(SameBandSource.SITE))
        assertTrue("не HLS: ${res.location}", res.isHls)
        assertTrue("качеств ${res.variants?.size}", (res.variants?.size ?: 0) >= 2)
        StreamCheck.assertStreamServed(okHttp, res)
    }

    @Test
    fun `разбор playerjs-плейлиста`() {
        val json = """[
          {"title": "<img src='/v/anime/X/SnapShots/a.jpg' class=playlist_poster><div class=playlist_duration>25:31</div>Серия 01",
           "file": "[480p]/v/anime/X Y/X Y - 01_RUS_2/index.m3u8,[720p]/v/anime/X Y/X Y - 01_RUS_1/index.m3u8,[1080p]/v/anime/X Y/X Y - 01_RUS_0/index.m3u8"},
          {"title": "Серия 02", "file": "[720p]/v/anime/X/02/index.m3u8"}
        ]"""
        val eps = SameBandSource.parsePlaylist(json)
        assertEquals(listOf(1, 2), eps.map { it.number })
        assertEquals(listOf("1080p", "720p", "480p"), eps[0].variants.map { it.quality })
        assertEquals("https://sameband.studio/v/anime/X%20Y/X%20Y%20-%2001_RUS_0/index.m3u8", eps[0].variants.first().url)
    }
}

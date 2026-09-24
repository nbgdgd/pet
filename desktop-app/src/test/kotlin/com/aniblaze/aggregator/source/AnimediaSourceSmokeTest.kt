package com.aniblaze.aggregator.source

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE-проверка Animedia (amd.online → aser.pro). Ценность источника — СВОЙ HLS без
 * Kodik, поэтому главная проверка здесь: master-плейлист с aser.pro реально отдаётся.
 */
class AnimediaSourceSmokeTest {

    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val source = AnimediaSource(com.aniblaze.network.HttpClient(okHttp))

    @Test
    fun `каталог с главной даёт карточки с постерами`() = runBlocking {
        val items = source.trending()
        assertTrue("каталог пуст", items.isNotEmpty())
        val first = items.first()
        assertTrue("id без префикса: ${first.id}", first.id.startsWith("am:"))
        assertTrue("постер не абсолютный: ${first.poster}", first.poster.startsWith("https://"))
        val blank = items.filter { it.poster.isBlank() }
        assertTrue("карточки без постера: ${blank.map { it.title }}", blank.isEmpty())
        assertTrue("id без slug: ${first.id}", first.id.count { it == ':' } == 2)
    }

    @Test
    fun `поиск находит тайтл`() = runBlocking {
        val hits = source.search("Наруто")
        assertTrue("поиск ничего не вернул", hits.isNotEmpty())
        assertTrue("нет Наруто в ${hits.map { it.title }}", hits.any { it.title.contains("Наруто", ignoreCase = true) })
        assertTrue("в поиске карточки без постера: ${hits.filter { it.poster.isBlank() }.map { it.title }}", hits.all { it.poster.isNotBlank() })
    }

    @Test
    fun `свежий тайтл - серии и настоящий HLS с aser pro`() = runBlocking {
        val title = source.trending().first()
        val segments = source.getContentSegments(title.id)
        assertTrue("серий не найдено у ${title.id}", segments.isNotEmpty())
        assertTrue("серии не отсортированы", segments.map { it.number } == segments.map { it.number }.sorted())
        val playable = segments.first { it.playable }
        val result = source.extractContent(title.id, playable.number)
        assertTrue("поток не резолвится у ${title.id} ep ${playable.number}", result != null)
        val res = result!!
        assertTrue("не aser.pro: ${res.location}", res.location.contains("aser.pro"))
        assertTrue("не HLS: ${res.location}", res.isHls)
        assertTrue("нет вариантов качества", !res.variants.isNullOrEmpty())
        StreamCheck.assertStreamServed(okHttp, res)
        // И конкретное качество тоже отдаётся, не только master.
        StreamCheck.assertStreamServed(okHttp, res.copy(location = res.variants!!.first().url))
    }

    @Test
    fun `разбор master-плейлиста - лучшее качество первым`() {
        val master = "https://aser.pro/content/stream/x/001_1/hls/index.m3u8"
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:RESOLUTION=640x360,BANDWIDTH=464000
            ./360/index.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=1280x720,BANDWIDTH=1046000
            ./720/index.m3u8
        """.trimIndent()
        val variants = AnimediaSource.variantsOf(master, body)
        assertEquals(listOf("720p", "360p"), variants.map { it.quality })
        assertEquals("https://aser.pro/content/stream/x/001_1/hls/720/index.m3u8", variants.first().url)
    }

    @Test
    fun `не вышедшие серии помечены неиграбельными`() {
        val html = """
            <a data-vid="1" data-vlnk="https://aser.pro/vod/47735" class="nav_video_links"></a>
            <a data-vid="2" data-vlnk="/index.php?do=nz&id=5736" class="nav_video_links"></a>
        """
        val eps = AnimediaSource.parseEpisodes(html)
        assertEquals(listOf(1, 2), eps.map { it.number })
        assertTrue(eps[0].vod.isNotBlank())
        assertTrue(eps[1].vod.isBlank())
    }
}

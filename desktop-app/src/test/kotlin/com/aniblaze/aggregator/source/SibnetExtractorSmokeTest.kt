package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.ContentResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE: Sibnet — единственный у YummyAnime/AniDUB хостинг с прогрессивным MP4.
 * Берём настоящий iframe из выдачи Yummy и проверяем, что MP4 отдаётся по Range.
 */
class SibnetExtractorSmokeTest {

    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val http = com.aniblaze.network.HttpClient(okHttp)

    @Test
    fun `sibnet-страница даёт mp4, и он отдаётся по Range`() = runBlocking {
        val videos = http.getHtml("https://api.yani.tv/anime/111/videos") ?: error("нет ответа Yummy")
        val iframe = Regex("""//video\.sibnet\.ru/shell\.php\?videoid=\d+""").find(videos)?.value
            ?: error("у Наруто на Yummy не осталось Sibnet-видео")
        val variants = SibnetExtractor(http).extract(iframe)
        assertTrue("mp4 не найден на $iframe", variants.isNotEmpty())
        val url = variants.first().url
        assertTrue("не mp4: $url", url.endsWith(".mp4") && url.startsWith("https://video.sibnet.ru/"))
        StreamCheck.assertStreamServed(okHttp, ContentResult(location = url, quality = "auto", referer = SibnetExtractor.REFERER))
    }
}

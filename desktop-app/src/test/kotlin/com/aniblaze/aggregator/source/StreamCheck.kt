package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.ContentResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue

/**
 * Общая для live-проверок часть: не «ссылка похожа на поток», а ПОТОК РЕАЛЬНО
 * ОТДАЁТСЯ. HLS — тело начинается с `#EXTM3U`; MP4 — сервер отвечает на диапазон
 * (200/206) и присылает байты. Referer берётся из результата, как сделает плеер.
 */
object StreamCheck {

    fun assertStreamServed(okHttp: OkHttpClient, result: ContentResult) {
        val url = result.location
        assertTrue("ссылка не абсолютная: ${url.take(80)}", url.startsWith("http"))
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
            .header("Range", "bytes=0-2047")
        result.referer?.let { builder.header("Referer", it) }
        okHttp.newCall(builder.build()).execute().use { response ->
            assertTrue("HTTP ${response.code} на ${url.take(80)}", response.code == 200 || response.code == 206)
            val head = response.body?.source()?.let { src ->
                src.request(2048)
                src.buffer.snapshot().utf8().take(2048)
            }.orEmpty()
            when {
                result.isHls -> assertTrue(
                    "не HLS-плейлист: «${head.take(60).replace('\n', ' ')}»",
                    head.trimStart().startsWith("#EXTM3U"),
                )
                else -> assertTrue("mp4 пришёл пустым", head.isNotEmpty())
            }
        }
    }
}

package com.aniblaze.aggregator.source

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE-проверка источника animeon.cc.
 *
 * Ходит в сеть намеренно: ломается тут именно контракт чужого API, и падать при
 * этом надо громко. Проверяется вся цепочка целиком — каталог, список серий и
 * настоящая ссылка на поток через [KodikExtractor].
 */
class AnimeOnSourceSmokeTest {

    private val okHttp = OkHttpClient.Builder()
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private val source = AnimeOnSource(
        com.aniblaze.network.HttpClient(okHttp),
        KodikExtractor(okHttp),
    )

    @Test
    fun `поиск находит тайтл и даёт постер`() = runBlocking {
        val hits = source.search("Блич")
        assertTrue("поиск ничего не вернул", hits.isNotEmpty())
        val first = hits.first()
        assertTrue("id без префикса: ${first.id}", first.id.startsWith("ao:"))
        assertTrue("постер не абсолютный: ${first.poster}", first.poster.startsWith("https://"))
    }

    @Test
    fun `список серий приходит для известного тайтла`() = runBlocking {
        val segments = source.getContentSegments(BLEACH)
        assertTrue("серий не найдено", segments.isNotEmpty())
        assertTrue("нумерация не с единицы: ${segments.map { it.number }}", segments.first().number >= 1)
        assertTrue("серии не отсортированы", segments.map { it.number } == segments.map { it.number }.sorted())
    }

    @Test
    fun `эпизод резолвится в реальный поток и список озвучек`() = runBlocking {
        val result = source.extractContent(BLEACH, 1)
        assertTrue("поток не резолвится", result != null)
        val res = result!!
        assertTrue("нет вариантов качества", !res.variants.isNullOrEmpty())
        assertTrue(
            "ссылка не похожа на поток: ${res.location.take(60)}",
            res.location.startsWith("http") && res.location.contains(".m3u8"),
        )
        // Ради этого источник и заводился: озвучек должно быть много.
        val dubs = res.translations.orEmpty()
        assertTrue("озвучек всего ${dubs.size}: ${dubs.map { it.name }}", dubs.size >= 4)
        assertTrue("выбранная озвучка вне списка", dubs.any { it.id == res.translationId })
    }

    private companion object {
        /** «Блич [ТВ-2, часть 4]», shikimori 60636 — девять озвучек на момент проверки. */
        const val BLEACH = "ao:60636"
    }
}

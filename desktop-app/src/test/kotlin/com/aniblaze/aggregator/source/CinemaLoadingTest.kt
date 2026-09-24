package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.lampa.LampaExtractor
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CinemaLoadingTest {
    @Test fun `detail and playback warmup share metadata and load plugin concurrently`() = runBlocking {
        val entered = CountDownLatch(2)
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            entered.countDown()
            check(entered.await(5, TimeUnit.SECONDS)) { "Metadata and plugin were loaded sequentially" }
            val body = if (path.endsWith(".js")) "Lampa.Component.add('test', function(){});"
                else """{"title":"Test film","overview":"Описание","external_ids":{"kinopoisk_id":"123"}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val extractor = LampaExtractor(client)
        val source = LampaCatalogSource(HttpClient(client), extractor, { "https://example.com/plugin.js" }, { "cdnvideohub" })
        try {
            source.preparePlayback("tmdb:42")
            assertEquals("Описание", source.description("tmdb:42"))
            source.preparePlayback("tmdb:42:t1")
            assertEquals(1, counts.getValue("/3/movie/42").get())
            assertEquals(1, counts.getValue("/plugin.js").get())
            source.preparePlayback("tmdbtv:42")
            assertEquals(1, counts.getValue("/3/tv/42").get(), "Movie and TV IDs must not collide")
        } finally { extractor.close() }
    }

    @Test fun `concurrent readers share a fetch and cache expires without retaining failures`() = runBlocking {
        var now = 0L
        var calls = 0
        val cache = CinemaMetadataCache<String>(capacity = 2, ttlMs = 100, clock = { now })
        val results = (1..12).map { async { cache.get("same") { calls++; delay(20); "value" } } }.awaitAll()
        assertEquals(List(12) { "value" }, results)
        assertEquals(1, calls)
        now = 101
        assertNull(cache.get("same") { calls++; null })
        assertEquals("fresh", cache.get("same") { calls++; "fresh" })
        assertEquals(3, calls)
        cache.get("two") { "2" }
        cache.get("three") { "3" }
        assertEquals("reloaded", cache.get("same") { "reloaded" }, "Cache must be bounded")
    }

    @Test fun `cancelled warmup releases cache lock for playback`() = runBlocking {
        val cache = CinemaMetadataCache<String>()
        val started = CompletableDeferred<Unit>()
        val warmup = launch { cache.get("film") { started.complete(Unit); awaitCancellation() } }
        started.await()
        warmup.cancelAndJoin()
        assertEquals("ready", withTimeout(1_000) { cache.get("film") { "ready" } })
    }
}

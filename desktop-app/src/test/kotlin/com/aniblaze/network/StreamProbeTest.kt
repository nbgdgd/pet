package com.aniblaze.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Проверка ссылок ДО плеера.
 *
 * Повод: за один сеанс все 22 адреса solodcdn ответили 404 — включая тот, что
 * тремя минутами раньше исправно играл. libVLC узнавал об этом только по таймауту
 * старта, девять секунд на каждую озвучку и каждое качество; со стороны это
 * выглядело как зависшая программа. Двухбайтовый запрос отвечает за десятки
 * миллисекунд.
 */
class StreamProbeTest {

    private val http = HttpClient(
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build(),
    )

    @Test
    fun `живой адрес отвечает успехом`() = runBlocking {
        val code = http.probe("https://api.anixart.tv/release/2804")
        assertNotNull("сервер не ответил вовсе", code)
        assertTrue("ожидали 2xx/206, получили $code", code!! in 200..299)
    }

    @Test
    fun `мёртвая ссылка выдаёт себя сразу, а не по таймауту плеера`() = runBlocking {
        val started = System.nanoTime()
        val code = http.probe(DEAD_STREAM)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("solodcdn должен отвечать 404 на протухшую ссылку", 404, code)
        // Смысл всей затеи: приговор выносится быстрее, чем плеер успел бы начать
        // ждать. Порог заведомо щедрый — важно, что это не девять секунд.
        assertTrue("проверка заняла ${elapsedMs}мс", elapsedMs < 3_000)
    }

    @Test
    fun `недостижимый хост не выдаёт приговор`() = runBlocking {
        // null = «не определилось»: вызывающий обязан отдать ссылку плееру, а не
        // объявить источник мёртвым из-за моргнувшей сети.
        assertEquals(null, http.probe("https://aniblaze-no-such-host.invalid/x.mp4"))
    }

    private companion object {
        /** Реальный адрес из журнала: играл, потом протух. */
        const val DEAD_STREAM =
            "https://p14.solodcdn.com/s/m/aHR0cHM6Ly9jbG91ZC5zb2xvZGNkbi5jb20vdXNlcnVwbG9hZHMvMzYxNThjYmEtNzJkZC00NzBiLTg0MTgtYjI4NzI2ODM3Mjk4/3fbed699b73ab804042957097f347a895c9f6737cb63d4028c5f03b7a4cf7e7e:20260"
    }
}

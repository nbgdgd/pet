package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.source.AnixartSource
import com.aniblaze.aggregator.source.KodikExtractor
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * LIVE-проверка воспроизведения Anixart от разбора ответа до настоящих кадров.
 *
 * Повод — жалоба «выбираю Anixart → 720p, плеер виснет на „Подготовка потока…“ и
 * „Загрузка… 0 %“ навсегда». Зелёная сборка и зелёные модульные тесты про это ничего
 * не говорят, поэтому здесь проходится ВЕСЬ путь на живой сети:
 *
 *     разбор Anixart → список качеств → проверка адресов → libVLC → первые кадры
 *
 * Что именно сторожится:
 *
 *  1. Источник вообще отдаёт непустой список вариантов с адресами.
 *  2. Хотя бы один адрес отвечает на проверку — иначе играть нечего в принципе.
 *  3. Живой адрес РЕАЛЬНО проигрывается в libVLC, причём с продолжения с середины
 *     (`:start-time`) — ровно так, как это делает плеер.
 *  4. Если какой-то адрес молчит, сторож [playbackNeverStarted] обязан вынести
 *     приговор в срок. Эта поломка и висела: с `:start-time` позиция приходит СРАЗУ,
 *     ещё до первого байта, и проверка «позиция больше нуля» считала мёртвый поток
 *     начавшимся — навсегда.
 *
 * Мёртвое 720p у самого раздающего тестом НЕ считается провалом: 19.08 замерено, что
 * solodcdn болеет пофайлово и вразнобой (у шестой серии молчит 720p, у четвёртой —
 * 480p). Провал — это «ничего не играет» или «молчание не опознано».
 */
class AnixartPlaybackLiveTest {

    private val contentId = System.getenv("ANIBLAZE_LIVE_TITLE") ?: "ax:18334"
    private val segment = System.getenv("ANIBLAZE_LIVE_EPISODE")?.toInt() ?: 6

    /** Продолжение с середины: та самая точка со скриншота (13:44). */
    private val resumeMs = System.getenv("ANIBLAZE_LIVE_RESUME_MS")?.toLong() ?: 824_718L

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val http = HttpClient(okHttp)
    private val source = AnixartSource(http, KodikExtractor(okHttp), SettingsDataStore())

    private data class Attempt(
        /** Насколько позиция ушла ДАЛЬШЕ точки запуска. */
        val position: Long,
        /** Что показывает сама libVLC — с уже применённой `:start-time`. */
        val rawPosition: Long,
        val length: Long,
        val decoded: Int,
        val elapsedNanos: Long,
    ) {
        val started: Boolean get() = position > 0 || decoded > 0
    }

    /**
     * Играет адрес теми же опциями, что и плеер, и ждёт признаков жизни.
     *
     * Позиция считается ОТ ТОЧКИ ЗАПУСКА: libVLC применяет `:start-time` внутри
     * демультиплексора, ещё не открыв вход, и сама по себе она ничего не доказывает.
     */
    private fun play(factory: MediaPlayerFactory, url: String, referer: String?, startMs: Long, waitMs: Long): Attempt =
        playRaw(factory, url, referer, startMs, waitMs)

    private fun playRaw(factory: MediaPlayerFactory, url: String, referer: String?, startMs: Long, waitMs: Long): Attempt {
        val player = factory.mediaPlayers().newMediaPlayer()
        try {
            val options = buildList {
                add(":http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                referer?.takeIf { it.isNotBlank() }?.let { add(":http-referrer=$it") }
                if (startMs > 3_000) add(String.format(Locale.ROOT, ":start-time=%.3f", startMs / 1000.0))
                add(":network-caching=5000")
                add(":live-caching=1000")
                add(":http-reconnect")
            }
            val startedAt = System.nanoTime()
            player.media().play(url, *options.toTypedArray())
            var decoded = 0
            var position = 0L
            var length = 0L
            while (System.nanoTime() - startedAt < waitMs * 1_000_000L) {
                Thread.sleep(250)
                position = runCatching { player.status().time().coerceAtLeast(0) }.getOrDefault(0)
                length = runCatching { player.status().length().coerceAtLeast(0) }.getOrDefault(0)
                decoded = runCatching { player.media().info().statistics().decodedVideo() }.getOrDefault(0)
                // Признак жизни — движение ДАЛЬШЕ точки запуска либо настоящие кадры.
                if (position > startMs || decoded > 0) break
            }
            return Attempt(
                position = (position - startMs).coerceAtLeast(0),
                rawPosition = position,
                length = length,
                decoded = decoded,
                elapsedNanos = System.nanoTime() - startedAt,
            )
        } finally {
            runCatching { player.controls().stop() }
            // Освобождение отложено: stop() на застрявшем сетевом чтении возвращается
            // не мгновенно, и release() сразу за ним роняет JVM нативным нарушением
            // доступа (0xC0000005). Плеер тут живёт секунды, счёт им идёт на единицы —
            // отдадим их операционной системе на выходе из процесса, как это делает и
            // само приложение со своей фабрикой.
            Thread.sleep(300)
            runCatching { player.release() }
        }
    }

    @Test
    fun `Anixart играет от разбора до первых кадров`() {
        // --- 1. разбор -----------------------------------------------------------
        val res = runBlocking { source.extractContent(contentId, segment) }
        assertNotNull("Anixart не отдал поток для $contentId серия $segment", res)
        requireNotNull(res)
        val variants: List<StreamVariant> = res.variants?.takeIf { it.isNotEmpty() }
            ?: listOf(StreamVariant(res.quality.ifBlank { "Auto" }, res.location))
        println("[1] разбор: source=${res.source}; озвучка=${res.translationId}; вариантов=${variants.size}")
        variants.forEach { println("      ${it.quality.padEnd(22)} ${it.url}") }
        assertTrue("список качеств пуст", variants.isNotEmpty())
        assertTrue("у варианта пустой адрес", variants.all { it.url.isNotBlank() })

        // --- 2. проверка адресов --------------------------------------------------
        val referer = res.referer?.takeIf { it.isNotBlank() }
        val probed = variants.map { it to runBlocking { http.probe(it.url, referer) } }
        probed.forEach { (variant, code) ->
            println("[2] проверка: ${variant.quality.padEnd(22)} код=${code ?: "молчит"}")
        }
        val alive = probed.firstOrNull { (_, code) -> code != null && code !in setOf(404, 410) }?.first
        assertNotNull("ни один адрес Anixart не ответил — играть нечего", alive)
        requireNotNull(alive)

        // --- 3. настоящее воспроизведение ----------------------------------------
        assertTrue("libVLC не найдена — проверить воспроизведение нечем", VlcSupport.available)
        // Фабрику НЕ освобождаем — ровно как VlcRuntime в приложении: банк модулей
        // libVLC живёт до конца процесса, а его освобождение посреди работы валило
        // тестовую JVM нативным нарушением доступа (0xC0000005).
        val factory = MediaPlayerFactory("--vout=dummy", "--no-audio", "--quiet")
        run {
            val good = play(factory, alive.url, referer, resumeMs, waitMs = 25_000)
            println("[3] ${alive.quality}: сдвиг=${good.position} мс; длина=${good.length}; кадров=${good.decoded}; за ${good.elapsedNanos / 1_000_000} мс")
            assertTrue(
                "живой адрес ${alive.quality} не дал ни кадров, ни движения позиции — поток не начался",
                good.started,
            )

            // --- 4. сценарий жалобы целиком ---------------------------------------
            // Зритель выбрал 720p, а этот адрес молчит. Проходим ровно то, что делает
            // плеер: ждём отсечку, выносим приговор, берём следующий вариант по правилу
            // лестницы — и требуем, чтобы он ЗАИГРАЛ. Не «код собрался», а кадры пошли.
            val silent = probed.firstOrNull { (_, code) -> code == null }?.first
            if (silent == null) {
                println("[4] молчащих адресов сейчас нет — сценарий жалобы не воспроизводится")
                return
            }
            val dead = playRaw(factory, silent.url, referer, resumeMs, waitMs = 10_500)
            println(
                "[4] ${silent.quality} (молчит): позиция=${dead.rawPosition}; кадров=${dead.decoded}; " +
                    "за ${dead.elapsedNanos / 1_000_000} мс",
            )
            if (dead.decoded > 0 || dead.rawPosition > resumeMs) {
                // Раздающий ожил между проверкой и запуском — бывает, это не наш сбой.
                println("[4] адрес ожил после проверки, приговор неуместен")
                return
            }
            // ВОТ ОНА, ПОЛОМКА. libVLC рапортует заданную `:start-time` позицию, не
            // получив ни байта, и прежняя проверка «позиция больше нуля» объявляла
            // мёртвый поток начавшимся — навсегда.
            println("[4] прежнее правило (position <= 0) сказало бы: начался=${dead.rawPosition > 0}")
            assertTrue(
                "libVLC обязана рапортовать точку возобновления — иначе тест меряет не то",
                dead.rawPosition > 0,
            )
            assertTrue(
                "сторож обязан назвать мёртвый поток мёртвым, иначе «Подготовка потока…» висит навсегда",
                // Точка отсчёта = то, что libVLC доложила о себе первой: у мёртвого
                // потока она с тех пор не менялась.
                playbackNeverStarted(dead.elapsedNanos, dead.rawPosition, firstPosition = dead.rawPosition),
            )

            // --- 5. лестница обязана привести к играющему потоку -------------------
            val next = nextVariantAfterFailure(variants, silent.url, neverStarted = true)
            assertNotNull("после молчания лестнице нечего пробовать", next)
            requireNotNull(next)
            val recovered = play(factory, next.url, referer, resumeMs, waitMs = 25_000)
            println("[5] лестница выбрала ${next.quality}: кадров=${recovered.decoded}; за ${recovered.elapsedNanos / 1_000_000} мс")
            assertTrue(
                "после мёртвого ${silent.quality} лестница обязана довести до РЕАЛЬНО играющего потока",
                recovered.started,
            )
        }
    }
}

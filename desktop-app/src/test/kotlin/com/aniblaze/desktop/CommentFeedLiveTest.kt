package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.source.AniskipTimings
import com.aniblaze.aggregator.source.AnixartSource
import com.aniblaze.aggregator.source.BalancerSource
import com.aniblaze.aggregator.source.CommentStop
import com.aniblaze.aggregator.source.EpisodeAirDates
import com.aniblaze.aggregator.source.KodikExtractor
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.AnixartProxyInterceptor
import com.aniblaze.network.HttpClient
import com.aniblaze.network.UserAgentInterceptor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * LIVE-проверка ленты обсуждения ЧЕРЕЗ НАСТОЯЩИЙ репозиторий — тот самый путь, которым
 * ходят экран тайтла и плеер.
 *
 * Проверяется то, что не проверить на чистых функциях: порции действительно приходят по
 * ходу обхода, накопитель общий, а второе открытие того же тайтла НЕ ПРИПИСЫВАЕТ
 * собранное второй раз.
 *
 * Настройки трогаются только на чтение: [DesktopRepository.commentFeed] к ним не
 * обращается вовсе, а конструктор [AppSettings] на уже перенесённом файле лишь читает.
 */
class CommentFeedLiveTest {

    private val commentCacheDirectory = java.io.File(
        "build/tmp/comment-live-${System.nanoTime()}",
    ).apply { mkdirs() }

    private val cookies = ConcurrentHashMap<String, List<Cookie>>()
    private val okHttp = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, list: List<Cookie>) {
                cookies.compute(url.host) { _, previous ->
                    val fresh = list.map { it.name }.toSet()
                    previous.orEmpty().filterNot { it.name in fresh } + list
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host].orEmpty()
        })
        .addInterceptor(AnixartProxyInterceptor())
        .addInterceptor(UserAgentInterceptor())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val http = HttpClient(okHttp)
    private val anixart = AnixartSource(http, KodikExtractor(okHttp), SettingsDataStore())

    private fun repository(): DesktopRepository {
        val aniskip = AniskipTimings(http)
        return DesktopRepository(
            aggregators = listOf(anixart),
            cinemaSources = emptyList(),
            settings = AppSettings(),
            aniskip = aniskip,
            balancer = BalancerSource(http, KodikExtractor(okHttp)),
            airDates = EpisodeAirDates(http, aniskip),
            http = http,
            commentCacheDirectory = commentCacheDirectory,
        )
    }

    @Test
    fun `лента отдаёт порции по ходу обхода и не повторяется`() {
        val repo = repository()
        val naruto = runBlocking { runCatching { anixart.search("Наруто") }.getOrDefault(emptyList()) }
            .firstOrNull()
        if (naruto == null) {
            println("источник не ответил — проверять нечего")
            return
        }
        val anime = Anime(id = naruto.id, title = naruto.title, poster = naruto.poster)

        val batches = runBlocking { repo.commentFeed(anime, maxPages = 8).toList() }
        assertTrue("обсуждение обязано приходить порциями, а не одним куском в конце", batches.size >= 2)
        val last = batches.last()
        println(
            "[лента] ${anime.title.take(30)}: порций=${batches.size}; " +
                "итог=${last.comments.size}; завершён=${last.complete}; остановка=${last.stop}",
        )

        // Каждая следующая порция только растёт — экрану не приходится ничего склеивать.
        batches.zipWithNext().forEach { (before, after) ->
            assertTrue("порция не может стать меньше предыдущей", after.comments.size >= before.comments.size)
        }
        // И ни одного повтора внутри итогового списка.
        val ids = last.comments.map { it.id }
        assertEquals("в собранном обсуждении не должно быть повторов", ids.size, ids.toSet().size)

        // ВТОРОЕ ОТКРЫТИЕ ТОГО ЖЕ ТАЙТЛА. Кэш общий: собранное отдаётся сразу и
        // ВТОРОЙ РАЗ НЕ ПРИПИСЫВАЕТСЯ.
        val again = runBlocking { repo.commentFeed(anime, maxPages = 8).toList() }
        val repeated = again.last()
        println("[повторное открытие] порций=${again.size}; итог=${repeated.comments.size}")
        assertEquals(
            "повторное открытие не имеет права удвоить список",
            last.comments.size,
            repeated.comments.size,
        )
        assertEquals(repeated.comments.size, repeated.comments.map { it.id }.toSet().size)

        // Расширение бюджета продолжает обход, а не перечитывает начало.
        val extended = runBlocking { repo.commentFeed(anime, maxPages = 12).toList() }.last()
        println("[бюджет 12 страниц] итог=${extended.comments.size}")
        assertTrue(
            "расширенный бюджет обязан принести ещё записи, а не те же самые",
            extended.comments.size > last.comments.size || extended.stop != null,
        )
        assertEquals(extended.comments.size, extended.comments.map { it.id }.toSet().size)
    }

    @Test
    fun `короткое обсуждение доходит до настоящего конца`() {
        // Тайтл с обсуждением короче бюджета: обход обязан остановиться сам и сказать,
        // почему — а не упереться в потолок вызывающего.
        val repo = repository()
        val hit = runBlocking { runCatching { anixart.search("Ван-Пис") }.getOrDefault(emptyList()) }
            .firstOrNull() ?: return
        val anime = Anime(id = hit.id, title = hit.title, poster = hit.poster)
        val last = runBlocking { repo.commentFeed(anime, maxPages = 100).toList() }.lastOrNull() ?: return
        println("[конец] ${anime.title.take(30)}: итог=${last.comments.size}; остановка=${last.stop}")
        assertTrue("обход обязан завершиться по причине источника", last.complete)
        assertTrue(
            "и причина должна быть честной, а не предохранителем",
            last.stop == CommentStop.LAST_PAGE || last.stop == CommentStop.EMPTY_PAGE || last.stop == CommentStop.NO_NEW,
        )
    }
}

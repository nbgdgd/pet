package com.aniblaze.aggregator.source

import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.AnixartProxyInterceptor
import com.aniblaze.network.HttpClient
import com.aniblaze.network.UserAgentInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * LIVE-замер постраничной выдачи комментариев.
 *
 * Повод — жалоба: комментарии повторяются, их мало, и на длинных тайтлах (Наруто, One
 * Piece, Bleach) это видно особенно ясно. Здесь не проверка «работает / не работает», а
 * ИЗМЕРЕНИЕ: сколько страниц удалось пройти, сколько пришло записей, сколько среди них
 * действительно разных и на чём обход остановился.
 *
 * Живое, поэтому мягкое: если источник не ответил вовсе, замер молчит. Ловится
 * единственное — обход, который сам себя обрывает на первых страницах.
 */
class CommentsPaginationLiveTest {

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
    private val source = AnixartSource(http, KodikExtractor(okHttp), SettingsDataStore())

    private data class Walk(
        val pages: Int,
        val raw: Int,
        val unique: Int,
        val stop: String,
        val perPageDuplicates: Int,
        val sourcePages: Int,
        val sourceCount: Int,
    )

    /** Обходит страницы до настоящего конца и считает, что именно пришло. */
    private fun walk(id: String, maxPages: Int = 40): Walk = runBlocking {
        val seen = LinkedHashSet<Long>()
        var raw = 0
        var pages = 0
        var dupWithinPage = 0
        var stop = "достигнут потолок замера"
        var barren = 0
        var sourcePages = 0
        var sourceCount = 0
        for (page in 0 until maxPages) {
            val attempt = runCatching { source.comments(id, page) }
            if (attempt.isFailure) {
                stop = "ошибка: ${attempt.exceptionOrNull()?.javaClass?.simpleName}"
                break
            }
            val chunk = attempt.getOrNull()?.items.orEmpty()
            val envelope = attempt.getOrNull()
            if (envelope != null && envelope.totalPages > 0) sourcePages = envelope.totalPages
            if (envelope != null && envelope.totalCount > 0) sourceCount = envelope.totalCount
            pages++
            if (chunk.isEmpty()) { stop = "пустая страница"; break }
            raw += chunk.size
            val idsHere = chunk.map { it.id }
            dupWithinPage += idsHere.size - idsHere.toSet().size
            val before = seen.size
            seen += idsHere
            if (seen.size == before) {
                barren++
                if (barren >= 3) { stop = "три страницы подряд без новых"; break }
            } else {
                barren = 0
            }
        }
        Walk(pages, raw, seen.size, stop, dupWithinPage, sourcePages, sourceCount)
    }

    @Test
    fun `замер постраничной выдачи на длинных тайтлах`() {
        val titles = listOf("Наруто", "Ван-Пис", "Блич")
        var measured = 0
        for (query in titles) {
            val hit = runBlocking { runCatching { source.search(query) }.getOrDefault(emptyList()) }
                .firstOrNull() ?: continue
            val walk = walk(hit.id)
            measured++
            println(
                "[$query] ${hit.title.take(38)} (${hit.id}): страниц=${walk.pages}; " +
                    "получено=${walk.raw}; уникальных=${walk.unique}; " +
                    "повторов внутри страниц=${walk.perPageDuplicates}; " +
                    "повторов между страницами=${walk.raw - walk.unique - walk.perPageDuplicates}; " +
                    "источник заявляет=${walk.sourceCount}/${walk.sourcePages}стр; остановка: ${walk.stop}",
            )
            assertTrue(
                "[$query] обход не должен обрываться на первых страницах при живом источнике",
                walk.pages >= 5 || walk.stop == "пустая страница" || walk.stop.startsWith("ошибка"),
            )
        }
        if (measured == 0) println("источник не ответил — замерять нечего")
    }
}

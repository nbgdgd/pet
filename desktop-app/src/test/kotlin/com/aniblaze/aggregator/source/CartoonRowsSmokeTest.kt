package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.lampa.LampaExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE-проверка лент раздела «Мультфильмы».
 *
 * Ходит в сеть намеренно: ломается тут контракт чужого API, и падать при этом надо
 * громко. Раньше раздел состоял из двух списков «по типу», и подборок вроде «сейчас
 * смотрят» / «в тренде» в нём не было вовсе.
 *
 * Второй тест сторожит ловушку, из-за которой раздел наполовину состоял из аниме: в
 * адресе стоял `without_original_language=ja`, а такого параметра у TMDB нет — он его
 * молча игнорировал. ЗАМЕРЕНО: ответ с ним и без него совпадал (475 тайтлов, десять
 * японских в первых двадцати). Теперь японское отсеивается на своей стороне, и этот
 * тест не даст вернуться к «фильтру», который ничего не фильтрует.
 */
class CartoonRowsSmokeTest {

    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val http = com.aniblaze.network.HttpClient(okHttp)
    private val source = LampaCatalogSource(
        http = http,
        lampa = LampaExtractor(okHttp),
        pluginUrl = { "" },
        balancer = { "rezka2" },
    )

    /** Ленты, которые должен показывать раздел, — ровно как в его списке категорий. */
    private val rows = listOf(
        "cartoon:trending", "cartoon:new_episodes", "cartoon:hot",
        "cartoon:latest", "cartoon:popular", "cartoon:top",
        "cartoon:movie", "cartoon:tv",
    )

    @Test
    fun `каждая подборка мультфильмов что-то отдаёт`() = runBlocking {
        val empty = rows.filter { source.browsePath(it, 1).isEmpty() }
        assertTrue("пустые подборки: $empty", empty.isEmpty())
    }

    @Test
    fun `подборки листаются дальше первой страницы`() = runBlocking {
        // Без этого бесконечная прокрутка ряда упирается в двадцать карточек.
        val first = source.browsePath("cartoon:popular", 1).map { it.id }.toSet()
        val second = source.browsePath("cartoon:popular", 2)
        assertTrue("вторая страница пуста", second.isNotEmpty())
        assertTrue("вторая страница повторяет первую", second.any { it.id !in first })
    }

    @Test
    fun `аниме в мультфильмы не просачивается`() = runBlocking {
        // Это ровно те тайтлы, что стояли В НАЧАЛЕ лент до починки: «Топ рейтинга»
        // открывался Фрирен и Ван-Писом, «Популярное» — «Унесёнными призраками».
        val anime = setOf(
            "tmdbtv:209867", // Фрирен, провожающая в последний путь
            "tmdbtv:37854", // Ван-Пис
            "tmdbtv:1429", // Атака титанов
            "tmdbtv:31911", // Стальной Алхимик: Братство
            "tmdb:129", // Унесённые призраками
        )
        val leaked = rows.flatMap { row -> source.browsePath(row, 1).map { row to it } }
            .filter { (_, item) -> item.id in anime }
        assertTrue("аниме в мультфильмах: ${leaked.map { "${it.first} → ${it.second.title}" }}", leaked.isEmpty())
    }

    @Test
    fun `оценки приходят по десятибалльной шкале и с числом голосов`() = runBlocking {
        val items = source.browsePath("cartoon:popular", 1).filter { it.rating > 0 }
        assertTrue("нет ни одной оценки", items.isNotEmpty())
        assertTrue("шкала не проставлена", items.all { it.ratingMax == 10.0 })
        assertTrue("оценка вне шкалы: ${items.map { it.rating }}", items.all { it.rating <= 10.0 })
        // Голоса — вторая координата ранга; в самой популярной ленте они обязаны быть.
        assertTrue("голоса не приходят", items.any { it.ratingVotes > 0 })
    }
}

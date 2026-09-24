package com.aniblaze.aggregator.source

import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE-проверка списка сезонов.
 *
 * Стережёт ровно ту жалобу, с которой всё началось: у «Клинка, рассекающего демонов»
 * в меню сезонов было четыре пункта вместо двенадцати. Причина — `related_releases`
 * внутри `/release/{id}` отдаёт только три записи-затравки, а франшиза целиком лежит
 * за отдельным `/related/{franchiseId}/{page}`.
 *
 * Тест ходит в сеть намеренно: сломаться может именно контракт источника, и падать
 * при этом он должен громко.
 */
class AnixartSeasonsSmokeTest {

    private val okHttp = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val source = AnixartSource(
        HttpClient(okHttp),
        KodikExtractor(okHttp),
        SettingsDataStore(),
    )

    @Test
    fun `франшиза Клинка приходит целиком, а не тремя записями`() = runBlocking {
        val seasons = source.related("ax:$DEMON_SLAYER_FIRST_SEASON")
        assertTrue(
            "Сезонов пришло ${seasons.size}, а во франшизе их больше десяти: " +
                seasons.joinToString { it.title },
            seasons.size >= 10,
        )
        // Внутри — сам тайтл (меню строится по этому списку) и заведомо поздний сезон.
        assertTrue("нет первого сезона", seasons.any { it.id == "ax:$DEMON_SLAYER_FIRST_SEASON" })
        assertTrue("нет «Квартала красных фонарей»", seasons.any { it.title.contains("красных фонарей") })
        // Порядок выхода: первым идёт самый ранний год, аноны без года — в хвосте.
        val years = seasons.map { it.year }.filter { it > 0 }
        assertTrue("порядок не по годам: $years", years == years.sorted())
    }

    @Test
    fun `любой сезон франшизы видит тот же полный список`() = runBlocking {
        val fromLateSeason = source.related("ax:$DEMON_SLAYER_LATE_SEASON")
        assertTrue("с позднего сезона список обрезан: ${fromLateSeason.size}", fromLateSeason.size >= 10)
    }

    private companion object {
        const val DEMON_SLAYER_FIRST_SEASON = 2804
        const val DEMON_SLAYER_LATE_SEASON = 19938
    }

    @Test
    fun `лента анонсов Anixart - невышедшее с заметным числом добавлений`() = runBlocking {
        // Ряд «Ожидаемые анонсы» строится из неё независимо от основного источника.
        val page = source.catalogPage(6, 0)
        assertTrue("лента анонсов пуста", page.isNotEmpty())
        val expected = page.filter { it.rating < 1.0 && it.favoritesCount >= 500 }
        assertTrue("нет ни одного анонса с ≥500 в избранном: ${page.take(3).map { it.title to it.favoritesCount }}", expected.isNotEmpty())
    }
}

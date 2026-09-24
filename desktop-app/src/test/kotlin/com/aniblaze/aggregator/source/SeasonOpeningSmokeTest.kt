package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.OpeningRange
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Пропуск опенинга на ЖИВЫХ данных AniSkip.
 *
 * Здесь проверяется не арифметика (для неё есть [SeasonOpeningTest]), а то, что на
 * настоящем тайтле с дырявым покрытием кнопка попадает куда надо. Тайтл выбран не
 * случайно: именно на нём жалоба и воспроизводилась.
 *
 * Тест ходит в сеть. Если AniSkip недоступен — он молча пропускается: сломанная
 * сборка из-за чужого сервера никому не помогает.
 */
class SeasonOpeningSmokeTest {

    private val http = com.aniblaze.network.HttpClient(
        OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build(),
    )

    /** «Чёрная кошка и класс ведьм» — на нём и промахивался пропуск. */
    private val mal = 62171

    /** Адрес AniSkip: у самого класса он приватный, а тест ходит туда напрямую. */
    private val ANISKIP = "https://api.aniskip.com/v2"

    private fun fetch(episode: Int): OpeningRange? = runBlocking {
        // Одна повторная попытка: AniSkip изредка обрывает соединение, и молчаливый
        // пропуск теста маскировал бы это под «данных нет».
        var body = http.getText("$ANISKIP/skip-times/$mal/$episode?types=op&episodeLength=0")
        if (body == null) {
            Thread.sleep(700)
            body = http.getText("$ANISKIP/skip-times/$mal/$episode?types=op&episodeLength=0")
        }
        if (body == null) {
            println("серия $episode: ответа нет вовсе")
            return@runBlocking null
        }
        if (!body.isSuccessful) {
            if (body.code != 404) println("серия $episode: код ${body.code}")
            return@runBlocking null
        }
        val text = body.body ?: return@runBlocking null
        val root = org.json.JSONObject(text)
        if (!root.optBoolean("found")) return@runBlocking null
        val results = root.optJSONArray("results") ?: return@runBlocking null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val interval = item.optJSONObject("interval") ?: continue
            val start = interval.optDouble("startTime", Double.NaN)
            val end = interval.optDouble("endTime", Double.NaN)
            if (start.isNaN() || end.isNaN()) continue
            return@runBlocking OpeningRange((start * 1000).toLong(), (end * 1000).toLong())
        }
        null
    }

    @Test
    fun `донор для незаполненной серии не берётся у первой`() {
        val probes = seasonProbeOrder(episode = 12, limit = SEASON_PROBES)
        val found = probes.mapNotNull { probe -> fetch(probe)?.let { probe to it } }
        // Частичный ответ AniSkip (в этом прогоне все соседние серии вернули 500,
        // а первая — 200) не даёт выбрать ДРУГОГО донора. Это недоступность внешних
        // данных, а не повод объявить продовый выбор первой серии регрессией.
        if (found.size < 2) {
            println("AniSkip не отдал хотя бы двух кандидатов — проверять выбор донора нечем")
            return
        }
        println("найдено: " + found.joinToString { "серия ${it.first}: ${it.second.startMs}-${it.second.endMs}" })
        val picked = pickSeasonRange(found.map { it.second })
        assertNotNull(picked)
        // Первая серия начинает опенинг на 2.1 секунде — это и был промах. Любой
        // разумный донор для двенадцатой серии стартует заметно позже.
        assertTrue(
            "выбран интервал первой серии: ${picked!!.startMs}-${picked.endMs}",
            picked.startMs > 10_000L,
        )
    }

    @Test
    fun `у серии с собственными данными ничего не занимается`() {
        // Восьмая серия заполнена: подмена донором была бы ошибкой.
        val own = fetch(8)
        if (own == null) {
            println("AniSkip недоступен — проверять нечего")
            return
        }
        assertTrue("собственный интервал обязан быть валидным: $own", own.isValid)
        assertTrue("и он не должен совпасть с первой серией", own.startMs > 10_000L)
    }

    @Test
    fun `длительность опенинга по сезону стабильна`() {
        // На этом и держится вся затея с заимствованием: ролик один и тот же, поэтому
        // занять чужой интервал можно. Разъезжается только НАЧАЛО — из-за холодного
        // открытия. Если бы разъезжалась и длина, занимать было бы нельзя вовсе.
        val durations = listOf(1, 2, 8).mapNotNull { fetch(it)?.let { r -> r.endMs - r.startMs } }
        if (durations.size < 2) {
            println("AniSkip недоступен — проверять нечего")
            return
        }
        val spread = durations.max() - durations.min()
        println("длительности: $durations; разброс $spread мс")
        assertTrue("длина опенинга внутри сезона обязана быть почти одинаковой: $durations", spread <= 5_000L)
    }

    @Test
    fun `начала опенинга внутри сезона разъезжаются — поэтому интервал и приблизительный`() {
        val starts = listOf(1, 2, 8).mapNotNull { fetch(it)?.startMs }
        if (starts.size < 2) {
            println("AniSkip недоступен — проверять нечего")
            return
        }
        val spread = starts.max() - starts.min()
        println("начала: $starts; разброс $spread мс")
        // Именно из-за этого разброса занятый интервал помечается approximate и не
        // допускается к автопропуску.
        assertEquals("разброс обязан быть заметным — иначе исправлять было бы нечего", true, spread > 10_000L)
    }
}

package com.aniblaze.aggregator.source

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE-проверка источника оценок, на котором стоит «Шлакометр».
 *
 * Оценка берётся не из каталога, а из Shikimori (его идентификаторы и есть
 * идентификаторы MyAnimeList). Если сломается поиск по названию или формат ответа,
 * шкала молча замрёт на нуле — а по экрану будет не отличить «ничего не нашли» от
 * «ты смотришь только шедевры». Поэтому цепочка проверяется целиком и громко.
 */
class CommunityScoreSmokeTest {

    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val timings = AniskipTimings(com.aniblaze.network.HttpClient(okHttp))

    @Test
    fun `оценка и распределение голосов приходят по русскому названию`() = runBlocking {
        val score = timings.communityScore("Клинок, рассекающий демонов", "Kimetsu no Yaiba")
        assertNotNull("оценка не найдена по названию из истории", score)
        val (value, votes, low) = score!!
        assertTrue("оценка вне шкалы MAL: $value", value > 0.0 && value <= 10.0)
        assertTrue("голосов ноль — судить будет не по чему", votes > 0)
        assertTrue("низких оценок больше, чем всех голосов: $low из $votes", low in 0..votes)
    }

    @Test
    fun `набирается порог, при котором вердикт вообще выносится`() = runBlocking {
        // Ниже MIN_VOTES тайтл в «Шлакометре» не участвует. У известного сериала
        // голосов должны быть тысячи — если их вдруг единицы, сломался разбор ответа.
        val score = timings.communityScore("Наруто", "Naruto")
        assertNotNull("известный тайтл не нашёлся", score)
        assertTrue("голосов подозрительно мало: ${score!!.second}", score.second >= 200)
    }

    @Test
    fun `несуществующее название возвращает null, а не падает`() = runBlocking {
        val score = timings.communityScore("Ъфыва несуществующее аниме 12345", "")
        assertTrue("для мусора должен быть null, пришло $score", score == null)
    }
}

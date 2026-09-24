package com.aniblaze.desktop.ui

import com.aniblaze.aggregator.model.Anime
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/**
 * Ранг постера считается ПО СВОЕЙ ШКАЛЕ ДЛЯ КАЖДОГО ИСТОЧНИКА.
 *
 * Тест сторожит ровно ту поломку, ради которой всё это переписано: пороги были
 * только пятибалльные (легенда с 4.80), а «Кино» приходит из TMDB по десятибалльной,
 * поэтому легендой оказывался КАЖДЫЙ фильм — семёрка тоже больше 4.80.
 *
 * Числа в примерах настоящие: сняты с TMDB и из каталога Anixart.
 */
class TitleRankTest {

    private fun cinema(rating: Double, votes: Int = 0) =
        Anime(id = "tmdb:1", title = "", poster = "", rating = rating, ratingMax = 10.0, ratingVotes = votes)

    private fun anime(rating: Double, favorites: Int = 0) =
        Anime(id = "1", title = "", poster = "", rating = rating, favoritesCount = favorites)

    @Test
    fun `десятибалльная середина каталога это не легенда`() {
        // Медиана ленты «Кино» — 7.54. Именно такие тайтлы раньше поголовно
        // получали «Легенду», потому что 7.54 больше пятибалльного порога 4.80.
        listOf(6.8, 7.1, 7.54, 7.9).forEach { score ->
            assertNotEquals("оценка $score не может быть легендой", TitleRank.LEGEND, TitleRank.of(cinema(score, votes = 50_000)))
        }
    }

    @Test
    fun `легенда в кино требует и оценки и аудитории`() {
        // «Побег из Шоушенка»: 8.7 при 28 тысячах голосов.
        assertEquals(TitleRank.LEGEND, TitleRank.of(cinema(8.7, votes = 28_000)))
        // Та же оценка, но у полутора зрителей — это ещё не легенда.
        assertEquals(TitleRank.GREAT, TitleRank.of(cinema(8.3, votes = 40)))
        // Оценки нет вовсе — судить не по чему.
        assertEquals(TitleRank.UNKNOWN, TitleRank.of(cinema(0.0, votes = 90_000)))
    }

    @Test
    fun `десятибалльные ранги ложатся на замеренные четверти`() {
        // q1 = 6.69, медиана = 7.54, q3 = 8.20 (887 тайтлов из одиннадцати лент TMDB).
        assertEquals(TitleRank.GREAT, TitleRank.of(cinema(7.6, votes = 500)))
        assertEquals(TitleRank.OKAY, TitleRank.of(cinema(6.9, votes = 500)))
        assertEquals(TitleRank.AWFUL, TitleRank.of(cinema(5.4, votes = 500)))
    }

    @Test
    fun `пятибалльная шкала считается по-прежнему`() {
        // Границы каталога Anixart: q1 = 4.24, медиана = 4.64, q3 = 4.80.
        assertEquals(TitleRank.LEGEND, TitleRank.of(anime(4.85, favorites = 120_000)))
        assertEquals(TitleRank.GREAT, TitleRank.of(anime(4.70, favorites = 900)))
        assertEquals(TitleRank.OKAY, TitleRank.of(anime(4.40, favorites = 900)))
        assertEquals(TitleRank.AWFUL, TitleRank.of(anime(3.80, favorites = 900)))
    }

    @Test
    fun `оценка выше своей шкалы означает что шкала соврала`() {
        // 8.6 по пятибалльной невозможно: значит источник о шкале не сказал правды,
        // и любой ранг тут был бы выдуманным.
        assertEquals(TitleRank.UNKNOWN, TitleRank.of(anime(8.6, favorites = 50_000)))
    }

    @Test fun `у Yummy своя шкала - медиана каталога это «норм», а не «ужас»`() {
        // Оценка Shikimori 7.0 при 100 тыс. просмотров: по TMDB-порогам — «Норм» на грани,
        // по Yummy-замеру (медиана 7.27, нижняя четверть 6.81) — тоже «Норм»; 6.5 — уже низ.
        fun yummy(grade: Double, views: Int = 100_000) = TitleRank.of(
            com.aniblaze.aggregator.model.Anime("ya:1", "t", "", rating = grade, ratingMax = 10.0, watchingCount = views),
        )
        assertEquals(TitleRank.OKAY, yummy(7.0))
        assertEquals(TitleRank.GREAT, yummy(7.4))
        assertEquals(TitleRank.AWFUL, yummy(6.5))
        assertEquals(TitleRank.LEGEND, yummy(7.9, views = 500_000))
        assertEquals("легенда требует аудитории", TitleRank.GREAT, yummy(7.9, views = 1_000))
        // Тот же тайтл под id TMDB-шкалы судится строже — шкалы не смешиваются.
        assertEquals(TitleRank.AWFUL, TitleRank.of(com.aniblaze.aggregator.model.Anime("tmdb:1", "t", "", rating = 6.5, ratingMax = 10.0)))
    }
}

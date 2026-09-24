package com.aniblaze.desktop

import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Личные оценки и их вес в подборе.
 *
 * Главное требование, которое здесь сторожится: ОЦЕНКА ПЕРЕВЕШИВАЕТ ПОВЕДЕНИЕ. Всё
 * остальное во вкусе — догадки по следам просмотра, и они не вправе спорить с тем, что
 * человек сказал прямо. Досмотрел и поставил двойку — это «не понравилось».
 */
class RatingTasteTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun card(id: String, genres: String, studio: String = "", year: Int = 2020) =
        PersistedAnime(id = id, title = id, poster = "", genres = genres, studio = studio, year = year)

    private fun rate(state: PersistedState, id: String, genres: String, score: Int, at: Long = now) =
        applyRating(state, card(id, genres), score, at)

    private fun taste(state: PersistedState) = Recommender.buildTaste(state, now)

    // ---- хранение -------------------------------------------------------------

    @Test
    fun `оценка сохраняется вместе с карточкой`() {
        val after = rate(PersistedState(), "a", "экшен", 5)
        assertEquals(1, after.ratings.size)
        assertEquals(5, after.ratings.first().score)
        // Без карточки оценка была бы числом, которое рекомендациям не видно.
        assertEquals("экшен", after.ratings.first().anime.genres)
    }

    @Test
    fun `повторная оценка заменяет прежнюю, а не добавляет вторую`() {
        val once = rate(PersistedState(), "a", "экшен", 5)
        val twice = rate(once, "a", "экшен", 2)
        assertEquals(1, twice.ratings.size)
        assertEquals(2, twice.ratings.first().score)
    }

    @Test
    fun `оценка вне шкалы снимает её`() {
        val rated = rate(PersistedState(), "a", "экшен", 4)
        assertTrue(rate(rated, "a", "экшен", 0).ratings.isEmpty())
        assertTrue(rate(rated, "a", "экшен", 9).ratings.isEmpty())
    }

    @Test
    fun `снятие оценки не стирает факт просмотра`() {
        // «Передумал ставить оценку» и «я это не смотрел» — разные заявления.
        val watched = markTitleWatched(PersistedState(), card("a", "экшен"), watched = true)
        val rated = rate(watched, "a", "экшен", 5)
        val cleared = rate(rated, "a", "экшен", 0)
        assertTrue(cleared.ratings.isEmpty())
        assertTrue(buildWatchIndex(cleared)["a"]!!.finished)
        assertEquals(listOf("a"), cleared.history.map { it.id })
    }

    @Test
    fun `свежая оценка ложится сверху`() {
        val first = rate(PersistedState(), "a", "экшен", 5, at = now - 10 * day)
        val second = rate(first, "b", "драма", 4, at = now)
        assertEquals(listOf("b", "a"), second.ratings.map { it.anime.id })
    }

    // ---- вес оценки ------------------------------------------------------------

    @Test
    fun `оценённый тайтл влияет на вкус сам по себе`() {
        val t = taste(rate(PersistedState(), "a", "экшен, фэнтези", 5))
        assertEquals(1, t.samples)
        assertEquals(1, t.ratedSamples)
        assertTrue(t.genres.containsKey("экшен"))
    }

    @Test
    fun `пятёрка перевешивает избранное с полным просмотром`() {
        // Одна осознанная оценка должна быть тяжелее двух косвенных следов, иначе
        // «рекомендуй по моим оценкам» — просто слова.
        val state = PersistedState(
            favorites = listOf(card("fav", "драма")),
            watched = setOf("fav#1", "fav#2"),
            episodeCounts = mapOf("fav" to 2),
        ).let { rate(it, "top", "экшен", 5) }
        val t = taste(state)
        assertTrue(
            "жанр оценённого (${t.genres["экшен"]}) должен весить больше жанра избранного (${t.genres["драма"]})",
            (t.genres["экшен"] ?: 0.0) > (t.genres["драма"] ?: 0.0),
        )
    }

    @Test
    fun `низкая оценка уводит жанр в антивкус`() {
        val t = taste(rate(PersistedState(), "a", "гарем", 1))
        assertTrue("жанр единицы не попал в антивкус", t.dislikedGenres.containsKey("гарем"))
        assertFalse("жанр единицы попал в любимые", t.genres.containsKey("гарем"))
    }

    @Test
    fun `двойка за досмотренное перебивает досмотренность`() {
        // Ровно тот случай, ради которого оценка ЗАМЕЩАЕТ поведение, а не складывается
        // с ним: досмотрел до конца, но не понравилось.
        val watched = markTitleWatched(PersistedState(episodeCounts = mapOf("a" to 12)), card("a", "гарем"), true)
        assertTrue("до оценки жанр должен быть любимым", taste(watched).genres.containsKey("гарем"))

        val t = taste(rate(watched, "a", "гарем", 2))
        assertTrue("после двойки жанр обязан уйти в антивкус", t.dislikedGenres.containsKey("гарем"))
        assertFalse(t.genres.containsKey("гарем"))
    }

    @Test
    fun `хорошая оценка снимает штраф за брошенное`() {
        // Бросил на второй серии, но поставил пятёрку — значит понравилось, а бросил
        // по другой причине. Штраф «брошено» тут неуместен.
        val abandoned = PersistedState(
            progress = listOf(
                ProgressEntry(card("a", "спорт"), segment = 1, positionMs = 60_000, durationMs = 1_400_000, updatedAt = now - 30 * day),
            ),
            episodeCounts = mapOf("a" to 24),
        )
        assertTrue("давняя пауза без оценки должна быть нейтральной", taste(abandoned).isEmpty)

        val t = taste(rate(abandoned, "a", "спорт", 5))
        assertTrue(t.genres.containsKey("спорт"))
        assertFalse(t.dislikedGenres.containsKey("спорт"))
    }

    @Test
    fun `тройка почти ничего не значит рядом с пятёркой`() {
        // Веса сравнимы только внутри ОДНОГО вкуса: карты нормируются на свой максимум,
        // и у одинокой оценки он всегда единица, какой бы она ни была.
        val state = rate(rate(PersistedState(), "top", "детектив", 5), "meh", "драма", 3)
        val t = taste(state)
        val strong = t.genres["детектив"] ?: 0.0
        val weak = t.genres["драма"] ?: 0.0
        assertTrue("тройка ($weak) должна быть много слабее пятёрки ($strong)", weak < strong * 0.25)
    }

    @Test
    fun `старая оценка весит меньше свежей`() {
        val state = rate(
            rate(PersistedState(), "old", "меха", 5, at = now - 500 * day),
            "fresh", "детектив", 5, at = now,
        )
        val t = taste(state)
        val recent = t.genres["детектив"] ?: 0.0
        val stale = t.genres["меха"] ?: 0.0
        assertTrue("свежая ($recent) должна весить больше старой ($stale)", stale < recent)
    }

    // ---- доверие ----------------------------------------------------------------

    @Test
    fun `оценки поднимают доверие быстрее пассивных следов`() {
        // Доверие входит множителем во все личные признаки score: пока оно низкое,
        // выдача остаётся «лучшим в каталоге», а не «лучшим из твоего».
        var rated = PersistedState()
        repeat(3) { i -> rated = rate(rated, "r$i", "экшен", 5) }
        val passive = PersistedState(favorites = (0 until 3).map { card("f$it", "экшен") })

        assertTrue(
            "три оценки (${taste(rated).confidence}) должны давать больше доверия, " +
                "чем три избранных (${taste(passive).confidence})",
            taste(rated).confidence > taste(passive).confidence,
        )
        assertTrue("три оценки должны давать хотя бы половину доверия", taste(rated).confidence >= 0.5)
    }

    @Test
    fun `оценённое не предлагается снова`() {
        assertTrue("a" in taste(rate(PersistedState(), "a", "экшен", 5)).seenIds)
        // Даже единица: не понравилось — тем более не предлагать.
        assertTrue("b" in taste(rate(PersistedState(), "b", "гарем", 1)).seenIds)
    }

    // ---- выдача ------------------------------------------------------------------

    @Test
    fun `кандидаты с любимым жанром идут выше`() {
        var state = PersistedState()
        repeat(3) { i -> state = rate(state, "r$i", "детектив", 5) }
        val t = taste(state)
        val pool = listOf(
            com.aniblaze.aggregator.model.Anime(id = "x", title = "Мимо", poster = "", genres = "гарем", rating = 4.6, ratingMax = 5.0, ratingVotes = 5000),
            com.aniblaze.aggregator.model.Anime(id = "y", title = "В точку", poster = "", genres = "детектив", rating = 4.0, ratingMax = 5.0, ratingVotes = 5000),
        )
        val out = Recommender.recommend(t, pool)
        assertEquals("тайтл любимого жанра должен быть первым", "y", out.first().anime.id)
    }

    @Test
    fun `запрос без потолка не падает по памяти`() {
        // ArrayList(limit) с Int.MAX_VALUE — падение на пустом месте; экран просит
        // «сколько найдётся» именно так.
        val t = taste(rate(PersistedState(), "a", "экшен", 5))
        // Названия РАЗНЫЕ по корню: выдача оставляет одного представителя франшизы, и
        // «Тайтл 1»…«Тайтл 5» схлопнулись бы в один — тест мерил бы не то.
        val names = listOf("Берсерк", "Ковбой Бибоп", "Триган", "Хеллсинг", "Волчица и пряности")
        val pool = names.mapIndexed { i, name ->
            com.aniblaze.aggregator.model.Anime(id = "p$i", title = name, poster = "", genres = "экшен")
        }
        assertEquals(5, Recommender.recommend(t, pool, limit = Int.MAX_VALUE).size)
    }
}

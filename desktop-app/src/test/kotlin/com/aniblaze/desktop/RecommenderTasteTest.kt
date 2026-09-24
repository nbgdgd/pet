package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Персональный движок: что именно выводится из следов просмотра и что попадает в выдачу.
 *
 * Состояния собираются руками из тех же полей, что пишет живое приложение
 * (история, прогресс, `watched` вида «id#серия», `episodeCounts`, избранное), а
 * тайтлы — настоящие: правила про «досмотрел» и «бросил» проверяются на том, как
 * каталог реально выглядит.
 */
class RecommenderTasteTest {

    private val now = 1_700_000_000_000L
    private fun daysAgo(days: Int): Long = now - days * 86_400_000L

    private fun card(
        id: String,
        title: String,
        genres: String = "",
        studio: String = "",
        year: Int = 0,
        description: String = "",
    ) = PersistedAnime(
        id = id, title = title, poster = "p", year = year, description = description,
        genres = genres, studio = studio,
    )

    private fun anime(
        id: String,
        title: String,
        genres: String = "",
        studio: String = "",
        year: Int = 0,
        rating: Double = 0.0,
        favorites: Int = 0,
        description: String = "",
    ) = Anime(
        id = id, title = title, poster = "p", description = description, rating = rating,
        genres = genres, year = year, favoritesCount = favorites, studio = studio,
    )

    /** Все серии тайтла отмечены досмотренными. */
    private fun watchedAll(id: String, episodes: Int): Set<String> =
        (1..episodes).map { "$id#$it" }.toSet()

    // --- что даёт история -------------------------------------------------------

    @Test
    fun `без следов просмотра вкуса нет`() {
        val taste = Recommender.buildTaste(PersistedState(), now)
        assertTrue(taste.isEmpty)
        assertEquals(0, taste.samples)
        assertTrue(taste.genres.isEmpty())
    }

    @Test
    fun `досмотренное влияет а давняя пауза нейтральна`() {
        val jujutsu = card("ax:1", "Магическая битва", genres = "экшен, сверхъестественное")
            .copy(airingStatus = 1, episodesTotal = 24)
        val gate = card("ax:2", "Врата: Там бьются наши воины", genres = "военное, фэнтези")
        val state = PersistedState(
            history = listOf(jujutsu, gate),
            progress = listOf(
                // Брошено на первой серии из двадцати четырёх и не открывалось два месяца.
                ProgressEntry(gate, segment = 1, positionMs = 300_000, durationMs = 1_400_000, updatedAt = daysAgo(60)),
            ),
            watched = watchedAll("ax:1", 24) + "ax:2#1",
            episodeCounts = mapOf("ax:1" to 24, "ax:2" to 24),
        )
        val taste = Recommender.buildTaste(state, now)

        assertTrue(taste.genres.containsKey("экшен"), "досмотренное не попало во вкус: ${taste.genres}")
        assertTrue(
            taste.dislikedGenres.isEmpty(),
            "давняя пауза не доказывает неприязнь: ${taste.dislikedGenres}",
        )
        assertFalse(taste.genres.containsKey("военное"), "жанр брошенного не может быть и любимым")

        val action = anime("ax:100", "Ванпанчмен", genres = "экшен", rating = 4.5, favorites = 50_000)
        val war = anime("ax:101", "Восемьдесят шесть", genres = "военное", rating = 4.5, favorites = 50_000)
        assertTrue(
            Recommender.score(taste, action) > Recommender.score(taste, war),
            "жанр брошенного должен уступать жанру досмотренного",
        )
    }

    @Test
    fun `начатое вчера ещё не брошено`() {
        val gate = card("ax:2", "Врата: Там бьются наши воины", genres = "военное, фэнтези")
        val state = PersistedState(
            history = listOf(gate),
            progress = listOf(
                ProgressEntry(gate, segment = 1, positionMs = 300_000, durationMs = 1_400_000, updatedAt = daysAgo(1)),
            ),
            watched = setOf("ax:2#1"),
            episodeCounts = mapOf("ax:2" to 24),
        )
        val taste = Recommender.buildTaste(state, now)
        assertTrue(
            taste.dislikedGenres.isEmpty(),
            "вчерашний просмотр записан в «не понравилось»: ${taste.dislikedGenres}",
        )
        assertTrue(taste.genres.isEmpty())
    }

    @Test
    fun `избранное весит больше простого захода на страницу`() {
        val favorite = card("ax:1", "Стальной алхимик: Братство", genres = "приключения")
        val visited = card("ax:2", "Блич", genres = "экшен")
        val state = PersistedState(favorites = listOf(favorite), history = listOf(favorite, visited))
        val taste = Recommender.buildTaste(state, now)
        assertTrue(
            (taste.genres["приключения"] ?: 0.0) > (taste.genres["экшен"] ?: 0.0),
            "избранное должно перевешивать: ${taste.genres}",
        )
    }

    // --- выдача -----------------------------------------------------------------

    private fun tasteOf(vararg cards: PersistedAnime): Recommender.Taste =
        Recommender.buildTaste(
            PersistedState(favorites = cards.toList(), history = cards.toList()),
            now,
        )

    @Test
    fun `просмотренное и его продолжения в рекомендации не возвращаются`() {
        val taste = tasteOf(card("ax:1", "Атака титанов", genres = "экшен, драма"))
        val pool = listOf(
            anime("ax:1", "Атака титанов", genres = "экшен, драма", rating = 4.9, favorites = 300_000),
            // Тот же тайтл с другого источника — другой id, то же название.
            anime("libria:aot", "Атака титанов", genres = "экшен, драма", rating = 4.9, favorites = 1_000),
            anime("ax:2", "Атака титанов 3-й сезон", genres = "экшен, драма", rating = 4.9, favorites = 200_000),
            anime("ax:3", "Атака титанов: Финал. Часть 2", genres = "экшен, драма", rating = 4.9),
            anime("ax:9", "Ванпанчмен", genres = "экшен, комедия", rating = 4.6, favorites = 90_000),
        )
        val out = Recommender.recommend(taste, pool)
        assertEquals(listOf("ax:9"), out.map { it.anime.id }, "в выдаче осталось уже просмотренное: $out")
    }

    @Test
    fun `одна франшиза занимает одно место`() {
        val taste = tasteOf(card("ax:1", "Ванпанчмен", genres = "экшен, комедия"))
        val pool = listOf(
            anime("ax:10", "Магическая битва", genres = "экшен", rating = 4.7, favorites = 120_000),
            anime("ax:11", "Магическая битва 2", genres = "экшен", rating = 4.8, favorites = 130_000),
            anime("ax:12", "Магическая битва 0", genres = "экшен", rating = 4.6, favorites = 90_000),
            anime("ax:20", "Клинок, рассекающий демонов", genres = "экшен", rating = 4.8, favorites = 200_000),
            anime("ax:21", "Клинок, рассекающий демонов: Квартал красных фонарей", genres = "экшен", rating = 4.8),
        )
        val out = Recommender.recommend(taste, pool)
        assertEquals(2, out.size, "франшизы не схлопнулись до одной карточки: ${out.map { it.anime.title }}")
        assertTrue(out.any { it.anime.title.startsWith("Магическая битва") })
        assertTrue(out.any { it.anime.title.startsWith("Клинок") })
    }

    @Test
    fun `в подборке есть доля новых направлений`() {
        val watchedAction = listOf(
            card("ax:1", "Атака титанов", genres = "экшен"),
            card("ax:2", "Ванпанчмен", genres = "экшен"),
            card("ax:3", "Блич", genres = "экшен"),
        )
        val taste = Recommender.buildTaste(
            PersistedState(favorites = watchedAction, history = watchedAction),
            now,
        )
        val action = listOf(
            "Хантер х Хантер", "Ван-Пис", "Чёрный клевер", "Токийский гуль",
            "Мастера меча онлайн", "Моя геройская академия", "Стальной алхимик", "Клинок ведьм",
        ).mapIndexed { i, t -> anime("act:$i", t, genres = "экшен", rating = 4.5, favorites = 50_000) }
        val calm = listOf("Баракамон", "Тетрадь дружбы Нацумэ", "К-Он!", "Усаги Дроп")
            .mapIndexed { i, t -> anime("calm:$i", t, genres = "повседневность", rating = 4.4, favorites = 30_000) }

        val out = Recommender.recommend(taste, action + calm, limit = 12)
        assertTrue(out.any { it.exploratory }, "выдача схлопнулась в один жанр: ${out.map { it.anime.title }}")
        assertTrue(
            out.count { it.exploratory } >= 2,
            "новых направлений должно быть заметно, а не одно на всю ленту: ${out.filter { it.exploratory }}",
        )
        // Но большинство всё-таки под вкус — это рекомендации, а не рулетка.
        assertTrue(out.count { !it.exploratory } > out.count { it.exploratory })
    }

    @Test
    fun `чем больше данных тем сильнее личные признаки`() {
        // Один и тот же выбор при двух объёмах истории. Кандидаты подобраны так, что
        // ответ ОБЯЗАН смениться: «по вкусу, но средний» против «мимо вкуса, но лучшее
        // в каталоге». На двух тайтлах вкус — ещё совпадение, и решать должно качество.
        val sport = anime("ax:50", "Гигант шагает", genres = "спорт", rating = 3.5, favorites = 300)
        val acclaimed = anime("ax:51", "Твоё имя", genres = "драма", rating = 4.9, favorites = 300_000)

        val fewSamples = (1..2).map { card("s:$it", "Тайтл про спорт $it", genres = "спорт") }
        val manySamples = (1..30).map { card("s:$it", "Тайтл про спорт $it", genres = "спорт") }

        val thin = Recommender.buildTaste(PersistedState(favorites = fewSamples, history = fewSamples), now)
        val rich = Recommender.buildTaste(PersistedState(favorites = manySamples, history = manySamples), now)
        assertTrue(rich.confidence > thin.confidence)

        assertTrue(
            Recommender.score(thin, acclaimed) > Recommender.score(thin, sport),
            "на пустой истории решать должно качество каталога",
        )
        assertTrue(
            Recommender.score(rich, sport) > Recommender.score(rich, acclaimed),
            "на полной истории решать должен вкус",
        )
    }

    @Test
    fun `у каждой карточки есть человеческая причина`() {
        val taste = tasteOf(card("ax:1", "Магическая битва", genres = "экшен, сверхъестественное", studio = "MAPPA"))
        val pool = listOf(
            anime("ax:2", "Дороро", genres = "экшен, сверхъестественное", studio = "MAPPA", rating = 4.5, favorites = 40_000),
            anime("ax:3", "Баракамон", genres = "повседневность", rating = 4.4, favorites = 20_000),
        )
        val out = Recommender.recommend(taste, pool)
        assertTrue(out.all { it.reason.isNotBlank() }, "нашлась карточка без объяснения: $out")
        // One favourite gives little confidence: audience quality outweighs the
        // studio match. Exploration is a selection flag, not fabricated evidence.
        assertEquals(Recommender.ReasonFactor.QUALITY, out.first { it.anime.id == "ax:2" }.explanation?.primaryReason)
        assertTrue(out.first { it.anime.id == "ax:3" }.exploratory)
        for (rec in out) {
            val terms = Recommender.scoreContributions(taste, rec.anime)
            assertEquals(terms.maxBy { it.value }.key, rec.explanation?.primaryReason)
        }
    }
}

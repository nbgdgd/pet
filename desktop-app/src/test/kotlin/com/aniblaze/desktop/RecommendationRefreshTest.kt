package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import java.nio.file.Files
import kotlin.test.*

class RecommendationRefreshTest {
    private val now = 1_788_364_800_000L
    private fun card(id: String = "ax:1", title: String = "Любимый сериал", genre: String = "спорт") =
        PersistedAnime(id, title, "", genres = genre, year = 2026)
    private fun candidate(id: String, title: String, genre: String) =
        Anime(id, title, "", genres = genre, rating = 4.5, ratingVotes = 10_000, year = 2026)

    @Test fun `cold start is a deterministic quality selection not an empty or random list`() {
        val taste = Recommender.buildTaste(PersistedState(), now)
        val good = candidate("ax:10", "Хороший сериал", "спорт")
        val weak = candidate("ax:11", "Слабый сериал", "спорт").copy(rating = 2.0)
        val picks = Recommender.recommend(taste, listOf(weak, good))
        assertTrue(picks.isNotEmpty())
        assertEquals(good.id, picks.first().anime.id)
        assertEquals(picks, Recommender.recommend(taste, listOf(weak, good)))
    }
    @Test fun `one to three first favourites personalize the same pool`() {
        val sport = candidate("ax:10", "Спортивный сериал", "спорт")
        val horror = candidate("ax:11", "Страшный сериал", "ужасы")
        for (count in 1..3) {
            val taste = Recommender.buildTaste(PersistedState(favorites = (1..count).map { card("f:$it", "Клуб $it") }), now)
            assertTrue(Recommender.score(taste, sport) > Recommender.score(taste, horror))
        }
    }
    @Test fun `cinema history cannot contaminate anime taste or candidates`() {
        val taste = Recommender.buildTaste(PersistedState(favorites = listOf(card("tmdb:1", genre = "ужасы"), card())), now)
        assertFalse("ужасы" in taste.genres)
        assertTrue(Recommender.recommend(taste, listOf(candidate("tmdbtv:22", "Кино", "спорт"))).isEmpty())
    }
    @Test fun `cross-source duplicates are one taste sample and one recommendation`() {
        val taste = Recommender.buildTaste(PersistedState(favorites = listOf(card(), card("alias"))), now)
        assertEquals(1, taste.samples)
        val a = candidate("ax:10", "Другой сериал", "спорт")
        assertEquals(1, Recommender.recommend(taste, listOf(a, a.copy(id = "alias-2"))).size)
    }
    @Test fun `completed title is excluded even after its history card is removed`() {
        val taste = Recommender.buildTaste(PersistedState(watched = setOf("ax:10#1")), now)
        assertTrue(Recommender.recommend(taste, listOf(candidate("ax:10", "Просмотренный", "спорт"))).isEmpty())
    }
    @Test fun `removing favourite is a weak negative and readding clears it persistently`() {
        val file = Files.createTempDirectory("aniblaze-favourites-test").resolve("state.json").toFile()
        val settings = AppSettings(file)
        val title = card().toAnime()
        settings.toggleFavorite(title)
        settings.toggleFavorite(title)
        settings.flush()
        val restarted = AppSettings(file)
        assertEquals(1, restarted.state.value.favoriteRemovals.size)
        val taste = Recommender.buildTaste(restarted.state.value)
        assertTrue("спорт" in taste.dislikedGenres)
        restarted.toggleFavorite(title)
        assertTrue(restarted.state.value.favoriteRemovals.isEmpty())
        restarted.flush()
    }
    @Test fun `low rating overrides completion and favourite and penalizes themes too`() {
        val bad = card(genre = "фантастика").copy(description = "Космос, звездолёт, галактика")
        val state = PersistedState(favorites = listOf(bad), watched = (1..12).map { "ax:1#$it" }.toSet(),
            episodeCounts = mapOf("ax:1" to 12), ratings = listOf(RatedTitle(bad, 1, now)))
        val taste = Recommender.buildTaste(state, now)
        assertTrue(taste.genres.isEmpty())
        assertTrue(taste.dislikedThemes.isNotEmpty())
    }
    @Test fun `new completion survives disappearing progress as a recent positive signal`() {
        val state = PersistedState(history = listOf(card().copy(airingStatus = 1, episodesTotal = 12)), watched = (1..12).map { "ax:1#$it" }.toSet(),
            watchedAt = mapOf("ax:1#12" to now), episodeCounts = mapOf("ax:1" to 12))
        val taste = Recommender.buildTaste(state, now)
        assertTrue("спорт" in taste.genres)
        assertTrue(taste.dislikedGenres.isEmpty())
    }
    @Test fun `ratings favourites history deletion and completion invalidate profile input`() {
        val original = PersistedState(history = listOf(card()))
        val before = recommendationInput(original)
        val changed = listOf(original.copy(favorites = listOf(card())),
            original.copy(history = emptyList()), original.copy(ratings = listOf(RatedTitle(card(), 5, now))),
            original.copy(watched = setOf("ax:1#1")), original.copy(episodeCounts = mapOf("ax:1" to 12)))
        changed.forEach { assertNotEquals(before, recommendationInput(it)) }
    }
    @Test fun `two second progress saves and unrelated settings do not rerank`() {
        val progress = ProgressEntry(card(), 1, 400_000, 1_400_000, now)
        val state = PersistedState(progress = listOf(progress))
        val later = state.copy(progress = listOf(progress.copy(positionMs = 402_000, updatedAt = now + 2000)), chatSide = "left")
        assertEquals(recommendationInput(state), recommendationInput(later))
        assertNotEquals(recommendationInput(state), recommendationInput(state.copy(progress = listOf(progress.copy(segment = 2)))))
    }
    @Test fun `genre window and franchise diversification hold with a varied pool`() {
        val state = PersistedState(favorites = listOf(card()))
        val pool = (0 until 36).map { i ->
            candidate("candidate:$i", "История ${('А'.code + i).toChar()} приключение", if (i < 18) "спорт" else if (i < 27) "драма" else "комедия")
        } + listOf(candidate("ax:100", "Клинок чудес", "спорт"), candidate("ax:101", "Клинок чудес ARAGOTO", "спорт"))
        val out = Recommender.recommend(Recommender.buildTaste(state, now), pool, 24)
        assertEquals(24, out.size)
        assertTrue(out.take(12).count { it.anime.genres == "спорт" } < 9)
        assertTrue(out.count { it.anime.title.startsWith("Клинок чудес") } <= 1)
        assertTrue(out.any { it.exploratory })
    }
    @Test fun `large history keeps results unique and excludes old titles`() {
        val history = (1..1000).map { card("old:$it", "Просмотренный $it", if (it % 2 == 0) "спорт" else "драма") }
        val pool = history.take(200).map { it.toAnime() } +
            (1..100).map { candidate("new:$it", "Уникальная история ${('А'.code + it).toChar()} новая", "комедия") }
        val out = Recommender.recommend(Recommender.buildTaste(PersistedState(history = history), now), pool)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.anime.id.startsWith("new:") })
        assertEquals(out.size, out.map { it.anime.id }.toSet().size)
    }
}

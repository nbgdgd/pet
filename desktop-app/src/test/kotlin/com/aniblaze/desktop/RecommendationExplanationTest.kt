package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.Recommender.ReasonFactor
import com.aniblaze.desktop.Recommender.HistorySignal
import kotlin.test.*

class RecommendationExplanationTest {
    private val now = 1_788_364_800_000L
    private fun source(id: String = "ax:1", title: String = "Исходный тайтл") =
        PersistedAnime(id, title, "https://images.test/$id.jpg", genres = "спорт", studio = "Bones", year = 2025)
    private val candidate = Anime("ax:20", "Другая история", "https://images.test/candidate.jpg",
        genres = "спорт", studio = "Bones", year = 2025, rating = 4.5, ratingVotes = 10000)
    private fun taste() = Recommender.buildTaste(PersistedState(ratings = (1..10).map {
        RatedTitle(source("ax:$it", "Основа ${('А'.code + it).toChar()}"), 5, now)
    }), now)

    @Test fun `reason is the strongest actual score term even when studio matches`() {
        val rec = Recommender.recommend(taste(), listOf(candidate)).single()
        val detail = assertNotNull(rec.explanation)
        assertEquals(detail.contributions.values.sum(), rec.score, 1e-10)
        assertEquals(detail.contributions.maxBy { it.value }.key, detail.primaryReason)
        assertEquals(ReasonFactor.GENRES, detail.primaryReason)
        assertEquals(rec.reason, detail.text)
        assertEquals(listOf("спорт"), detail.matchedGenres)
        assertEquals("Bones", detail.studioMatch)
        assertFalse(rec.reason.startsWith("Студия"))
    }
    @Test fun `source posters use scored title ids and are not the candidate poster`() {
        val detail = Recommender.explain(taste(), candidate)
        assertEquals(3, detail.sourceAnime.size)
        for (s in detail.sourceAnime) {
            assertEquals("https://images.test/${s.anime.id}.jpg", s.anime.poster)
            assertNotEquals(candidate.poster, s.anime.poster)
            assertEquals(HistorySignal.HIGH_RATING, s.signal)
        }
        assertEquals(detail.sourceAnime.size, detail.sourceAnime.map { it.anime.id }.toSet().size)
    }
    @Test fun `quality wins for cold start without invented personal basis`() {
        val detail = Recommender.explain(Recommender.buildTaste(PersistedState(), now), candidate)
        assertEquals(ReasonFactor.QUALITY, detail.primaryReason)
        assertTrue(detail.sourceAnime.isEmpty())
        assertFalse(detail.text.contains("смотрели"))
    }
    @Test fun `similarity reason distinguishes actual watched from merely opened`() {
        val s = source().copy(genres = "", studio = "", year = 0, description = "Волшебная магия",
            airingStatus = 1, episodesTotal = 1)
        val watched = Recommender.buildTaste(PersistedState(history = listOf(s), watched = setOf("ax:1#1")), now)
        val a = candidate.copy(genres = "", studio = "", year = 0, rating = 0.0, description = s.description)
        // Isolate nearest-anchor factor using a valid taste with no aggregate terms.
        val w = watched.copy(themes = emptyMap(), genres = emptyMap(), studios = emptyMap(), preferredYear = 0)
        val detail = Recommender.explain(w, a)
        assertEquals(ReasonFactor.SIMILARITY, detail.primaryReason)
        assertTrue(detail.text.startsWith("Потому что вы смотрели"))
        assertEquals("ax:1", detail.sourceAnime.single().anime.id)
        val visited = Recommender.buildTaste(PersistedState(history = listOf(s)), now)
            .copy(themes = emptyMap(), genres = emptyMap(), studios = emptyMap(), preferredYear = 0)
        assertFalse(Recommender.explain(visited, a).text.contains("смотрели"))
        assertTrue(Recommender.explain(visited, a).sourceAnime.isEmpty())
    }
    @Test fun `studio explanation only claims high ratings when supporting ratings exist`() {
        val detail = Recommender.explain(taste(), candidate.copy(genres = "", year = 0, rating = 0.0))
        assertEquals(ReasonFactor.STUDIO, detail.primaryReason)
        assertTrue(detail.text.contains("высоко оценили"))
        val favorite = Recommender.buildTaste(PersistedState(favorites = listOf(source())), now)
        val other = Recommender.explain(favorite, candidate.copy(genres = "", year = 0, rating = 0.0))
        assertFalse(other.text.contains("высоко оценили"))
    }
    @Test fun `negative genres cannot become a positive primary explanation`() {
        val t = taste().copy(dislikedGenres = mapOf("спорт" to 5.0))
        val detail = Recommender.explain(t, candidate)
        assertTrue(detail.contributions.getValue(ReasonFactor.GENRES) < 0)
        assertNotEquals(ReasonFactor.GENRES, detail.primaryReason)
    }
    @Test fun `explanations are deterministic pure results with no duplicate franchises`() {
        val cards = listOf(candidate, candidate.copy(id = "alias"), candidate.copy(id = "ax:21", title = "Другая история 2"))
        val first = Recommender.recommend(taste(), cards)
        assertEquals(1, first.size)
        repeat(50) { assertEquals(first, Recommender.recommend(taste(), cards)) }
    }
}

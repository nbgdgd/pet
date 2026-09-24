package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.Recommender.ReasonFactor
import kotlinx.serialization.json.Json
import kotlin.test.*

class HybridRecommendationTest {
    private val now = 1_788_364_800_000L
    private fun card(id: String = "source") = PersistedAnime(id, "История $id", "poster-$id",
        genres = "фэнтези", description = "Герои отправляются в путешествие исследовать мир после утраты близких друзей.",
        episodesTotal = 12, episodesAvailable = 12, airingStatus = 1)
    private fun state(count: Int, a: PersistedAnime = card()) = PersistedState(history = listOf(a),
        watched = (1..count).map { "${a.id}#$it" }.toSet(), episodeCounts = mapOf(a.id to count))
    private fun taste(state: PersistedState) = Recommender.buildTaste(state, now)
    private fun rated(count: Int, score: Int = 5) = PersistedState(ratings = (1..count).map {
        RatedTitle(card("base${('А'.code + it).toChar()}"), score, now)
    })
    private val reflective = SemanticProfile(themes = listOf("утрата"), tone = listOf("меланхолия"),
        storyFocus = listOf("исследование мира"), characterDynamics = listOf("дружба"), narrativeStyle = listOf("путешествие"))
    private val comedy = SemanticProfile(themes = listOf("школьная жизнь"), tone = listOf("комедия"),
        storyFocus = listOf("романтика"), characterDynamics = listOf("соперничество"))
    private val context = Anime("candidate-context", "Звёздный странник", "c", genres = "фантастика",
        description = "Странник исследует космос и пытается справиться с утратой близких друзей.")
    private val sameGenre = Anime("candidate-genre", "Весёлая академия", "c", genres = "фэнтези",
        description = "Забавная комедия про студентов магической школы и их любовные приключения.")

    @Test fun `partial history 1 2 6 11 episodes and unknown totals never positive`() {
        for (n in listOf(1, 2, 6, 11)) {
            val t = taste(state(n))
            assertTrue(t.isEmpty, "partial=$n")
            assertTrue(t.sources.isEmpty())
            assertTrue(t.genres.isEmpty())
        }
        assertTrue(taste(state(12, card().copy(airingStatus = 0))).isEmpty)
        assertTrue(taste(state(12, card().copy(episodesTotal = 0))).isEmpty)
    }
    @Test fun `all final main episodes required and specials not mandatory`() {
        assertEquals(RecommendationCompletion.COMPLETED, recommendationCompletion(state(12), card()))
        assertFalse(taste(state(12)).isEmpty)
        assertEquals(Recommender.HistorySignal.WATCHED, taste(state(12)).sources.single().signal)
        // Highest completed alone is not a complete season; an OVA with another ID is irrelevant.
        assertTrue(taste(state(12).copy(watched = setOf("source#12", "ova#1"))).isEmpty)
        assertFalse(taste(state(12).copy(watched = state(12).watched + "source#13")).isEmpty)
    }
    @Test fun `caught up ongoing stays neutral even at planned count`() {
        for (available in listOf(2, 10, 12)) {
            val a = card().copy(airingStatus = 2, episodesAvailable = available)
            val s = state(available, a)
            assertEquals(RecommendationCompletion.CAUGHT_UP, recommendationCompletion(s, a))
            assertTrue(taste(s).isEmpty)
            assertTrue(taste(markTitleWatched(s, a, true, now)).isEmpty)
        }
    }
    @Test fun `completed 5 dominates history and completed 1 remains negative`() {
        val completed = state(12)
        val good = taste(completed.copy(ratings = listOf(RatedTitle(card(), 5, now))))
        val bad = taste(completed.copy(ratings = listOf(RatedTitle(card(), 1, now))))
        assertTrue(good.sources.single().weight > taste(completed).sources.single().weight)
        assertTrue(bad.sources.isEmpty())
        assertEquals(1, bad.negativeSources.single().rating)
        val terms = HybridRecommender.recommend(bad, listOf(sameGenre)).single().explanation!!.contributions
        assertEquals(0.0, terms[ReasonFactor.COMPLETED_HISTORY])
        assertTrue(terms.getValue(ReasonFactor.DISLIKED) < 0)
    }
    @Test fun `pauses never inferred as abandoned and favorite removal is explicit weak feedback`() {
        val paused = state(1).copy(progress = listOf(ProgressEntry(card(), 2, 60000, 1400000, now - 365L * 86400000)))
        assertTrue(taste(paused).dislikedGenres.isEmpty())
        assertTrue(taste(paused).isEmpty)
        val removed = taste(paused.copy(favoriteRemovals = listOf(FavoriteRemoval(card(), now))))
        assertTrue(removed.dislikedGenres.isNotEmpty())
        assertTrue(removed.negativeSources.isEmpty()) // Not an explicit 1-star rating or dropped status.
    }
    @Test fun `cold start uses old engine exactly and one rating has limited confidence`() {
        val pool = listOf(sameGenre, context)
        assertEquals(Recommender.recommend(taste(PersistedState()), pool),
            HybridRecommender.recommend(taste(PersistedState()), pool))
        val one = taste(rated(1)); val many = taste(rated(10))
        assertTrue(one.confidence < many.confidence)
        assertTrue(taste(rated(1, 3)).isEmpty, "A neutral 3 must not normalise into a strong genre preference")
        for (count in listOf(1, 5, 10, 100, 500)) {
            val results = HybridRecommender.recommend(taste(rated(count)), pool)
            assertEquals(2, results.size)
            assertTrue(results.all { it.score.isFinite() })
        }
    }
    @Test fun `different genres same context outranks same genre different tone`() {
        val t = taste(rated(10))
        val profiles = t.sources.associate { it.anime.id to reflective } +
            mapOf(context.id to reflective, sameGenre.id to comedy)
        val out = HybridRecommender.recommend(t, listOf(sameGenre, context), profiles)
        assertEquals(context.id, out.first().anime.id, out.joinToString { "${it.anime.title}: ${it.score}" })
        val reason = out.first().explanation!!
        assertTrue(reason.text.contains("высоко оценили"))
        assertTrue("меланхолия" in reason.matchedTags)
        assertEquals(3, reason.sourceAnime.size)
        assertTrue(reason.sourceAnime.all { it.anime.poster.startsWith("poster-base") })
        assertEquals(reason.contributions.values.sum(), out.first().score, 1e-9)
    }
    @Test fun `negative semantic neighbours reduce score even across different genres`() {
        val bad = taste(rated(8, 1))
        val profiles = bad.negativeSources.associate { it.anime.id to reflective } + mapOf(context.id to reflective)
        val cached = HybridRecommender.recommend(bad, listOf(context), profiles).single()
        val unrelated = HybridRecommender.recommend(bad, listOf(context), profiles + (context.id to comedy)).single()
        assertTrue(cached.score + 20 < unrelated.score)
        assertTrue(cached.explanation!!.sourceAnime.isEmpty())
    }
    @Test fun `partial episodes never appear in recommendation evidence`() {
        val s = rated(5).copy(history = listOf(card("partial")), watched = setOf("partial#1", "partial#2"))
        val t = taste(s)
        val out = HybridRecommender.recommend(t, listOf(sameGenre, context))
        assertTrue(out.flatMap { it.explanation!!.sourceAnime }.none { it.anime.id == "partial" })
        assertTrue(t.sources.none { it.signal in setOf(Recommender.HistorySignal.STARTED, Recommender.HistorySignal.VISITED) })
    }
    @Test fun `metadata hash ignores score popularity and position but follows synopsis schema and id`() {
        val a = context
        assertEquals(AnimeSemantics.cacheKey(a), AnimeSemantics.cacheKey(a.copy(rating = 4.8, watchingCount = 5000,
            episodesAvailable = 9, airingStatus = 1)))
        assertNotEquals(AnimeSemantics.cacheKey(a), AnimeSemantics.cacheKey(a.copy(description = "Different story")))
        assertNotEquals(AnimeSemantics.cacheKey(a), AnimeSemantics.cacheKey(a.copy(id = "other")))
        assertTrue(AnimeSemantics.cacheKey(a).contains("|${AnimeSemantics.VERSION}|"))
        assertEquals(0.0, AnimeSemantics.similarity(SemanticProfile(), SemanticProfile()))
    }
    @Test fun `additive save migration preserves progress and allows explicit complete mark`() {
        val legacy = Json.decodeFromString<PersistedState>("""{"history":[{"id":"a","title":"A","poster":""}],"watched":["a#1"]}""")
        assertTrue(taste(legacy).isEmpty)
        val marked = markTitleWatched(legacy, legacy.history.single(), true, now)
        assertEquals(setOf("a"), marked.completedTitles)
        assertFalse(taste(marked).isEmpty)
        assertTrue(markTitleWatched(marked, legacy.history.single(), false).completedTitles.isEmpty())
        assertEquals(card().copy(ratingMax = 5.0), card().toAnime().toPersisted())
    }
    @Test fun `main season titles independent and large partial history contributes no affinity`() {
        val cards = (1..500).map { card("id$it") }
        val s = PersistedState(history = cards, watched = cards.flatMap { a -> (1..2).map { "${a.id}#$it" } }.toSet())
        assertTrue(taste(s).isEmpty)
        val complete = s.copy(watched = s.watched + (1..12).map { "id250#$it" })
        assertEquals(listOf("id250"), taste(complete).sources.map { it.anime.id })
    }
}

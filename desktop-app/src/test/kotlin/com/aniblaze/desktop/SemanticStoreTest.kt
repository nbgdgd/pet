package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class SemanticStoreTest {
    private val time = 1_788_364_800_000L
    private fun anime(id: String) = Anime(id, "История ${id.map { ('А'.code + it.code % 32).toChar() }.joinToString("")} мир", "p",
        description = "Исследование неизвестного мира, путешествие с друзьями и взросление героев.", genres = "приключения")
    private val source = anime("anchor")
    private val state = PersistedState(ratings = listOf(RatedTitle(source.toPersisted(), 5, time)))
    private fun dir() = Files.createTempDirectory("semantic-cache-test").toFile()
    private class Fake : SemanticExtractor {
        val requested = mutableListOf<String>()
        var calls = 0
        override suspend fun extract(titles: List<Anime>): List<SemanticProfile> {
            calls++; requested += titles.map { it.id }
            return titles.map { SemanticProfile(storyFocus = listOf("исследование мира"), tone = listOf("надежда")) }
        }
    }
    @Test fun `empty cache batches only anchors and shortlist then zero requests on reopen restart`() = runBlocking {
        val directory = dir(); val api = Fake()
        val store = SemanticRecommendationStore(directory, api, { time })
        val pool = (1..250).map { anime("candidate$it") }
        store.rank(state, pool, online = false)
        assertEquals(0, api.calls)
        store.rank(state, pool, online = true)
        assertEquals(source.id, api.requested.first())
        val selected = HybridRecommender.candidates(Recommender.buildTaste(state, time), pool).map { it.anime.id }.toSet()
        assertTrue(api.requested.all { it == source.id || it in selected })
        assertTrue(api.requested.size <= 121)
        val calls = api.calls
        store.rank(state, pool, online = true)
        SemanticRecommendationStore(directory, api, { time }).rank(state, pool, online = true)
        assertEquals(calls, api.calls)
        assertEquals(api.requested.size, api.requested.distinct().size)
    }
    @Test fun `changed metadata invalidates only affected profile`() = runBlocking {
        val api = Fake(); val store = SemanticRecommendationStore(dir(), api, { time })
        val candidate = anime("candidate")
        store.rank(state, listOf(candidate), true)
        val before = api.calls
        store.rank(state, listOf(candidate.copy(description = candidate.description + " Психологическая драма.")), true)
        assertEquals(before + 1, api.calls)
        assertEquals(1, api.requested.count { it == source.id })
        assertEquals(2, api.requested.count { it == candidate.id })
    }
    @Test fun `schema version change invalidates profile`() = runBlocking {
        val directory = dir(); val api = Fake()
        val oldKey = AnimeSemantics.cacheKey(source).replace("|${AnimeSemantics.VERSION}|", "|0|")
        directory.resolve("semantic-profiles-v1.json").writeText(Json.encodeToString(SemanticCacheState(
            profiles = mapOf(oldKey to CachedSemantic(SemanticProfile(tone = listOf("test")), time)))))
        SemanticRecommendationStore(directory, api, { time }).rank(state, emptyList(), true)
        assertEquals(listOf(source.id), api.requested)
    }
    @Test fun `no api and api error return valid local ranking with persistent cooldown`() = runBlocking {
        val pool = listOf(anime("candidate"))
        assertEquals(1, SemanticRecommendationStore(dir()).rank(state, pool, true).items.size)
        val directory = dir(); var calls = 0
        val broken = SemanticExtractor { calls++; error("Simulated service unavailable") }
        repeat(3) {
            assertEquals(1, SemanticRecommendationStore(directory, broken, { time }).rank(state, pool, true).items.size)
        }
        assertEquals(1, calls)
        assertFalse(directory.resolve("semantic-profiles-v1.json").readText().contains("Simulated"))
    }
    @Test fun `cold start and partial history do not call ai`() = runBlocking {
        val api = Fake(); val store = SemanticRecommendationStore(dir(), api, { time })
        val partial = PersistedState(history = listOf(source.toPersisted()), watched = setOf("${source.id}#1"))
        store.rank(PersistedState(), listOf(anime("candidate")), true)
        store.rank(partial, listOf(anime("candidate")), true)
        assertEquals(0, api.calls)
    }
    @Test fun `concurrent opens single flight instead of duplicated calls`() = runBlocking {
        val api = Fake(); val store = SemanticRecommendationStore(dir(), api, { time })
        coroutineScope { repeat(6) { launch { store.rank(state, listOf(anime("candidate")), true) } } }
        assertEquals(1, api.calls)
    }
    @Test fun `daily budget survives restart and never spills into entire catalog`() = runBlocking {
        val directory = dir(); val api = Fake()
        val seed = SemanticCacheState(budgetDay = time / 86400000, requests = SemanticRecommendationStore.MAX_REQUESTS_PER_DAY - 1)
        directory.resolve("semantic-profiles-v1.json").writeText(Json.encodeToString(seed))
        val pool = (1..150).map { anime("candidate$it") }
        SemanticRecommendationStore(directory, api, { time }).rank(state, pool, true)
        SemanticRecommendationStore(directory, api, { time }).rank(state, pool, true)
        assertEquals(1, api.calls)
        assertTrue(api.requested.size <= SemanticRecommendationStore.BATCH_SIZE)
    }
    @Test fun `successful empty extraction is cached not retried endlessly`() = runBlocking {
        var calls = 0
        val store = SemanticRecommendationStore(dir(), SemanticExtractor { titles -> calls++; titles.map { SemanticProfile() } }, { time })
        repeat(3) { store.rank(state, listOf(anime("candidate")), true) }
        assertEquals(1, calls)
    }
    @Test fun `legacy metadata hydration enables only proven final completion and persists`() = runBlocking {
        val directory = dir(); var metadataCalls = 0
        val old = source.copy(airingStatus = 0, episodesTotal = 0).toPersisted()
        val s = PersistedState(history = listOf(old), watched = (1..12).map { "${source.id}#$it" }.toSet())
        val loader: suspend (Anime) -> Anime? = { metadataCalls++; it.copy(airingStatus = 1, episodesTotal = 12) }
        val store = SemanticRecommendationStore(directory, null, { time }, loader)
        assertTrue(store.rank(s, emptyList(), false).taste.isEmpty)
        assertFalse(store.rank(s, emptyList(), true).taste.isEmpty)
        val restart = SemanticRecommendationStore(directory, null, { time }, loader)
        assertFalse(restart.rank(s, emptyList(), false).taste.isEmpty)
        restart.rank(s, emptyList(), true)
        assertEquals(1, metadataCalls)
        assertEquals(0, old.airingStatus) // No mutation of playback saves.
    }
    @Test fun `corrupt cache is recoverable without losing user progress`() = runBlocking {
        val directory = dir()
        directory.resolve("semantic-profiles-v1.json").writeText("{broken")
        val api = Fake()
        val result = SemanticRecommendationStore(directory, api, { time }).rank(state, listOf(anime("candidate")), true)
        assertEquals(1, result.items.size)
        assertEquals(1, state.ratings.size)
        assertEquals(1, api.calls)
    }
    @Test fun `cancelled batch preserves reservation and resumes without immediately duplicating billing`() = runBlocking {
        val directory = dir(); var calls = 0
        val started = CompletableDeferred<Unit>()
        val hanging = SemanticExtractor { calls++; started.complete(Unit); awaitCancellation() }
        val store = SemanticRecommendationStore(directory, hanging, { time })
        val job = launch { store.rank(state, emptyList(), true) }
        started.await(); job.cancelAndJoin()
        SemanticRecommendationStore(directory, hanging, { time }).rank(state, emptyList(), true)
        assertEquals(1, calls)
    }
    @Test fun `cached first paint does not wait for in flight network analysis`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val store = SemanticRecommendationStore(dir(), SemanticExtractor { started.complete(Unit); awaitCancellation() }, { time })
        val pool = listOf(anime("candidate"))
        val job = launch { store.rank(state, pool, true) }
        started.await()
        val warm = withTimeout(1000) { store.rank(state, pool, false) }
        assertEquals(1, warm.items.size)
        job.cancelAndJoin()
    }
}

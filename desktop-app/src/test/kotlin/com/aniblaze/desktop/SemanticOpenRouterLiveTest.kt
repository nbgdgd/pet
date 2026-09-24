package com.aniblaze.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

/** Explicit opt-in only; normal regression tests never spend API credits. */
class SemanticOpenRouterLiveTest {
    @Test fun `real structured extraction with user scoped credential`() = runBlocking {
        if (System.getenv("ANIBLAZE_SEMANTIC_LIVE") != "1") return@runBlocking
        val directory = File(System.getenv("APPDATA"), "AniBlaze")
        val token = assertNotNull(OpenRouterCredential.read(directory), "Encrypted credential unavailable")
        val state = Json { ignoreUnknownKeys = true }.decodeFromString<PersistedState>(File(directory, "state.json").readText())
        val eligible = state.ratings.filter { it.anime.description.length >= 40 }
        val sample = listOfNotNull(eligible.firstOrNull { it.score >= 4 }, eligible.firstOrNull { it.score <= 2 })
            .distinctBy { it.anime.id }.map { it.anime.toAnime() }
        assertTrue(sample.isNotEmpty())
        val profiles = OpenRouterSemantics({ token }).extract(sample)
        assertEquals(sample.size, profiles.size)
        assertTrue(profiles.none { it.isEmpty })
        println("OPENROUTER_LIVE_OK model=${OpenRouterSemantics.MODEL} requests=1 profiles=${profiles.size}")
        profiles.forEachIndexed { i, p -> println("profile[$i] tone=${p.tone} focus=${p.storyFocus} themes=${p.themes}") }
    }
}

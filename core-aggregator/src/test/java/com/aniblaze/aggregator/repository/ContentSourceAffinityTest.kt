package com.aniblaze.aggregator.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentSourceAffinityTest {

    @Test
    fun `known ids prioritize their owning source`() {
        assertEquals(100, contentSourceAffinity("ax:2706", "Anixart"))
        assertEquals(100, contentSourceAffinity("ao:42", "AnimeOn"))
        assertEquals(100, contentSourceAffinity("av:17", "AnimeVost"))
        assertEquals(100, contentSourceAffinity("tmdb:603", "LampaTMDB"))
        assertEquals(100, contentSourceAffinity("tmdbtv:1399", "LampaTMDB"))
        assertEquals(100, contentSourceAffinity("https://rezka.ag/films/example.html", "Rezka"))
    }

    @Test
    fun `unrelated sources keep fallback priority`() {
        assertEquals(0, contentSourceAffinity("ax:2706", "AniLibria"))
        assertEquals(0, contentSourceAffinity("tmdb:603", "Anixart"))
        assertEquals(0, contentSourceAffinity("plain-release-alias", "AniLibria"))
    }
}

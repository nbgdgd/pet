package com.aniblaze.desktop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsChoicePersistenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `grid and sharpening choices survive state serialization`() {
        val saved = PersistedState(gridColumns = 3, videoEnhance = "strong")
        val restored = json.decodeFromString<PersistedState>(json.encodeToString(saved))

        assertEquals(3, restored.gridColumns)
        assertEquals("strong", restored.videoEnhance)
    }

    @Test
    fun `legacy state without new choices keeps safe defaults`() {
        val restored = json.decodeFromString<PersistedState>("{}")

        assertEquals(0, restored.gridColumns)
        assertEquals("off", restored.videoEnhance)
    }
}

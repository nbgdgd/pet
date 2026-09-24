package com.aniblaze.aggregator.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceoverPriorityTest {
    @Test
    fun `priority is dub then studio band then anilibria regardless of order`() {
        val names = listOf("AniLibria", "Студийная Банда", "Любительская", "Полный дубляж")
        assertEquals(3, preferredVoiceoverIndex(names, "", priorityEnabled = true))
    }

    @Test
    fun `studio band aliases are equivalent`() {
        assertEquals(1, voiceoverPriorityRank("Studio Band"))
        assertEquals(1, voiceoverPriorityRank("StudioBand"))
        assertEquals(1, voiceoverPriorityRank("Студийная Банда"))
    }

    @Test
    fun `manual per-title choice overrides automatic priority`() {
        val names = listOf("Дубляж", "Dream Cast", "AniLibria")
        assertEquals(1, preferredVoiceoverIndex(names, "Dream Cast", priorityEnabled = true))
    }

    @Test
    fun `disabled priority leaves selection to existing source fallback`() {
        assertNull(preferredVoiceoverIndex(listOf("AniLibria", "Дубляж"), "", priorityEnabled = false))
    }
}

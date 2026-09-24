package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.Translation
import kotlin.test.Test
import kotlin.test.assertEquals

/** Проценты озвучек — в одной шкале: либо цифры источника, либо Anixart, но не смесь. */
class VoiceSharesScaleTest {
    private val anixart = mapOf("AniLibria" to 900L, "Dream Cast" to 100L, "AniDUB" to 1_000L)

    @Test fun `есть свои просмотры - чужие не подмешиваются`() {
        val voices = listOf(
            Translation(0, "AniLibria", views = 300),
            Translation(1, "Dream Cast", views = 100),
            // Своих цифр нет: раньше сюда подставлялась 1000 из Anixart и ломала шкалу.
            Translation(2, "AniDUB", views = 0),
        )
        val shares = voiceSharesOnOneScale(voices, anixart).map { it.second }
        assertEquals(listOf(300L, 100L, 0L), shares)
    }

    @Test fun `своих цифр нет ни у кого - все по Anixart по имени`() {
        val voices = listOf(Translation(0, "AniLibria.TV"), Translation(1, "Dream Cast"), Translation(2, "Неизвестная студия"))
        val shares = voiceSharesOnOneScale(voices, anixart).map { it.second }
        assertEquals(listOf(900L, 100L, 0L), shares)
    }
}

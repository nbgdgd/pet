package com.aniblaze.desktop.ui

import com.aniblaze.aggregator.model.Anime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationMoodTest {
    private fun card(genres: String) = Anime(id = "ya:1", title = "t", poster = "", genres = genres)

    @Test fun `настроение - по жанрам каталога, регистр и ё не важны, неизвестный ключ - null`() {
        assertTrue(RecommendationMood.LIGHT.matches(card("Комедия, Школа")))
        assertTrue(RecommendationMood.DARK.matches(card("Триллер · Психологическое")))
        assertFalse(RecommendationMood.ROMANCE.matches(card("Экшен, Меха")))
        assertTrue(RecommendationMood.FANTASY.matches(card("Фэнтези, Приключения")))
        assertEquals(RecommendationMood.DRAMA, RecommendationMood.byKey("drama"))
        assertEquals(null, RecommendationMood.byKey("nope"))
        assertEquals(null, RecommendationMood.byKey(null))
    }
}

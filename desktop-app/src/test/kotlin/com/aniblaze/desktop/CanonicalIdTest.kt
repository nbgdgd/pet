package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Один тайтл из разных источников — один id для истории, прогресса и избранного.
 * См. [AppSettings.canonical].
 */
class CanonicalIdTest {
    private fun settings() = AppSettings(Files.createTempDirectory("aniblaze-canonical").resolve("state.json").toFile())

    @Test fun `карточка другого источника приводится к id из прогресса`() {
        val s = settings()
        s.saveProgress(Anime("ax:20257", "Тяжкий труд в подземелье", "p1", year = 2025), 3, 120_000, 1_400_000)
        val fromYummy = Anime("ya:6194", "Тяжкий труд в подземелье", "p2", year = 2025, genres = "Комедия")
        val canon = s.canonical(fromYummy)
        assertEquals("ax:20257", canon.id)
        // Свежие поля пришедшей карточки не теряются.
        assertEquals("p2", canon.poster)
        assertEquals("Комедия", canon.genres)
    }

    @Test fun `другой год - другой тайтл`() {
        val s = settings()
        s.recordHistory(Anime("ax:1", "Хантер х Хантер", "", year = 1999))
        val remake = Anime("ya:468", "Хантер х Хантер", "", year = 2011)
        assertEquals("ya:468", s.canonical(remake).id)
    }

    @Test fun `неизвестный год не мешает совпадению, кино не трогается`() {
        val s = settings()
        s.recordHistory(Anime("ax:609", "Наруто", "", year = 2002))
        assertEquals("ax:609", s.canonical(Anime("ya:111", "Наруто", "")).id)
        s.recordHistory(Anime("tmdbtv:1", "Наруто", ""))
        assertEquals("tmdb:9", s.canonical(Anime("tmdb:9", "Наруто", "")).id)
    }

    @Test fun `известный id остаётся собой`() {
        val s = settings()
        s.recordHistory(Anime("ax:609", "Наруто", ""))
        s.recordHistory(Anime("ya:111", "Наруто", ""))
        assertEquals("ya:111", s.canonical(Anime("ya:111", "Наруто", "")).id)
    }
}

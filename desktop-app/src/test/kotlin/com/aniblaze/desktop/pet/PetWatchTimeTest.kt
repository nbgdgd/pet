package com.aniblaze.desktop.pet

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.AppSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Время просмотра по тайтлу: копится из измеренного воспроизведения, а не из
 * «серии × длительность», и переживает перезапуск.
 */
class PetWatchTimeTest {
    private val anime = Anime("ax:7", "Тайтл", "")

    private fun settings(dir: java.io.File) = AppSettings(dir.resolve("state.json"))

    @Test fun `копится только измеренное время, паузы и перемотки не в счёт`() {
        val dir = Files.createTempDirectory("aniblaze-pet-time").toFile()
        val s = settings(dir)
        // 60 с воспроизведения.
        s.saveProgress(anime, segment = 1, positionMs = 60_000, durationMs = 1_400_000, measuredWatchMs = 60_000)
        // Пауза: позиция та же, измеренного времени нет.
        s.saveProgress(anime, segment = 1, positionMs = 60_000, durationMs = 1_400_000, measuredWatchMs = 0)
        // Перемотка вперёд на 10 минут: позиция скакнула, но смотрели те же 5 с.
        s.saveProgress(anime, segment = 1, positionMs = 660_000, durationMs = 1_400_000, measuredWatchMs = 5_000)
        assertEquals(65_000L, s.watchedMsOf(anime.id))
        // Другой тайтл не мешается.
        assertEquals(0L, s.watchedMsOf("ax:8"))

        s.flush()
        assertEquals(65_000L, settings(dir).watchedMsOf(anime.id))
    }

    @Test fun `дней с прошлого просмотра - из отметок и брошенной позиции, иначе -1`() {
        val dir = Files.createTempDirectory("aniblaze-pet-days").toFile()
        val s = settings(dir)
        assertEquals(-1, s.daysSinceLastWatch(anime.id))
        s.saveProgress(anime, segment = 2, positionMs = 300_000, durationMs = 1_400_000, measuredWatchMs = 60_000)
        val at = s.lastWatchedAt(anime.id)
        assert(at > 0L)
        assertEquals(0, s.daysSinceLastWatch(anime.id, now = at + 3600_000L))
        assertEquals(12, s.daysSinceLastWatch(anime.id, now = at + 12L * 24 * 3600_000L + 1))
    }
}

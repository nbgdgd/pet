package com.aniblaze.desktop.pet

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.source.EpisodeAirDates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Питомец не выдумывает чисел: чего не знает — то и говорит «неизвестно». */
class PetBrainTest {
    private val anime = Anime("ax:1", "Тестовое аниме", "", episodesTotal = 12, episodesAvailable = 5)
    private val zone = java.time.ZoneId.systemDefault()

    private fun schedule(
        status: EpisodeAirDates.Status = EpisodeAirDates.Status.AIRING,
        total: Int = 12,
        next: Int = 6,
        atEpochSec: Long = 0,
    ) = EpisodeAirDates.TitleSchedule(
        status = status, totalEpisodes = total, nextEpisode = next, nextAiringAt = atEpochSec,
    )

    @Test fun `непросмотренные - среди вышедших и до конца сезона`() {
        val stats = petTitleStats(anime, watchedEpisodes = 3, airedEpisodes = 5, schedule = schedule(), watchedMs = 0)
        assertEquals(2, stats.unwatchedAired)
        assertEquals(9, stats.unwatchedTotal)
        assertEquals(12, stats.totalEpisodes)
    }

    @Test fun `без расписания и без общего числа - только вышедшие`() {
        val bare = Anime("ax:2", "Без данных", "")
        val stats = petTitleStats(bare, watchedEpisodes = 0, airedEpisodes = 0, schedule = EpisodeAirDates.TitleSchedule.EMPTY, watchedMs = 0)
        assertNull(stats.unwatchedAired)
        assertNull(stats.unwatchedTotal)
        assertNull(petNextEpisodeLabel(stats))
        assertTrue(petStatLines(stats).any { it.contains("Дата следующей серии пока неизвестна") })
    }

    @Test fun `следующая серия - дата и обратный отсчёт в зоне пользователя`() {
        val now = java.time.LocalDate.of(2026, 9, 18).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val at = java.time.LocalDate.of(2026, 9, 20).atTime(15, 30).atZone(zone).toInstant().epochSecond
        val stats = petTitleStats(anime, 3, 5, schedule(next = 6, atEpochSec = at), 0)
        assertEquals("Серия 6 — через 2 дн. в 15:30", petNextEpisodeLabel(stats, now))
    }

    @Test fun `время просмотра - реальное, не серии на длительность`() {
        assertNull(petWatchTimeLabel(0))
        assertNull(petWatchTimeLabel(30_000))
        assertEquals("34 мин", petWatchTimeLabel(34 * 60_000L))
        assertEquals("2 ч 15 мин", petWatchTimeLabel((2 * 60 + 15) * 60_000L))
        assertEquals("3 ч", petWatchTimeLabel(3 * 60 * 60_000L))
        val stats = petTitleStats(anime, 3, 5, schedule(), watchedMs = 95 * 60_000L)
        assertTrue(petStatLines(stats).any { it == "Ты смотрел это 1 ч 35 мин" })
    }

    @Test fun `догнали релиз - грусть и своя фраза, завершённый сезон - другая`() {
        val caught = petTitleStats(anime, watchedEpisodes = 5, airedEpisodes = 5, schedule = schedule(), watchedMs = 0)
        assertEquals(0, caught.unwatchedAired)
        assertTrue(!caught.seasonFinished)
        assertTrue(PetPhrases.of(PetEvent.CAUGHT_UP).first().contains("Ждём новую серию"))
        assertEquals(PetMood.WAITING, PetPhrases.moodOf(PetEvent.CAUGHT_UP))

        val done = petTitleStats(
            anime, watchedEpisodes = 12, airedEpisodes = 12,
            schedule = schedule(status = EpisodeAirDates.Status.FINISHED, next = 0), watchedMs = 0,
        )
        assertTrue(done.seasonFinished)
        assertTrue(PetPhrases.of(PetEvent.SEASON_DONE).first().contains("досмотрели сезон"))
        assertEquals(PetMood.CELEBRATING, PetPhrases.moodOf(PetEvent.SEASON_DONE))
        assertEquals(PetMood.HAPPY, PetPhrases.moodOf(PetEvent.NEW_EPISODE))
    }

    @Test fun `фразы не повторяются подряд`() {
        val picker = PetPhrasePicker()
        var previous = picker.pick(PetEvent.RETURN)
        repeat(20) {
            val next = picker.pick(PetEvent.RETURN)
            assertTrue(next != previous, "фраза повторилась подряд: $next")
            previous = next
        }
    }

    @Test fun `анимация под настроение`() {
        assertEquals(PetAction.HAPPY, petActionFor(PetMood.HAPPY))
        assertEquals(PetAction.SAD, petActionFor(PetMood.SAD))
        assertEquals(PetAction.SLEEP, petActionFor(PetMood.SLEEPY))
        assertEquals(PetAction.IDLE, petActionFor(PetMood.IDLE))
    }

    @Test fun `атласы всех питомцев лежат в ресурсах и совпадают с сеткой 8x9`() {
        PetDef.ALL.forEach { pet ->
            val stream = javaClass.classLoader.getResourceAsStream(pet.spritesheetPath)
            assertNotNull(stream, "нет атласа ${pet.spritesheetPath}")
            stream.use { assertTrue(it.readBytes().size > 100_000, "атлас ${pet.id} подозрительно мал") }
        }
    }

    @Test fun `клипы не выходят за границы атласа и имеют разные кадры`() {
        PetDef.ALL.forEach { pet ->
            PetAction.entries.forEach { action ->
                val clip = pet.clip(action)
                clip.frames.forEachIndexed { i, column ->
                    assertTrue(column in 0 until PetAtlas.COLS, "${pet.id}/$action: колонка $column")
                    assertTrue(clip.rowAt(i) in 0 until PetAtlas.ROWS, "${pet.id}/$action: ряд ${clip.rowAt(i)}")
                }
                assertTrue(clip.durations.all { it > 0 }, "${pet.id}/$action: нулевая длительность")
            }
            // Анимация, а не перемещение картинки: в покое кадры разные.
            val idle = pet.clip(PetAction.IDLE)
            assertTrue(idle.frames.toSet().size >= 3, "${pet.id}: idle из одного кадра — это не анимация")
        }
    }

    @Test fun `позиция питомца в плеере не выходит за границы`() {
        assertEquals(0f, petPixelPosition(-1f, 200f))
        assertEquals(200f, petPixelPosition(1f, 200f))
        assertEquals(100f, petPixelPosition(0.5f, 200f))
        assertEquals(0f, petPixelPosition(0.9f, 0f))
    }

    @Test fun `webp-атлас реально декодируется в ImageBitmap нужного размера`() {
        PetDef.ALL.forEach { pet ->
            val bmp = PetAtlas.get(pet)
            assertNotNull(bmp, "атлас ${pet.id} не декодировался")
            assertEquals(1536, bmp.width)
            assertEquals(1872, bmp.height)
            assertEquals(192, PetAtlas.cellWidth(bmp))
            assertEquals(208, PetAtlas.cellHeight(bmp))
        }
    }

    @Test fun `событие плеера различает конец серии, догнали и конец сезона`() {
        assertEquals(PetEvent.EPISODE_START, petPlaybackEvent(3, 12, 12, finished = false))
        assertEquals(PetEvent.EPISODE_DONE, petPlaybackEvent(3, 12, 12, finished = true))
        assertEquals(PetEvent.CAUGHT_UP, petPlaybackEvent(5, 5, 12, finished = true, caughtUp = true))
        assertEquals(PetEvent.SEASON_DONE, petPlaybackEvent(12, 12, 12, finished = true, seasonCompleted = true))
        // Общее число неизвестно — про сезон ничего не утверждаем.
        assertEquals(PetEvent.EPISODE_DONE, petPlaybackEvent(5, 5, 0, finished = true))
        assertEquals(PetEvent.EPISODE_DONE, petPlaybackEvent(12, 12, 12, finished = true))
        assertEquals(PetEvent.EPISODE_DONE, petPlaybackEvent(2, 0, 0, finished = true))
        // Каждое событие озвучено.
        PetEvent.entries.forEach { assertTrue(PetPhrases.of(it).isNotEmpty(), "нет фраз для $it") }
    }

    @Test fun `предложение вернуться после отлучки - минуты и позиция`() {
        assertEquals("Тебя не было 5 мин. Вернуться на 14:32?", petAwayText(PetAwayOffer(14 * 60_000L + 32_000L, 5 * 60_000L)))
        assertEquals("Тебя не было 1 ч 10 мин. Вернуться на 1:02:05?", petAwayText(PetAwayOffer(3_725_000L, 70 * 60_000L)))
        // Меньше минуты — без округления до нуля.
        assertEquals("Тебя не было 45 с. Вернуться на 0:10?", petAwayText(PetAwayOffer(10_000L, 45_000L)))
    }

    @Test fun `«в прошлый раз остановился N дней назад» - только когда след есть`() {
        val stats = petTitleStats(anime, 3, 5, schedule(), 0)
        assertTrue(petStatLines(stats, daysSinceLastWatch = 12).first() == "В прошлый раз ты остановился здесь 12 дней назад")
        assertTrue(petStatLines(stats, daysSinceLastWatch = -1).none { it.contains("прошлый раз") })
        assertTrue(petStatLines(stats, daysSinceLastWatch = 0).none { it.contains("прошлый раз") })
        assertTrue(PetPhrases.of(PetEvent.REWATCH).first() == "Ты уже смотрел это раньше")
    }
}

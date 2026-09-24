package com.aniblaze.desktop.pet

import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.PersistedState
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.*

class PetAdditionsTest {
    private fun scene() = PetDirector.Scene(
        episode = 3, positionMs = 300_000, durationMs = 1_440_000,
        playing = true, buffering = false, episodesAvailable = 12, episodesTotal = 12,
        streak = 0, sessionMs = 0, pausedForMs = 0, now = 1_000_000,
        hourOfDay = 12, quietWatching = true,
    )

    @Test fun `quiet watching suppresses incidental reactions but preserves completion and controls`() {
        val d = PetDirector()
        assertNull(d.decide(scene().copy(speed = 2f, forwards = 5, fullscreen = true)).say)
        assertEquals(PetMood.IDLE, d.decide(scene().copy(now = 2_000_000)).mood)
        val completion = d.decide(scene().copy(completion = PetEvent.SEASON_DONE, now = 2_001_000))
        assertEquals(PetMood.CELEBRATING, completion.mood)
        assertEquals(PetAsk.RATE_SEASON, completion.ask)
        assertEquals(PetAsk.SKIP_ENDING, PetDirector().decide(scene().copy(endingConfirmed = true, hasNext = true)).ask)
        assertEquals(PetMood.PAUSED, PetDirector().decide(scene().copy(playing = false, userPaused = true, pausedForMs = 4_000)).mood)
        assertNotNull(PetDirector().decide(scene().copy(error = true)).say)
    }

    @Test fun `enabling quiet mode interrupts incidental animation`() {
        val d = PetDirector()
        assertEquals(PetMood.EXCITED, d.decide(scene().copy(quietWatching = false, speed = 2f)).mood)
        assertEquals(PetMood.IDLE, d.decide(scene().copy(speed = 2f, now = 1_000_100)).mood)
    }

    @Test fun `diary uses measured daily time and ignores invalid or future days`() {
        val today = LocalDate.of(2026, 9, 19)
        val state = PersistedState(watchedByDay = mapOf(
            today.toString() to 90 * 60_000L, "2026-09-18" to 5_000,
            "2026-09-20" to 5_000, "bad-date" to 5_000, "2026-09-17" to -2,
        ))
        assertEquals(listOf("Сегодня: 1 ч 30 мин просмотра", "За неделю: 1 ч 30 мин", "Дней с просмотром: 2"), petDiary(state, today))
        assertEquals("Сегодня: ещё не смотрели", petDiary(state, today.plusDays(3)).first())
        assertEquals("Сегодня: меньше минуты просмотра", petDiary(state, today.minusDays(1)).first())
        // Неделя: считает семь дней включая сегодня, серии — по отметкам за этот срок.
        val zone = java.time.ZoneId.systemDefault()
        val weekly = state.copy(
            watchedByDay = state.watchedByDay + ("2026-09-13" to 30 * 60_000L) + ("2026-09-12" to 60 * 60_000L),
            watchedAt = mapOf("a#1" to today.atStartOfDay(zone).toInstant().toEpochMilli() + 1, "a#2" to LocalDate.of(2026, 9, 1).atStartOfDay(zone).toInstant().toEpochMilli()),
        )
        assertEquals("За неделю: 2 ч, серий: 1", petDiary(weekly, today)[1])
        assertTrue(petDiary(PersistedState(), today).none { it.startsWith("За неделю") })
    }

    @Test fun `night rest wakes after interaction or at daytime`() {
        assertEquals(PetMood.SLEEPY, petIdleMood(2, 120_000))
        assertEquals(PetMood.IDLE, petIdleMood(2, 0))
        assertEquals(PetMood.IDLE, petIdleMood(12, 600_000))
        assertTrue(petGreeting(PetDef.CLAUDE, 8).contains("Доброе утро"))
    }

    @Test fun `rating feedback expires and respects the existing error priority`() {
        val d = PetDirector()
        val ended = scene().copy(playing = false, ended = true, userPaused = false)
        d.decide(ended)
        assertEquals(PetMood.CELEBRATING, d.reactToRating(5, "claude", 1_000_100)?.mood)
        assertEquals(PetMood.CELEBRATING, d.decide(ended.copy(now = 1_000_200)).mood)
        assertEquals(PetMood.IDLE, d.decide(ended.copy(now = 1_020_000)).mood)
        d.reactToRating(1, "claude", 1_021_000)
        assertEquals(PetMood.WAITING, d.decide(ended.copy(error = true, now = 1_021_100)).mood)
        assertNull(d.reactToRating(0, "claude", 1_022_000))
    }

    @Test fun `fullscreen position and quiet preference survive restart independently`() {
        val file = Files.createTempDirectory("pet-additions").resolve("state.json").toFile()
        val settings = AppSettings(file)
        assertNull(settings.state.value.petFullscreenX)
        settings.setPetPlayerPosition(.2f, .3f)
        settings.setPetFullscreenPosition(.7f, .8f)
        settings.setPetQuietWatching(false)
        settings.flush()
        val restored = AppSettings(file).state.value
        assertEquals(.2f, restored.petPlayerX)
        assertEquals(.3f, restored.petPlayerY)
        assertEquals(.7f, restored.petFullscreenX)
        assertEquals(.8f, restored.petFullscreenY)
        assertFalse(restored.petQuietWatching)
    }
}

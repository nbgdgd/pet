package com.aniblaze.desktop.pet

import com.aniblaze.desktop.AppSettings
import java.nio.file.Files
import kotlin.test.*

class PetSafetyTest {
    private fun scene() = PetDirector.Scene(
        episode = 3, positionMs = 300_000, durationMs = 1_440_000,
        playing = true, buffering = false, episodesAvailable = 12, episodesTotal = 12,
        streak = 0, sessionMs = 0, pausedForMs = 0, now = 1_000_000,
        hourOfDay = 12,
    )

    @Test fun `position at end does not prove completion`() {
        val d = PetDirector().decide(scene().copy(episode = 12, positionMs = 1_440_000))
        assertNull(d.ask)
        assertNull(d.say)
        assertEquals(PetEvent.EPISODE_DONE, petPlaybackEvent(12, 12, 12, true))
    }

    @Test fun `only confirmed season completion offers rating`() {
        val d = PetDirector().decide(scene().copy(episode = 12, completionEpisode = 12,
            completion = PetEvent.SEASON_DONE, ended = true, playing = false, userPaused = false))
        assertEquals(PetAsk.RATE_SEASON, d.ask)
        assertEquals(PetMood.CELEBRATING, d.mood)
        assertEquals(PetAction.CELEBRATE, petActionFor(d.mood))
    }

    @Test fun `end of previous episode survives autoplay without stale rating card`() {
        val session = PetPlaybackSession()
        session.recordCompletion(3, PetEvent.EPISODE_DONE, true, 1_000_000)
        val e = assertNotNull(session.takeCompletion(1_001_000))
        val d = session.director.decide(scene().copy(episode = 4, completion = e.event, completionEpisode = e.episode))
        assertEquals("Серия 3 досмотрена", d.say)
        assertNull(d.ask)
        session.recordCompletion(3, PetEvent.EPISODE_DONE, true, 1_002_000)
        assertNull(session.takeCompletion(1_003_000))
    }

    @Test fun `manual pause overrides speed and buffering never counts as pause`() {
        val d = PetDirector()
        assertEquals(PetMood.PAUSED, d.decide(scene().copy(playing = false, userPaused = true,
            speed = 2f, pausedForMs = 10_000)).mood)
        assertEquals(PetMood.WAITING, d.decide(scene().copy(playing = false, userPaused = false,
            buffering = true, pausedForMs = 600_000, now = 1_100_000)).mood)
    }

    @Test fun `reaction finishes and falls back to playback instead of freezing at speed`() {
        val d = PetDirector()
        assertEquals(PetMood.EXCITED, d.decide(scene().copy(speed = 2f)).mood)
        assertEquals(PetMood.EXCITED, d.decide(scene().copy(speed = 2f, now = 1_000_500)).mood)
        assertEquals(PetMood.IDLE, d.decide(scene().copy(speed = 2f, now = 1_010_000)).mood)
    }

    @Test fun `fidget obeys silence and does not bypass twenty minute interval`() {
        val d = PetDirector(20 * 60_000L)
        d.decide(scene())
        assertNull(d.decide(scene().copy(now = 1_240_000)).say)
        assertNull(PetDirector().decide(scene().copy(positionMs = 10_000, speechEnabled = false)).say)
    }

    @Test fun `seeking forward is not measured viewing and switching episodes is not a streak`() {
        val d = PetDirector().decide(scene().copy(positionMs = 41 * 60_000L, durationMs = 90 * 60_000L))
        assertNull(d.say)
        val session = PetPlaybackSession()
        session.recordWatch(3, 5_000)
        session.recordWatch(4, 0)
        assertEquals(5_000L, session.watchedMs)
        assertEquals(0, session.completedCount)
    }

    @Test fun `ending offer requires timing and actual next episode`() {
        val tail = scene().copy(positionMs = 1_350_000)
        assertNull(PetDirector().decide(tail.copy(hasNext = true)).ask)
        assertNull(PetDirector().decide(tail.copy(endingConfirmed = true)).ask)
        assertEquals(PetAsk.SKIP_ENDING, PetDirector().decide(tail.copy(hasNext = true, endingConfirmed = true)).ask)
    }

    @Test fun `completion dedupe and speech preference survive restart`() {
        val file = Files.createTempDirectory("pet-events").resolve("state.json").toFile()
        val settings = AppSettings(file)
        assertTrue(settings.claimPetEvent("done:ax:7:12"))
        assertFalse(settings.claimPetEvent("done:ax:7:12"))
        settings.setPetSpeechEnabled(false)
        settings.flush()
        val restored = AppSettings(file)
        assertFalse(restored.claimPetEvent("done:ax:7:12"))
        assertFalse(restored.state.value.petSpeechEnabled)
    }

    @Test fun `reaction interrupted by buffering returns to current playback state`() {
        val d = PetDirector()
        assertEquals(PetMood.EXCITED, d.decide(scene().copy(speed = 2f)).mood)
        assertEquals(PetMood.WAITING, d.decide(scene().copy(buffering = true, now = 1_000_300)).mood)
        assertEquals(PetMood.IDLE, d.decide(scene().copy(now = 1_001_000)).mood)
    }

    @Test fun `returning to an episode does not congratulate again`() {
        val d = PetDirector()
        val done = scene().copy(completion = PetEvent.EPISODE_DONE)
        assertNotNull(d.decide(done).say)
        d.decide(scene().copy(episode = 4, mediaKey = "4", now = 1_020_000))
        assertNull(d.decide(done.copy(now = 1_030_000)).say)
    }

    @Test fun `stale pending completion is not spoken after a long absence`() {
        val session = PetPlaybackSession()
        session.recordCompletion(3, PetEvent.EPISODE_DONE, true, 1_000)
        assertNull(session.takeCompletion(40_000))
        assertEquals(1, session.completedCount)
    }

    @Test fun `quiet and energetic pets have distinct pacing and resting frames`() {
        val energetic = PetDef.of("eigenblob").personality
        val quiet = PetDef.of("aqua-wisp").personality
        assertTrue(quiet.fidgetMinutes > energetic.fidgetMinutes)
        assertTrue(quiet.speechMultiplier > energetic.speechMultiplier)
        assertEquals(5, PetDef.ALL.map { it.personality.pause }.toSet().size)
        PetDef.ALL.forEach { assertTrue(it.clip(PetAction.SIT).loop) }
        assertFalse(PetDef.of("drizz").clip(PetAction.IDLE).frames.contentEquals(PetDef.of("nezukocoder").clip(PetAction.IDLE).frames))
    }

    @Test fun `pet area leaves subtitle region free at desktop sizes`() {
        for (height in listOf(360f, 600f, 1080f)) {
            val block = 80f * 1.6f + 58f
            assertTrue(petSafeVerticalSpace(height, block) + block <= height * .55f)
        }
        assertEquals(0f, petSafeVerticalSpace(100f, 186f))
    }

    @Test fun `finished airing and equal counts alone do not prove watched season`() {
        val anime = com.aniblaze.aggregator.model.Anime("ax:1", "Test", "", episodesTotal = 12, episodesAvailable = 12, airingStatus = 1)
        val stats = petTitleStats(anime, 12, 12, com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule.EMPTY, 0)
        assertTrue(stats.seasonFinished)
        assertTrue(petStatLines(stats).none { it.contains("Сезон досмотрен") })
        assertTrue(petStatLines(stats.copy(seasonCompleted = true)).contains("Сезон досмотрен"))
    }

    @Test fun `elapsed forecast does not claim a new episode`() {
        val anime = com.aniblaze.aggregator.model.Anime("ax:1", "Test", "")
        val stats = petTitleStats(anime, 0, 0, com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule.EMPTY, 0)
            .copy(nextAiringAt = 1_000, nextEpisode = 3)
        assertNull(petNextEpisodeLabel(stats, now = 2_000))
    }

    @Test fun `only recent unseen favorite releases trigger a message`() {
        val title = com.aniblaze.desktop.PersistedAnime(id = "ax:1", title = "Test", poster = "")
        val base = com.aniblaze.desktop.PersistedState(favorites = listOf(title),
            newEpisodes = mapOf(title.id to 5), newEpisodeAt = mapOf(title.id to 1_000L))
        assertEquals(title, petFreshRelease(base, 2_000))
        assertNull(petFreshRelease(base.copy(favorites = emptyList()), 2_000))
        assertNull(petFreshRelease(base.copy(newEpisodeAt = emptyMap()), 2_000))
        assertNull(petFreshRelease(base.copy(petAnnouncedEvents = setOf("new:ax:1:5")), 2_000))
        assertNull(petFreshRelease(base, com.aniblaze.desktop.NEW_EPISODE_WINDOW_MS + 2_000))
        assertNull(petFreshRelease(base.copy(releaseMuted = setOf("ax:1")), 2_000), "«Не напоминать» глушит тайтл")
    }

    @Test fun `позже - напоминание возвращается по сроку, не напоминать - переключается`() {
        val settings = com.aniblaze.desktop.AppSettings(java.nio.file.Files.createTempDirectory("aniblaze-later").resolve("state.json").toFile())
        settings.remindLater("new:ax:1:5", delayMs = 60_000L)
        assertTrue(settings.claimDueReminders().isEmpty(), "срок ещё не пришёл")
        settings.remindLater("new:ax:2:3", delayMs = -1L)
        assertEquals(listOf("new:ax:2:3"), settings.claimDueReminders())
        assertTrue(settings.claimDueReminders().isEmpty(), "выдаётся один раз")
        assertEquals(setOf("new:ax:1:5"), settings.state.value.releaseLater.keys)

        settings.setReleaseMuted("ax:1", true)
        assertTrue(settings.isReleaseMuted("ax:1"))
        settings.setReleaseMuted("ax:1", false)
        assertFalse(settings.isReleaseMuted("ax:1"))
    }

    @Test fun `specials do not fill missing main episodes in the pet card`() {
        val state = com.aniblaze.desktop.PersistedState(watched = setOf("ax:1#1", "ax:1#2", "ax:1#13", "ax:2#3"))
        assertEquals(2, petMainWatchedCount(state, "ax:1", 12))
    }
}

package com.aniblaze.desktop.pet

import kotlin.test.*

class DrizzBehaviorTest {
    @Test fun `Drizz cycles borrowed pause lines through actual pause events`() {
        val director = PetDirector()
        val lines = mutableSetOf<String>()
        val options = DrizzPhrases.pauses.distinct()
        for (index in options.indices) {
            val time = 1_000_000L + index * 30_000L
            director.decide(scene(time).copy(speechEnabled = false))
            val paused = scene(time + 1_000).copy(playing = false, userPaused = true, pausedForMs = 1_000)
            val reaction = director.decide(paused)
            assertEquals(PetMood.PAUSED, reaction.mood)
            assertTrue(lines.add(assertNotNull(reaction.say)), "Repeated pause before exhausting variants")
            assertNull(director.decide(paused.copy(now = time + 10_000)).say)
        }
        assertTrue(lines.contains(PetDef.CLAUDE.personality.pause))
        assertTrue(lines.contains(PetDef.EIGENBLOB.personality.pause))
    }

    @Test fun `Drizz completion variants do not offer missing episodes or repeat completion`() {
        val director = PetDirector()
        for (index in 1..9) {
            val done = scene(1_000_000L + index * 30_000L).copy(
                mediaKey = "title:$index", completion = PetEvent.EPISODE_DONE, hasNext = false,
            )
            val reaction = director.decide(done)
            assertTrue(reaction.say in setOf("Серия досмотрена.", "Можно немного отдохнуть.", "Серия 3 досмотрена"))
            assertNull(reaction.ask)
            assertNull(director.decide(done.copy(now = done.now + 10_000)).say)
        }
    }

    private fun scene(t: Long = 1_000_000) = PetDirector.Scene(
        episode = 3, positionMs = 300_000, durationMs = 1_440_000,
        playing = true, buffering = false, episodesAvailable = 12, episodesTotal = 12,
        streak = 0, sessionMs = 0, pausedForMs = 0, now = t,
        hourOfDay = 12, quietWatching = true, character = "drizz",
        voiceName = "Studio Band", sourceName = "source", qualityName = "1080p",
    )

    @Test fun `quiet Drizz moves without speaking then returns to idle`() {
        val d = PetDirector()
        assertEquals(PetMood.IDLE, d.decide(scene()).mood)
        val move = d.decide(scene(1_031_000))
        assertEquals(PetMood.FIDGET, move.mood)
        assertNull(move.say)
        assertEquals(PetMood.IDLE, d.decide(scene(1_040_000)).mood)
        assertTrue((0..100).all { drizzMotionInterval(it) in 30_000..70_000 })
    }

    @Test fun `other pets keep existing quiet behavior`() {
        for (id in listOf("claude", "eigenblob", "aqua-wisp", "nezukocoder")) {
            val d = PetDirector()
            d.decide(scene().copy(character = id))
            assertEquals(PetMood.IDLE, d.decide(scene(1_050_000).copy(character = id, muted = true, speed = 2f)).mood)
        }
    }

    @Test fun `mute speed voice quality source and seek have contextual finite reactions`() {
        val cases = listOf(
            scene(1_001_000).copy(muted = true) to PetMood.LISTEN,
            scene(1_001_000).copy(speed = 2f) to PetMood.EXCITED,
            scene(1_001_000).copy(speed = .5f) to PetMood.LISTEN,
            scene(1_001_000).copy(voiceName = "AniLibria") to PetMood.LISTEN,
            scene(1_001_000).copy(qualityName = "720p") to PetMood.LISTEN,
            scene(1_001_000).copy(sourceName = "backup") to PetMood.LISTEN,
            scene(1_001_000).copy(subtitlesName = "Русские") to PetMood.LISTEN,
            scene(1_001_000).copy(seekSerial = 1, seekBackwards = true) to PetMood.SURPRISED,
            scene(1_001_000).copy(seekSerial = 1) to PetMood.EXCITED,
        )
        for ((changed, expected) in cases) {
            val d = PetDirector()
            d.decide(scene())
            val action = d.decide(changed)
            assertEquals(expected, action.mood)
            assertNotNull(action.say)
            assertEquals(PetMood.IDLE, d.decide(changed.copy(now = 1_020_000)).mood)
        }
    }

    @Test fun `action speech respects mute cooldown and does not repeat on unchanged snapshots`() {
        val d = PetDirector()
        d.decide(scene())
        assertTrue(d.decide(scene(1_001_000).copy(muted = true)).say!!.contains("выключен"))
        assertNull(d.decide(scene(1_005_000).copy(muted = false)).say)
        assertNull(d.decide(scene(1_010_000)).say)
        assertTrue(d.decide(scene(1_011_000).copy(speed = 1.5f)).say!!.contains("1,5×"))
        assertTrue(d.decide(scene(1_020_000).copy(speed = 1.5f, subtitlesName = "Русские")).say!!.contains("Субтитры"))
        val muted = PetDirector()
        muted.decide(scene().copy(speechEnabled = false))
        assertNull(muted.decide(scene(1_001_000).copy(speed = 2f, speechEnabled = false)).say)
        assertEquals(PetMood.EXCITED, muted.decide(scene(1_001_500).copy(speed = 2f, speechEnabled = false)).mood)
    }

    @Test fun `controls still receive words on manual pause without waking sleeping pet`() {
        val d = PetDirector()
        val paused = scene().copy(playing = false, userPaused = true, pausedForMs = 360_000)
        d.decide(paused)
        val changed = d.decide(paused.copy(now = 1_020_000, muted = true))
        assertEquals(PetMood.SLEEPY, changed.mood)
        assertTrue(changed.say!!.contains("выключен"))
    }

    @Test fun `short buffer is silent sustained buffer recovers once and is not pause`() {
        val d = PetDirector()
        val wait = scene().copy(playing = false, buffering = true, userPaused = false)
        assertEquals(PetMood.IDLE, d.decide(wait).mood)
        assertNull(d.decide(wait.copy(now = 1_001_500)).say)
        assertEquals(PetMood.WAITING, d.decide(wait.copy(now = 1_002_500)).mood)
        assertEquals(PetMood.HAPPY, d.decide(scene(1_003_000)).mood)
        assertEquals(PetMood.IDLE, d.decide(scene(1_020_000)).mood)
        val short = PetDirector()
        short.decide(scene())
        short.decide(wait.copy(now = 1_001_000))
        assertEquals(PetMood.IDLE, short.decide(scene(1_001_500)).mood)
    }

    @Test fun `pause error and completion preempt idle and no next does not mean finished`() {
        val d = PetDirector()
        d.decide(scene())
        d.decide(scene(1_031_000))
        val paused = scene(1_032_000).copy(playing = false, userPaused = true, pausedForMs = 4_000)
        assertEquals(PetMood.PAUSED, d.decide(paused).mood)
        assertEquals(PetMood.SLEEPY, d.decide(paused.copy(now = 1_400_000, pausedForMs = 360_000)).mood)
        assertEquals(PetMood.GREETING, d.decide(scene(1_401_000)).mood)
        assertEquals(PetMood.WAITING, d.decide(scene(1_402_000).copy(error = true, completion = PetEvent.SEASON_DONE)).mood)
        val done = scene(1_403_000).copy(completion = PetEvent.SEASON_DONE)
        assertEquals(PetAsk.RATE_SEASON, d.decide(done).ask)
        assertNull(d.decide(done.copy(now = 1_410_000)).say)
        assertNull(PetDirector().decide(scene().copy(ended = true, hasNext = false)).ask)
    }

    @Test fun `rest is finite tiredness uses real time not four short episodes`() {
        val d = PetDirector()
        d.decide(scene())
        assertEquals(PetMood.PAUSED, d.decide(scene(1_601_000)).mood)
        assertEquals(PetMood.IDLE, d.decide(scene(1_607_000)).mood)
        assertEquals(PetMood.IDLE, PetDirector().decide(scene().copy(streak = 4)).mood)
        assertEquals(PetMood.WAITING, PetDirector().decide(scene().copy(sessionMs = PetDirector.TIRED_SESSION_MS)).mood)
    }

    @Test fun `action interrupts a fidget and pet change resets baseline`() {
        val d = PetDirector()
        d.decide(scene())
        d.decide(scene(1_031_000))
        assertEquals(PetMood.EXCITED, d.decide(scene(1_032_000).copy(speed = 2f)).mood)
        assertEquals(PetMood.IDLE, d.decide(scene(1_032_100).copy(character = "claude")).mood)
        assertEquals(PetMood.IDLE, d.decide(scene(1_032_200).copy(speed = 2f)).mood)
    }

    @Test fun `ratings retain their own scale and unknown values are hidden`() {
        assertEquals(listOf("Ваша оценка: 5/5", "Оценка каталога: 8,7/10"), petRatingLines(5, 8.7, 10.0))
        assertEquals(listOf("Оценка каталога: 4,8/5"), petRatingLines(0, 4.8, 5.0))
        assertTrue(petRatingLines(0, 0.0, 0.0).isEmpty())
        assertTrue(petRatingLines(7, Double.NaN, 10.0).isEmpty())
        assertTrue(petRatingLines(0, 8.7, 5.0).isEmpty())
    }
}

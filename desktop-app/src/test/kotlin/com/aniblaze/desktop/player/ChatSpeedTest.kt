package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.TitleComment
import com.aniblaze.desktop.AppSettings
import java.nio.file.Files
import kotlin.test.*

class ChatSpeedTest {
    private val comments = (1..600).map { i -> TitleComment(id = i.toLong(), author = "Зритель $i",
        avatar = "", message = "Комментарий $i", timestamp = i.toLong(), votes = 0, isSpoiler = false, episode = 0) }
    private fun engine(speed: Float = 1f, intensity: ChatIntensity = ChatIntensity.NORMAL, quiet: Boolean = true) =
        ChatEngine("speed-test", comments.size, 150, intensity, quiet, comments, 42, speed).also { it.setRealOnly(true) }
    private fun poll(e: ChatEngine) = e.poll(100_000, 1_440_000, null, null, SceneSample.UNKNOWN, CHAT_TICK_MS)
    private fun run(e: ChatEngine, seconds: Int): List<ChatMessage> = buildList {
        repeat(seconds * 1000 / CHAT_TICK_MS.toInt()) { e.accumulate(CHAT_TICK_MS); addAll(poll(e)) }
    }

    @Test fun `speed presets visibly change real message frequency at every density`() {
        for (density in ChatIntensity.entries) {
            val counts = CHAT_SPEED_PRESETS.map { (speed, _) -> run(engine(speed, density), 180).size }
            assertTrue(counts.zipWithNext().all { (a, b) -> b > a }, "$density: $counts")
            assertTrue(counts.first() < counts[1] * 0.75, "$density: $counts")
            assertTrue(counts.last() > counts[1] * 1.5, "$density: $counts")
        }
    }
    @Test fun `speed applies to generated chat as well as real only`() {
        fun generated(speed: Float) = ChatEngine("generated", 0, 150, ChatIntensity.NORMAL, true, emptyList(), 42, speed)
        assertTrue(run(generated(2f), 120).size > run(generated(0.5f), 120).size * 2)
    }
    @Test fun `changing speed rescales the pending delay immediately`() {
        val e = engine()
        e.accumulate(1000)
        assertTrue(poll(e).isEmpty())
        e.retune(ChatIntensity.NORMAL, 150, true, 2f)
        e.accumulate(249)
        assertTrue(poll(e).isEmpty())
        e.accumulate(1)
        assertEquals(1, poll(e).size)
    }
    @Test fun `slowing down rescales the remaining delay without restarting it`() {
        val e = engine()
        e.accumulate(1000)
        e.retune(ChatIntensity.NORMAL, 150, true, 0.5f)
        e.accumulate(999)
        assertTrue(poll(e).isEmpty())
        e.accumulate(1)
        assertEquals(1, poll(e).size)
    }
    @Test fun `retuning never clears or repeats the real queue across seeks and pause`() {
        val e = engine()
        val first = run(e, 40)
        val count = e.messagesIssued
        e.retune(ChatIntensity.NORMAL, 150, true, 2f)
        repeat(100) { assertTrue(poll(e).isEmpty()) }
        assertEquals(count, e.messagesIssued)
        val faster = run(e, 40)
        e.onSeek()
        val afterSeek = run(e, 40)
        val all = first + faster + afterSeek
        assertTrue(all.all { it.real })
        assertEquals(all.size, all.map { it.text }.distinct().size)
        assertEquals(all.size.toLong(), e.messagesIssued)
    }
    @Test fun `no real comments remains silent even at double speed`() {
        val e = ChatEngine("empty", 0, 150, ChatIntensity.STORM, false, emptyList(), 42, 2f)
        e.setRealOnly(true)
        assertTrue(run(e, 120).isEmpty())
    }
    @Test fun `same episode keeps session and changing episodes keeps requested speed`() {
        val key = "speed-session-${System.nanoTime()}"
        val first = ChatSessions.obtain(key, comments, 150, ChatIntensity.NORMAL, false, true, seed = 42, speed = 0.5f)
        first.setRealOnly(true)
        val shown = run(first, 30).size
        val same = ChatSessions.obtain(key, comments, 150, ChatIntensity.NORMAL, false, true, speed = 2f)
        assertSame(first, same)
        assertEquals(shown.toLong(), same.messagesIssued)
        val next = ChatSessions.obtain("$key-next", comments, 150, ChatIntensity.NORMAL, false, true, seed = 42, speed = 2f)
        next.setRealOnly(true)
        assertNotEquals(first.id, next.id)
        assertEquals(run(engine(2f), 60).size, run(next, 60).size)
    }
    @Test fun `speed is live persisted and backwards compatible without changing other settings`() {
        val file = Files.createTempDirectory("chat-speed").resolve("state.json").toFile()
        file.writeText("""{"chatIntensity":"quiet","chatFontSize":16,"chatSide":"left"}""")
        val settings = AppSettings(file)
        assertEquals(1f, settings.state.value.chatSpeed)
        for ((speed, _) in CHAT_SPEED_PRESETS) {
            settings.setChatSpeed(speed)
            assertEquals(speed, settings.state.value.chatSpeed)
            settings.flush()
            val saved = AppSettings(file).state.value
            assertEquals(speed, saved.chatSpeed)
            assertEquals("quiet", saved.chatIntensity)
            assertEquals(16, saved.chatFontSize)
            assertEquals("left", saved.chatSide)
        }
    }
    @Test fun `invalid speeds never break timing or persistence`() {
        assertEquals(1f, normalizeChatSpeed(Float.NaN))
        assertEquals(1f, normalizeChatSpeed(Float.POSITIVE_INFINITY))
        assertEquals(0.5f, normalizeChatSpeed(-1f))
        assertEquals(2f, normalizeChatSpeed(100f))
        val settings = AppSettings(Files.createTempDirectory("chat-speed-invalid").resolve("state.json").toFile())
        settings.setChatSpeed(Float.NaN)
        settings.flush()
        assertEquals(1f, settings.state.value.chatSpeed)
        assertTrue(run(engine(Float.NaN), 10).isNotEmpty())
    }

    @Test fun `long real paragraphs retain reading time at half speed`() {
        val paragraph = "Длинный комментарий о сюжете и героях. ".repeat(20)
        val real = comments.take(10).map { it.copy(message = paragraph + it.id) }
        val e = ChatEngine("reading", real.size, 500, ChatIntensity.STORM, true, real, 42, .5f)
        e.setRealOnly(true)
        e.accumulate(3_000)
        val first = poll(e).single()
        val reading = chatReadingTimeMs(first.text, .5f)
        assertTrue(reading > 60_000)
        e.accumulate(reading - 1)
        assertTrue(poll(e).isEmpty())
        e.accumulate(1)
        assertEquals(1, poll(e).size)
    }

    @Test fun `reading and scroll motion both respect speed without changing video clock`() {
        val text = "Текст ".repeat(30)
        assertEquals(chatReadingTimeMs(text, 1f) * 2, chatReadingTimeMs(text, .5f))
        assertEquals(900, chatScrollDurationMs(.5f))
        assertEquals(450, chatScrollDurationMs(1f))
        assertEquals(225, chatScrollDurationMs(2f))
    }

    @Test fun `seek pause scales down too and never flushes a batch at half speed`() {
        val e = engine(.5f, ChatIntensity.STORM, false)
        e.onSeek()
        e.accumulate(2_399)
        assertTrue(poll(e).isEmpty())
        e.accumulate(1)
        assertEquals(1, poll(e).size)
        repeat(1000) { e.accumulate(400); assertTrue(poll(e).size <= 1) }
    }

    @Test fun `switching to half speed protects the paragraph already on screen and seeking does not discard reading time`() {
        val real = comments.take(10).map { it.copy(message = "Длинное сообщение о сюжете. ".repeat(25) + it.id) }
        val e = ChatEngine("switch-reading", real.size, 500, ChatIntensity.STORM, false, real, 42, 1f)
        e.setRealOnly(true)
        e.accumulate(1_500)
        assertEquals(1, poll(e).size)
        e.retune(ChatIntensity.STORM, 500, false, .5f)
        e.onSeek()
        e.accumulate(30_000)
        assertTrue(poll(e).isEmpty())
    }
}

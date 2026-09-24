package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.desktop.AppSettings
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.*

class AutoTimingTest {
    private fun fingerprint(episode: Int, duration: Long = 1_440_000, op: Long? = 60_000, ed: Long? = duration - 120_000,
        opLength: Long = 64_000, edLength: Long = 48_000, season: String = "season", pattern: Int = 10): TimingFingerprint {
        val windows = timingWindows(duration, true, true).map { w ->
            val random = Random(episode * 101 + w.kind.hashCode())
            val audio = MutableList((w.durationMs / FINGERPRINT_STEP_MS).toInt()) { random.nextInt() }
            val visual = MutableList((w.durationMs / VISUAL_STEP_MS).toInt()) { random.nextLong() }
            val start = if (w.kind == "op") op else ed
            val size = if (w.kind == "op") opLength else edLength
            if (start != null) {
                val sound = Random(pattern + w.kind.hashCode())
                val pictures = Random(pattern + w.kind.hashCode() + 99)
                val audioStart = ((start - w.startMs) / FINGERPRINT_STEP_MS).roundToInt()
                repeat((size / FINGERPRINT_STEP_MS).toInt()) { i ->
                    val hash = sound.nextInt()
                    if (i + audioStart in audio.indices) audio[i + audioStart] = hash
                }
                val visualStart = ((start - w.startMs) / VISUAL_STEP_MS).toInt()
                repeat((size / VISUAL_STEP_MS).toInt()) { i ->
                    val hash = pictures.nextLong()
                    if (i + visualStart in visual.indices) visual[i + visualStart] = hash
                }
            }
            w.copy(audio = audio, visual = visual)
        }
        return TimingFingerprint("$season-$episode-$pattern", season, episode, duration, windows)
    }

    @Test fun `two episodes allow a manual button but four are required for automatic skip`() {
        val current = fingerprint(4)
        assertNull(AutoTimingMatcher.detect(current, emptyList(), "op"))
        val manual = assertNotNull(AutoTimingMatcher.detect(current, listOf(fingerprint(1)), "op"))
        assertEquals(2, manual.witnesses)
        assertTrue(manual.range().approximate)
        assertFalse(shouldAutoSkipOpening(manual.range(), manual.startMs, false, true))
        val high = assertNotNull(AutoTimingMatcher.detect(current, (1..3).map { fingerprint(it) }, "op"))
        assertEquals(4, high.witnesses)
        assertTrue(shouldAutoSkipOpening(high.range(), high.startMs, false, true))
    }

    @Test fun `duration-relative windows handle short medium standard and long cold opens`() {
        for ((duration, start, size) in listOf(300_000L to 20_000L, 720_000L to 80_000L,
            1_440_000L to 0L, 1_440_000L to 180_000L, 2_880_000L to 600_000L, 3_600_000L to 900_000L)
            .map { (d, s) -> Triple(d, s, if (d < 600_000) 24_000L else 64_000L) }) {
            val current = fingerprint(4, duration, start, opLength = size)
            val peers = (1..3).map { fingerprint(it, duration, start + it * 8_000, opLength = size) }
            val range = assertNotNull(AutoTimingMatcher.detect(current, peers, "op"), "duration=$duration start=$start")
            assertTrue(range.startMs >= start && range.endMs <= start + size)
            assertTrue(range.endMs - range.startMs < duration * .22)
        }
    }

    @Test fun `missing first episode OP and missing last episode ED do not inherit another episode's offsets`() {
        val peers = (1..3).map { fingerprint(it) }
        assertNull(AutoTimingMatcher.detect(fingerprint(4, op = null), peers, "op"))
        assertNull(AutoTimingMatcher.detect(fingerprint(4, ed = null), peers, "ed"))
        assertNull(AutoTimingMatcher.detect(fingerprint(4, pattern = 444), peers, "op"))
    }

    @Test fun `ending stops inside matched credits and preserves post-credit or preview scenes`() {
        for (tail in listOf(24_000L, 80_000L, 200_000L)) {
            val endStart = 1_440_000L - tail - 64_000
            val current = fingerprint(4, ed = endStart, edLength = 64_000)
            val peers = (1..3).map { fingerprint(it, ed = endStart + it * 2_000, edLength = 64_000) }
            val detected = assertNotNull(AutoTimingMatcher.detect(current, peers, "ed"))
            assertTrue(detected.endMs <= endStart + 64_000 - 5_000)
            assertTrue(guardedSeekTarget(detected.endMs, 1_440_000) < 1_440_000 - tail)
        }
    }

    @Test fun `constant black credits silence and matching music without matching pictures cannot auto skip`() {
        val current = fingerprint(4)
        val silent = current.copy(windows = current.windows.map { it.copy(audio = it.audio.map { 0 }) })
        val black = current.copy(windows = current.windows.map { it.copy(visual = it.visual.map { 0L }) })
        val otherPictures = fingerprint(4, pattern = 77)
        val musicOnly = current.copy(windows = current.windows.zip(otherPictures.windows).map { (a, b) -> a.copy(visual = b.visual) })
        for (input in listOf(silent, black, musicOnly)) assertNull(AutoTimingMatcher.detect(input, (1..3).map { fingerprint(it) }, "op"))
    }

    @Test fun `ninety seconds is not a default OP for a five minute episode`() {
        val duration = 300_000L
        assertNull(AutoTimingMatcher.detect(fingerprint(4, duration, 0, opLength = 90_000),
            (1..3).map { fingerprint(it, duration, 0, opLength = 90_000) }, "op"))
    }

    @Test fun `releases seasons and duplicate renditions never lend confidence to each other`() {
        val current = fingerprint(4)
        assertNull(AutoTimingMatcher.detect(current, (1..3).map { fingerprint(it, season = "other") }, "op"))
        val peers = List(6) { fingerprint(1).copy(key = "quality-$it") }
        assertTrue(assertNotNull(AutoTimingMatcher.detect(current, peers, "op")).confidence < AUTO_TIMING_HIGH)
        val context = AutoTimingContext("title", 4, "source", "dub")
        val key = context.videoKey("https://a/video.m3u8?release=1", 1_440_000)
        assertNotEquals(key, context.copy(source = "other").videoKey("https://a/video.m3u8?release=1", 1_440_000))
        assertNotEquals(key, context.copy(dub = "other").videoKey("https://a/video.m3u8?release=1", 1_440_000))
        assertNotEquals(key, context.videoKey("https://a/video.m3u8?release=2", 1_440_000))
        assertNotEquals(key, context.copy(titleId = "season2").videoKey("https://a/video.m3u8?release=1", 1_440_000))
    }

    @Test fun `exact human timing wins independently for each range including when it arrives late`() {
        val human = OpeningRange(0, 90_000)
        val auto = DetectedInterval(50_000, 110_000, .96, 4).range()
        assertEquals(human, preferredSkipRange(human, auto))
        assertEquals(auto, preferredSkipRange(null, auto))
        assertEquals(auto, preferredSkipRange(human.copy(approximate = true), auto))
        assertEquals(human.copy(approximate = true), preferredSkipRange(human.copy(approximate = true), null))
        assertNull(preferredSkipRange(null, auto.copy(confidence = .4)))
        assertFalse(needsAutoTiming(human))
        assertTrue(needsAutoTiming(null))
    }

    @Test fun `automatic OP ED honor separate toggles resume manual rewind and valid range boundaries`() {
        val op = DetectedInterval(10_000, 90_000, .96, 4).range()
        val ed = op.copy(startMs = 1_300_000, endMs = 1_400_000)
        assertTrue(shouldAutoSkipOpening(op, 20_000, false, true))
        assertFalse(shouldAutoSkipOpening(ed, 1_350_000, false, false))
        assertFalse(shouldAutoSkipOpening(op, 20_000, true, true))
        assertFalse(shouldAutoSkipOpening(op, 91_000, false, true))
        assertFalse(shouldAutoSkipOpening(op.copy(confidence = .85), 20_000, false, true))
        assertFalse(shouldShowOpeningButton(op.copy(approximate = true), 0))
        assertTrue(shouldShowOpeningButton(op, 20_000))
        assertNull(openingSkipTarget(ed, 1_400_000))
    }

    @Test fun `independent settings persist and older files do not silently enable background downloads`() {
        val file = Files.createTempDirectory("timing-settings").resolve("state.json").toFile()
        file.writeText("""{"autoSkipOpening":true,"autoSkipEnding":false}""")
        val settings = AppSettings(file)
        assertFalse(settings.state.value.autoDetectTimings)
        settings.setAutoDetectTimings(true)
        settings.flush()
        val restored = AppSettings(file).state.value
        assertTrue(restored.autoDetectTimings)
        assertTrue(restored.autoSkipOpening)
        assertFalse(restored.autoSkipEnding)
    }

    @Test fun `cache survives restart avoids downloads and improves old episode confidence with new witnesses`() = runBlocking {
        val file = Files.createTempDirectory("timing-cache").resolve("timings.json").toFile()
        var calls = 0
        val extractor = TimingExtractor { url, _, window ->
            calls++
            fingerprint(url.toInt()).windows.first { it.kind == window.kind }
        }
        val service = AutoTimingService(file, extractor)
        suspend fun resolve(s: AutoTimingService, ep: Int, source: String = "source") = s.resolve(
            AutoTimingContext("title", ep, source, "dub"), ep.toString(), null, 1_440_000, true, true)
        assertNull(service.resolve(AutoTimingContext("title", 1, "source", "dub"), "1", null, 1_440_000,
            true, true, cachedOnly = true).opening)
        assertEquals(0, calls)
        assertNull(resolve(service, 1).opening)
        assertNotNull(resolve(service, 2).opening)
        resolve(service, 3)
        assertFalse(assertNotNull(resolve(service, 4).opening).approximate)
        assertEquals(8, calls)
        val restored = AutoTimingService(file, extractor)
        assertFalse(assertNotNull(resolve(restored, 1).opening).approximate)
        assertEquals(8, calls)
        repeat(10) { resolve(restored, 1) }
        assertEquals(8, calls)
        assertNull(resolve(restored, 1, "new-release").opening)
        assertEquals(10, calls)
        assertFalse(file.readText().contains("https://"))
    }

    @Test fun `absent FFmpeg or unsupported stream fails closed with a retry cooldown`() = runBlocking {
        val file = Files.createTempDirectory("timing-failure").resolve("timings.json").toFile()
        var calls = 0
        val service = AutoTimingService(file, TimingExtractor { _, _, _ -> calls++; error("no decoder") })
        repeat(5) { assertNull(service.resolve(AutoTimingContext("x", 1, "s", "d"), "url", null, 1_440_000, true, false).opening) }
        assertEquals(1, calls)
        assertFalse(file.exists())
    }

    @Test fun `cancellation does not cache partial media and no requested analysis means no extraction`() = runBlocking {
        val file = Files.createTempDirectory("timing-cancel").resolve("timings.json").toFile()
        var calls = 0
        val service = AutoTimingService(file, TimingExtractor { _, _, _ -> calls++; delay(10_000); error("cancel expected") })
        val context = AutoTimingContext("x", 1, "s", "d")
        service.resolve(context, "url", null, 1_440_000, false, false)
        service.resolve(context.copy(episode = 0), "url", null, 1_440_000, true, true)
        assertEquals(0, calls)
        withTimeoutOrNull(100) { service.resolve(context, "url", null, 1_440_000, true, true) }
        assertEquals(1, calls)
        assertFalse(file.exists())
    }
}

package com.aniblaze.desktop.player

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Optional real-decoder integration; set ANIBLAZE_TEST_FFMPEG=1. No external streams or paid APIs. */
class AutoTimingFfmpegTest {
    @Test fun `real decoded repeated audio and pictures locate shifted openings without eating a different tail`() = runBlocking {
        if (System.getenv("ANIBLAZE_TEST_FFMPEG") != "1") return@runBlocking
        val dir = Files.createTempDirectory("timing-fixture-").toFile()
        val ffmpeg = findTimingFfmpeg()
        val extractor = FfmpegTimingExtractor(ffmpeg)
        try {
            val fingerprints = (1..4).map { ep ->
                val prefix = ep * 4
                val file = File(dir, "$ep.mkv")
                val log = File(dir, "$ep.log")
                // Different cold open and post-credit picture; the 40s OP is the only shared sequence.
                val audio = "aevalsrc=0.15*sin(2*PI*(220+55*floor(t/2))*t)+0.12*sin(2*PI*(440+73*floor(t/3))*t)+0.10*sin(2*PI*880*t):s=11025:d=40"
                val args = listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y", "-filter_complex_threads", "1",
                    "-f", "lavfi", "-i", "color=c=black:s=160x96:r=12:d=$prefix",
                    "-f", "lavfi", "-i", "anullsrc=r=11025:cl=mono:d=$prefix",
                    "-f", "lavfi", "-i", "testsrc2=s=160x96:r=12:d=40",
                    "-f", "lavfi", "-i", audio,
                    "-f", "lavfi", "-i", "color=c=blue:s=160x96:r=12:d=${90 - prefix - 40}",
                    "-f", "lavfi", "-i", "anullsrc=r=11025:cl=mono:d=${90 - prefix - 40}",
                    "-filter_complex", "[0:v][1:a][2:v][3:a][4:v][5:a]concat=n=3:v=1:a=1[v][a]",
                    "-map", "[v]", "-map", "[a]", "-c:v", "ffv1", "-threads", "1", "-c:a", "pcm_s16le", file.absolutePath)
                val process = ProcessBuilder(args).redirectError(log).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                try {
                    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fixture timeout")
                    assertEquals(0, process.exitValue(), log.readText().take(2000))
                } finally { if (process.isAlive) process.destroyForcibly() }
                val window = extractor.extract(file.absolutePath, null, TimingWindow("op", 0, 90_000))
                assertTrue(window.audio.size > 300)
                assertTrue(window.visual.distinct().size >= 8)
                TimingFingerprint("video-$ep", "fixture-season", ep, 720_000, listOf(window))
            }
            val current = fingerprints.last()
            val detected = assertNotNull(AutoTimingMatcher.detect(current, fingerprints.dropLast(1), "op"))
            assertTrue(detected.startMs >= 16_000, "must not consume cold open: $detected")
            assertTrue(detected.endMs <= 56_000, "must not consume story after OP: $detected")
            assertTrue(detected.endMs - detected.startMs >= 16_000, "must find an actual useful repeat: $detected")
            assertTrue(detected.confidence >= AUTO_TIMING_HIGH, "$detected")
        } finally {
            // The fixture directory contains only files created above.
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }
}

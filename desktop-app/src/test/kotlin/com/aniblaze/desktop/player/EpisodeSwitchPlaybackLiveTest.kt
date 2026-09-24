@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.source.AnixartSource
import com.aniblaze.aggregator.source.KodikExtractor
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.HttpClient
import com.aniblaze.desktop.ui.AniBlazeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Own JVM, real native playback, no window or user settings mutations. */
class EpisodeSwitchPlaybackLiveTest {
    @Test fun `actual remote stream advances through the production renderer from saved position`() = runBlocking {
        val httpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).build()
        val source = AnixartSource(HttpClient(httpClient), KodikExtractor(httpClient), SettingsDataStore())
        val contentId = System.getenv("ANIBLAZE_LIVE_TITLE") ?: "ax:1152"
        val episode = System.getenv("ANIBLAZE_LIVE_EPISODE")?.toInt() ?: 10
        val resume = System.getenv("ANIBLAZE_LIVE_RESUME_MS")?.toLong() ?: 473_765L
        val result = assertNotNull(source.extractContent(contentId, episode))
        val variants = result.variants?.takeIf { it.isNotEmpty() }
            ?: listOf(StreamVariant(result.quality.ifBlank { "Auto" }, result.location))
        val checkpoints = java.util.concurrent.CopyOnWriteArrayList<PlaybackCheckpoint>()
        var refreshes = 0
        val scene = CanvasLayersComposeScene(size = IntSize(640, 360), coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(640, 360)
        scene.setContent {
            AniBlazeTheme {
                VlcPlayerView(variants, result.referer, Modifier.fillMaxSize(), startPositionMs = resume,
                    initialVolume = 0, composeVideo = true, autoDetectTimings = false,
                    mediaKey = "$contentId:$episode", onProgress = { checkpoints.add(it) },
                    onStreamDead = { refreshes++ })
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55)
            while (checkpoints.lastOrNull()?.positionMs?.let { it >= resume + 12_000 } != true && System.nanoTime() < deadline) {
                delay(25)
                scene.render(surface.canvas.asComposeCanvas(), System.nanoTime())
            }
            assertTrue(checkpoints.lastOrNull()?.positionMs?.let { it >= resume + 12_000 } == true,
                "Remote stream did not advance: $checkpoints; refreshes=$refreshes")
            println("Production renderer HLS: $contentId episode $episode, " +
                "positions=${checkpoints.map { it.positionMs }}, refreshes=$refreshes")
            // This HLS has 6s segments; libVLC starts decoding before the requested
            // point (measured 6.946s of preroll). Do not mistake decoder preroll for
            // losing the resume. Require it to stay within two segments and advance
            // past the requested point, without seeking/falling back to the start.
            assertTrue(checkpoints.all { it.mediaKey == "$contentId:$episode" && it.positionMs >= resume - 12_000 },
                "Resume was not retained: $checkpoints")
            assertTrue(checkpoints.zipWithNext().all { (a, b) -> b.positionMs >= a.positionMs },
                "Playback jumped backwards: $checkpoints")
        } finally { scene.close(); surface.close(); httpClient.connectionPool.evictAll() }
    }

    @Test fun `actual player switches from old credits to unseen zero then own resume`() = runBlocking {
        val directory = Files.createTempDirectory("aniblaze-episode-native-")
        val video = directory.resolve("episode9.mp4")
        val output = directory.resolve("ffmpeg.log").toFile()
        val encoder = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=160x96:rate=10:duration=120",
            "-c:v", "mpeg4", "-q:v", "8", "-movflags", "+faststart", video.toString())
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(encoder.waitFor(30, TimeUnit.SECONDS), "FFmpeg fixture timed out")
            assertEquals(0, encoder.exitValue(), output.readText())
        } finally { if (encoder.isAlive) encoder.destroyForcibly() }
        val urls = (9..17).associateWith { episode ->
            val path = directory.resolve("episode$episode.mp4")
            if (path != video) Files.copy(video, path)
            path.toUri().toString()
        }
        assertTrue(VlcSupport.available, "Real libVLC is required for this task")
        var episode by mutableStateOf(9)
        var lateOpening by mutableStateOf(false)
        val progress = mutableListOf<PlaybackCheckpoint>()
        val ended = mutableListOf<Any?>()
        val scene = CanvasLayersComposeScene(size = IntSize(640, 360), coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(640, 360)
        scene.setContent {
            AniBlazeTheme {
                VlcPlayerView(
                    variants = listOf(StreamVariant("test", urls.getValue(episode))), referer = null,
                    modifier = Modifier.fillMaxSize(), initialVolume = 0,
                    mediaKey = "test:$episode", activeEpisode = episode,
                    startPositionMs = when (episode) {
                        9, 16 -> 85_000; 11 -> 30_000; 13 -> 5_000; 14 -> 10_000
                        15 -> 40_000; 17 -> 115_000; else -> 0
                    },
                    openingRange = if (episode in listOf(12, 14, 15) || (episode == 13 && lateOpening))
                        OpeningRange(0, 20_000) else null,
                    endingRange = when (episode) {
                        10 -> OpeningRange(80_000, 110_000)
                        16 -> OpeningRange(80_000, 100_000) // 20s post-credit scene must remain
                        17 -> OpeningRange(110_000, 120_000)
                        else -> null
                    },
                    autoSkipOpening = true, autoSkipEnding = true, composeVideo = true, autoDetectTimings = false,
                    onProgress = { progress.add(it) }, onEnded = { ended.add(it) },
                )
            }
        }
        suspend fun until(label: String, seconds: Long = 25, ready: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (!ready() && System.nanoTime() < deadline) {
                delay(25)
                scene.render(surface.canvas.asComposeCanvas(), System.nanoTime())
            }
            assertTrue(ready(), "$label: progress=$progress; ended=$ended")
        }
        try {
            until("Episode 9 resume must really play") { progress.any { it.mediaKey == "test:9" && it.positionMs >= 85_000 } }
            episode = 10
            until("Unseen episode 10 must report its own timeline") { progress.any { it.mediaKey == "test:10" && it.positionMs > 0 } }
            val tenth = progress.filter { it.mediaKey == "test:10" }
            assertTrue(tenth.all { it.positionMs < 10_000 }, "Old ED triggered a seek on the unseen episode: $tenth")
            assertFalse("test:10" in ended, "A new episode must not be completed by the old clock")
            episode = 11
            until("Episode 11 must use its own saved resume") { progress.any { it.mediaKey == "test:11" && it.positionMs >= 30_000 } }
            assertTrue(progress.filter { it.mediaKey == "test:11" }.all { it.positionMs in 30_000..40_000 })
            println("Real VLC + Compose passed: 9 at 85s -> 10 from zero (${tenth.map { it.positionMs }}) -> 11 at 30s; no false end")
            episode = 12
            until("Exact OP at zero must skip, not spend its one-shot on a zero-length seek", 10) {
                progress.any { it.mediaKey == "test:12" && it.positionMs >= 20_000 }
            }
            episode = 13
            until("Late OP fixture must first start without timings") { progress.any { it.mediaKey == "test:13" && it.positionMs >= 5_000 } }
            lateOpening = true
            until("OP metadata arriving after play must trigger skip", 7) { progress.any { it.mediaKey == "test:13" && it.positionMs >= 20_000 } }
            episode = 14
            until("Resume inside exact OP must skip", 7) { progress.any { it.mediaKey == "test:14" && it.positionMs >= 20_000 } }
            episode = 15
            until("Resume after OP must remain after OP") { progress.any { it.mediaKey == "test:15" && it.positionMs >= 40_000 } }
            assertTrue(progress.filter { it.mediaKey == "test:15" }.all { it.positionMs >= 39_000 })
            episode = 16
            until("Exact ED must skip to its own end", 7) { progress.any { it.mediaKey == "test:16" && it.positionMs >= 100_000 } }
            assertFalse("test:16" in ended, "Post-credit scene must not be consumed")
            assertTrue(progress.filter { it.mediaKey == "test:16" }.all { it.positionMs < 108_000 },
                "Post-credit fixture must not cross the 90% completed threshold just by skipping ED")
            episode = 17
            until("ED ending at duration must leave a playable tail and deliver ended", 10) { "test:17" in ended }
            assertEquals(1, ended.count { it == "test:17" })
            println("Exact OP/ED passed: OP at zero, late timings, resume inside/after OP, ED with post-credits, native ended exactly once")
        } finally { scene.close(); surface.close() }
    }
}

package com.aniblaze.desktop.player

import com.aniblaze.aggregator.source.AniskipTimings.SkipTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** The extractor is injectable so cache, cancellation and errors are testable without a network. */
internal fun interface TimingExtractor {
    suspend fun extract(url: String, referer: String?, window: TimingWindow): TimingWindow
}

internal class FfmpegTimingExtractor(private val executable: String = findTimingFfmpeg()) : TimingExtractor {
    override suspend fun extract(url: String, referer: String?, window: TimingWindow): TimingWindow = withContext(Dispatchers.IO) {
        require(url.isNotBlank() && !url.startsWith("-"))
        val dir = Files.createTempDirectory("aniblaze-timings-").toFile()
        val audio = File(dir, "audio.fp")
        val video = File(dir, "video.gray")
        val errors = File(dir, "ffmpeg.log")
        var process: Process? = null
        try {
            val seconds = window.durationMs / 1000.0
            val args = mutableListOf(executable, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-threads", "1", "-filter_threads", "1", "-rw_timeout", "12000000")
            if (!referer.isNullOrBlank() && url.startsWith("http", true)) args += listOf("-referer", referer)
            args += listOf("-ss", (window.startMs / 1000.0).toString(), "-i", url,
                "-map", "0:a:0", "-t", seconds.toString(), "-ac", "1", "-ar", "11025",
                "-f", "chromaprint", "-algorithm", "1", "-fp_format", "raw", audio.absolutePath,
                "-map", "0:v:0", "-t", seconds.toString(), "-an", "-threads", "1",
                "-vf", "fps=1/2,scale=9:8,format=gray", "-pix_fmt", "gray", "-f", "rawvideo", video.absolutePath)
            process = ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(errors).start()
            check(withTimeoutOrNull(90_000) {
                while (process.isAlive) {
                    // Bounds remain useful even with an invalid/live manifest or an unexpected FFmpeg build.
                    check(audio.length() + video.length() + errors.length() < 4_000_000) { "analysis output limit" }
                    delay(200)
                }
                true
            } == true) { "analysis timeout" }
            check(process.exitValue() == 0) { "FFmpeg analysis unavailable (${process.exitValue()})" }
            check(audio.length() in 4..1_000_000 && video.length() in 72..1_000_000) { "empty media fingerprints" }
            val raw = ByteBuffer.wrap(audio.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val fingerprints = List(raw.remaining() / 4) { raw.int }
            val pixels = video.readBytes()
            val pictures = List(pixels.size / 72) { frame ->
                var hash = 0L
                for (y in 0..7) for (x in 0..7) {
                    val base = frame * 72 + y * 9 + x
                    if ((pixels[base].toInt() and 255) > (pixels[base + 1].toInt() and 255)) {
                        hash = hash or (1L shl (y * 8 + x))
                    }
                }
                hash
            }
            window.copy(audio = fingerprints, visual = pictures)
        } finally {
            process?.let { if (it.isAlive) { it.destroyForcibly(); it.waitFor(500, TimeUnit.MILLISECONDS) } }
            // Only explicit files in our own generated temp directory; never a user/media directory.
            audio.delete(); video.delete(); errors.delete(); dir.delete()
        }
    }
}

internal fun findTimingFfmpeg(): String {
    System.getenv("ANIBLAZE_FFMPEG")?.takeIf { File(it).isFile }?.let { return it }
    val executable = if (System.getProperty("os.name").startsWith("Windows")) "ffmpeg.exe" else "ffmpeg"
    val paths = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, executable) }
    return paths.firstOrNull { it.isFile }?.absolutePath ?: executable
}

@Serializable
private data class CachedDetection(val signature: String, val op: DetectedInterval? = null, val ed: DetectedInterval? = null)
@Serializable
private data class TimingCache(
    val version: Int = AUTO_TIMING_VERSION,
    val fingerprints: List<TimingFingerprint> = emptyList(),
    val detections: Map<String, CachedDetection> = emptyMap(),
)

/** One background job, bounded cache, no API/AI and no changes to the progress store. */
internal class AutoTimingService(
    private val file: File,
    private val extractor: TimingExtractor = FfmpegTimingExtractor(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false
    private var cache = TimingCache()
    private val failures = mutableMapOf<String, Long>()

    suspend fun resolve(context: AutoTimingContext, url: String, referer: String?, durationMs: Long,
        opening: Boolean, ending: Boolean, cachedOnly: Boolean = false): SkipTimings = withContext(Dispatchers.IO) {
        lock.withLock {
            val windows = timingWindows(durationMs, opening, ending)
            if (context.episode <= 0 || windows.isEmpty()) return@withLock SkipTimings.EMPTY
            load()
            val key = context.videoKey(url, durationMs)
            if ((failures[key] ?: 0L) > now()) return@withLock SkipTimings.EMPTY
            try {
                var current = cache.fingerprints.firstOrNull { it.key == key }
                val needed = windows.filter { w -> current?.windows?.any { it.kind == w.kind } != true }
                if (cachedOnly && needed.isNotEmpty()) return@withLock SkipTimings.EMPTY
                if (needed.isNotEmpty()) {
                    val extracted = needed.map { extractor.extract(url.substringBefore("#h="), referer, it) }
                    current = TimingFingerprint(key, context.seasonKey(), context.episode, durationMs,
                        current?.windows.orEmpty() + extracted, now())
                    val all = (listOf(current) + cache.fingerprints.filterNot { it.key == key })
                        .filter { now() - it.createdAt < 180L * 24 * 60 * 60 * 1000 }
                    val perSeason = mutableMapOf<String, Int>()
                    val bounded = all.filter { fp ->
                        val count = perSeason.getOrDefault(fp.season, 0)
                        perSeason[fp.season] = count + 1
                        count < 12
                    }.take(64)
                    cache = cache.copy(fingerprints = bounded, detections = cache.detections.filterKeys { k -> bounded.any { it.key == k } })
                }
                val fingerprint = current ?: return@withLock SkipTimings.EMPTY
                val peers = cache.fingerprints.filter { it.season == fingerprint.season && it.episode != fingerprint.episode }
                val signature = timingHash((listOf(key) + fingerprint.windows.map { it.kind } + peers.map { it.key }).joinToString("|"))
                val cached = cache.detections[key]?.takeIf { it.signature == signature }
                val detection = cached ?: CachedDetection(signature,
                    AutoTimingMatcher.detect(fingerprint, peers, "op"), AutoTimingMatcher.detect(fingerprint, peers, "ed"))
                if (cached == null || needed.isNotEmpty()) {
                    cache = cache.copy(detections = cache.detections + (key to detection))
                    persist()
                    PlayerDiagnostics.log("timings.auto", "episode=${context.episode}; witnesses=${peers.distinctBy { it.episode }.size}; " +
                        "op=${detection.op?.confidence}; ed=${detection.ed?.confidence}")
                }
                SkipTimings(if (opening) detection.op?.range() else null, if (ending) detection.ed?.range() else null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // No URL, referrer or raw FFmpeg error (may contain signed credentials) in logs.
                failures[key] = now() + 15 * 60_000
                if (failures.size > 128) failures.entries.removeIf { it.value <= now() }
                PlayerDiagnostics.log("timings.autoUnavailable", "kind=${error.javaClass.simpleName}")
                SkipTimings.EMPTY
            }
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        cache = runCatching {
            if (!file.isFile || file.length() > 24_000_000) TimingCache()
            else json.decodeFromString<TimingCache>(file.readText()).takeIf { it.version == AUTO_TIMING_VERSION } ?: TimingCache()
        }.getOrElse { TimingCache() }
    }

    private fun persist() {
        file.parentFile.mkdirs()
        val temp = Files.createTempFile(file.parentFile.toPath(), "timings-", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(cache))
            try { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temp) }
    }

    companion object {
        val shared by lazy {
            AutoTimingService(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AniBlaze/auto-timings-v1.json"))
        }
    }
}

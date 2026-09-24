package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.OpeningRange
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val AUTO_TIMING_VERSION = 1
internal const val AUTO_TIMING_HIGH = 0.95
internal const val FINGERPRINT_STEP_MS = 1365.0 * 1000 / 11025 // Chromaprint algorithm 1
internal const val VISUAL_STEP_MS = 2000L

/** Catalog entries already identify seasons; never merge them by a fuzzy title. */
data class AutoTimingContext(val titleId: String, val episode: Int, val source: String, val dub: String)

internal fun timingHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun AutoTimingContext.seasonKey() = timingHash("$titleId\u0000$source\u0000$dub")

/** Exact URL (including release/auth query) is deliberately conservative: cache misses beat wrong offsets. */
internal fun AutoTimingContext.videoKey(url: String, durationMs: Long) =
    timingHash("$AUTO_TIMING_VERSION|${seasonKey()}|$episode|$url|${durationMs / 1000}")

@Serializable
internal data class TimingWindow(
    val kind: String, val startMs: Long, val durationMs: Long,
    val audio: List<Int> = emptyList(), val visual: List<Long> = emptyList(),
)

@Serializable
internal data class TimingFingerprint(
    val key: String, val season: String, val episode: Int, val durationMs: Long,
    val windows: List<TimingWindow>, val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
internal data class DetectedInterval(val startMs: Long, val endMs: Long, val confidence: Double, val witnesses: Int) {
    fun range() = OpeningRange(startMs, endMs, approximate = confidence < AUTO_TIMING_HIGH,
        autoDetected = true, confidence = confidence)
}

/** Relative search areas, NOT proposed skip intervals. No fallback such as 'first 90 seconds'. */
internal fun timingWindows(durationMs: Long, opening: Boolean, ending: Boolean): List<TimingWindow> {
    if (durationMs !in 90_000L..10_800_000L) return emptyList()
    return buildList {
        if (opening) add(TimingWindow("op", 0, (durationMs * .40).toLong().coerceAtMost(1_200_000)))
        if (ending) {
            val size = (durationMs * .30).toLong().coerceAtMost(900_000)
            add(TimingWindow("ed", durationMs - size, size))
        }
    }
}

internal fun preferredSkipRange(human: OpeningRange?, detected: OpeningRange?): OpeningRange? {
    // Borrowed season timings remain a manual fallback, not exact timings for THIS episode.
    val real = human?.takeIf { it.isValid && !it.approximate && !it.autoDetected }
    return real ?: detected?.takeIf { it.isValid && it.autoDetected && it.confidence >= .80 }
        ?: human?.takeIf { it.isValid }
}

internal fun needsAutoTiming(range: OpeningRange?) = range == null || !range.isValid || range.approximate || range.autoDetected

private data class PairMatch(val start: Long, val end: Long, val quality: Double)

/**
 * Conservative repeated-sequence detector. A logo, black credits or recurring music alone
 * cannot produce a range. Both time-aligned audio fingerprints and varied pictures must agree.
 * Two episodes => manual only; four independent episodes with matching boundaries => auto.
 * Every release is measured independently, including cold-open offsets.
 */
internal object AutoTimingMatcher {
    fun detect(current: TimingFingerprint, peers: List<TimingFingerprint>, kind: String): DetectedInterval? {
        val window = current.windows.firstOrNull { it.kind == kind } ?: return null
        val matches = peers.asSequence().filter { it.season == current.season && it.episode != current.episode }
            .distinctBy { it.episode }.take(7).mapNotNull { peer ->
                peer.windows.firstOrNull { it.kind == kind }?.let { match(window, it, current.durationMs) }
            }.toList()
        if (matches.isEmpty()) return null
        // Different OP versions or uncertain borders cannot increase confidence by voting.
        val cluster = matches.map { center -> matches.filter {
            abs(it.start - center.start) <= 4_000 && abs(it.end - center.end) <= 4_000
        } }.maxByOrNull { it.size } ?: return null
        val start = cluster.maxOf { it.start } + 3_000
        val end = cluster.minOf { it.end } - 5_000 // leave the last seconds of ED, never eat post-credits
        if (end - start < 8_000) return null
        val high = cluster.size >= 3 && cluster.all { it.quality >= .94 }
        return DetectedInterval(start, end, if (high) .96 else .85, cluster.size + 1)
    }

    private fun match(a: TimingWindow, b: TimingWindow, episodeDuration: Long): PairMatch? {
        if (a.audio.size < 80 || b.audio.size < 80 || a.visual.size < 8 || b.visual.size < 8) return null
        // Sparse votes avoid an expensive whole-media audio cross-correlation.
        val offsets = HashMap<Int, Int>()
        for (i in a.visual.indices step 2) {
            val hash = a.visual[i]
            if (!varied(hash)) continue
            for (j in b.visual.indices) {
                if (varied(b.visual[j]) && java.lang.Long.bitCount(hash xor b.visual[j]) <= 8) {
                    offsets[j - i] = (offsets[j - i] ?: 0) + 1
                }
            }
        }
        val minLength = if (episodeDuration < 600_000) 16_000L else 24_000L
        val maxLength = minOf(240_000L, (episodeDuration * .22).toLong())
        var best: PairMatch? = null
        for (offset in offsets.entries.sortedByDescending { it.value }.take(8).map { it.key }) {
            val visualMatches = a.visual.indices.filter { i ->
                val j = i + offset
                j in b.visual.indices && varied(a.visual[i]) && varied(b.visual[j]) &&
                    java.lang.Long.bitCount(a.visual[i] xor b.visual[j]) <= 10
            }
            if (visualMatches.size < 8) continue
            // Refine the 2-second visual offset at Chromaprint resolution.
            val rough = (offset * VISUAL_STEP_MS / FINGERPRINT_STEP_MS).roundToInt()
            val audioOffset = (rough - 16..rough + 16).minByOrNull { shift ->
                visualMatches.take(80).sumOf { i -> audioDistance(a, b, i, shift) }
            } ?: continue
            var runStart = -1
            var qualitySum = 0.0
            fun finish(last: Int) {
                if (runStart < 0) return
                val duration = (last - runStart + 1) * VISUAL_STEP_MS
                if (duration in minLength..maxLength &&
                    a.visual.subList(runStart, last + 1).distinct().size >= 8) {
                    val candidate = PairMatch(a.startMs + runStart * VISUAL_STEP_MS,
                        a.startMs + (last + 1) * VISUAL_STEP_MS, qualitySum / (last - runStart + 1))
                    if (best == null || candidate.end - candidate.start > best!!.end - best!!.start) best = candidate
                }
                runStart = -1
                qualitySum = 0.0
            }
            for (i in a.visual.indices) {
                val j = i + offset
                val visualDistance = if (j in b.visual.indices) java.lang.Long.bitCount(a.visual[i] xor b.visual[j]) else 64
                val audioDistance = audioDistance(a, b, i, audioOffset)
                if (j in b.visual.indices && varied(a.visual[i]) && visualDistance <= 10 && audioDistance <= 5.0) {
                    if (runStart < 0) runStart = i
                    qualitySum += 1 - (visualDistance / 64.0 + audioDistance / 32.0) / 2
                } else finish(i - 1)
            }
            finish(a.visual.lastIndex)
        }
        return best
    }

    private fun varied(hash: Long) = java.lang.Long.bitCount(hash) in 8..56

    private fun audioDistance(a: TimingWindow, b: TimingWindow, visualIndex: Int, shift: Int): Double {
        val index = (visualIndex * VISUAL_STEP_MS / FINGERPRINT_STEP_MS).roundToInt()
        if (index !in a.audio.indices || index + shift !in b.audio.indices) return 32.0
        // Reject constant fingerprints (silence); compare a small neighbourhood too.
        val indices = (index..index + 3).filter { it in a.audio.indices && it + shift in b.audio.indices }
        if (indices.size < 4 || indices.map { a.audio[it] }.distinct().size < 2) return 32.0
        return indices.map { java.lang.Integer.bitCount(a.audio[it] xor b.audio[it + shift]) }.average()
    }
}

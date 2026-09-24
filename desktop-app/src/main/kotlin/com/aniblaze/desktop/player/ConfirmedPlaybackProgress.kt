package com.aniblaze.desktop.player

/**
 * Confirmed native timeline only. A displayed seek target must never complete an episode.
 * The resume checkpoint survives a stalled poll; watch time only accrues between moving,
 * playing native samples (not seeks, pauses, buffering or a newly opened stream).
 */
internal class ConfirmedPlaybackProgress {
    private data class Sample(val key: Any, val position: Long, val at: Long, val playing: Boolean)
    private var sample: Sample? = null
    private var checkpoint: PlaybackCheckpoint? = null
    private var watchMs = 0L

    @Synchronized fun observe(key: Any?, position: Long, duration: Long, playing: Boolean,
        atNanos: Long, seekInFlight: Boolean = false) {
        if (seekInFlight || key == null || position <= 0 || duration <= 0) {
            sample = null
            return
        }
        val old = sample
        val elapsed = old?.let { (atNanos - it.at) / 1_000_000 } ?: 0L
        val advance = position - (old?.position ?: position)
        if (old?.key == key && old.playing && playing && elapsed in 1..5_000 &&
            advance > 0 && advance <= elapsed * 4 + 2_000) {
            watchMs += elapsed
        }
        checkpoint = PlaybackCheckpoint(key, position, duration)
        sample = Sample(key, position, atNanos, playing)
    }

    @Synchronized fun interrupt() { sample = null }
    @Synchronized fun reset() { sample = null; checkpoint = null; watchMs = 0 }
    @Synchronized fun drain(): PlaybackCheckpoint? {
        val saved = checkpoint?.copy(watchedDeltaMs = watchMs)
        if (saved != null) watchMs = 0
        return saved
    }
}

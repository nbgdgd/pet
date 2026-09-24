package com.aniblaze.desktop.player

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A media id alone cannot distinguish two consecutive opens of the same episode. */
internal data class PlaybackEpoch(val mediaKey: Any?, val serial: Long)

/** Shared by the UI and the single VLC executor. Late replies never become a new episode's state. */
internal class PlaybackEpochGuard {
    private val serial = AtomicLong()
    private val requested = AtomicReference<PlaybackEpoch?>()
    private val active = AtomicReference<PlaybackEpoch?>()

    fun prepare(key: Any?): PlaybackEpoch = PlaybackEpoch(key, serial.incrementAndGet()).also(requested::set)
    fun invalidate() { requested.set(null) }
    fun isRequested(epoch: PlaybackEpoch?) = epoch != null && requested.get() == epoch
    fun activate(epoch: PlaybackEpoch): Boolean {
        if (!isRequested(epoch)) return false
        active.set(epoch)
        return true
    }
    fun activeEpoch(): PlaybackEpoch? = active.get()
    fun accepts(epoch: PlaybackEpoch?, expectedKey: Any?): Boolean =
        epoch != null && epoch.mediaKey == expectedKey && isRequested(epoch) && active.get() == epoch
}

internal fun previousEpisodeCheckpoint(previousKey: Any?, nextKey: Any?, position: Long, duration: Long): PlaybackCheckpoint? =
    if (previousKey != null && previousKey != nextKey && position > 0 && duration > 0)
        PlaybackCheckpoint(previousKey, position, duration) else null

internal fun canAnalyzeTimings(paused: Boolean, playing: Boolean, ended: Boolean, currentMediaReady: Boolean) =
    paused && !playing && !ended && currentMediaReady

/** An opening starting at zero must wait until VLC can actually accept setTime. */
internal fun canAutoSkipMedia(currentMediaReady: Boolean, durationMs: Long, seekable: Boolean,
    playing: Boolean, paused: Boolean): Boolean =
    currentMediaReady && durationMs > 0 && seekable && (playing || paused)

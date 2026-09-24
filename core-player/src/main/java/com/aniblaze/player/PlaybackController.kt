package com.aniblaze.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.CinemaTorrentFallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Immutable snapshot of the player exposed to the UI as MVI state. */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val ended: Boolean = false,
    val error: String? = null,
)

/**
 * App-wide wrapper over a single [ExoPlayer] instance. Owns the player
 * lifecycle, surfaces a [PlaybackState] flow and offers the gesture-driven
 * controls (seek ±10s, speed 0.5–2.0x) plus auto-next wiring.
 */
@Singleton
class PlaybackController @Inject constructor(
    val player: ExoPlayer,
    private val httpFactory: androidx.media3.datasource.DefaultHttpDataSource.Factory,
    private val torrentFallback: CinemaTorrentFallback,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    val torrentStatus = torrentFallback.status

    /** Callback belongs to [activeSession]; an outgoing screen must not clear the
     * callback installed by the player that replaced it during a navigation fade. */
    private var onSegmentEnded: (() -> Unit)? = null
    private var sessionCounter: Long = 0L
    private var activeSession: Long = 0L

    /** True while the player screen is on-screen — used to gate Picture-in-Picture. */
    var inPlayer: Boolean = false

    /** Non-UnstableApi accessor so other modules can check playback without opt-in. */
    fun isPlaying(): Boolean = player.isPlaying

    /** Pause the active torrent download (for cinema priority: pause when leaving player). */
    fun pauseTorrent() {
        torrentFallback.pause()
    }

    /** Resume the active torrent download. */
    fun resumeTorrent() {
        torrentFallback.resume()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                durationMs = player.duration.coerceAtLeast(0),
                ended = playbackState == Player.STATE_ENDED,
            )
            if (playbackState == Player.STATE_ENDED) onSegmentEnded?.invoke()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "playback error")
            _state.value = _state.value.copy(error = error.errorCodeName)
        }
    }

    init {
        player.addListener(listener)
    }

    /** Claims the singleton player for a newly opened screen and clears the old frame. */
    @Synchronized
    fun beginSession(): Long {
        torrentFallback.stop()
        activeSession = ++sessionCounter
        onSegmentEnded = null
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState()
        return activeSession
    }

    @Synchronized
    fun setOnSegmentEnded(sessionId: Long, callback: () -> Unit) {
        if (sessionId == activeSession) onSegmentEnded = callback
    }

    /** An old navigation entry cannot pause or clear the player that replaced it. */
    @Synchronized
    fun endSession(sessionId: Long) {
        if (sessionId != activeSession) return
        // Keep a cinema torrent alive after leaving the player: its progress and
        // controls remain available on the Downloads screen. A new player session
        // still retires it in beginSession(), so an old movie cannot leak through.
        onSegmentEnded = null
        player.pause()
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState()
    }

    @Synchronized
    fun prepare(result: ContentResult, title: String, startPositionMs: Long = 0, sessionId: Long? = null): Boolean {
        if (sessionId != null && sessionId != activeSession) {
            Timber.d("Ignoring stale stream result for session=%d, active=%d", sessionId, activeSession)
            if (result.source.equals("Torrent", ignoreCase = true)) torrentFallback.stop()
            return false
        }
        // Per-stream Referer: some CDNs (sibnet) reject requests without it. The
        // factory's defaults are read when Media3 creates the next data source,
        // so setting them right before prepare() applies to this stream only.
        httpFactory.setDefaultRequestProperties(
            result.referer?.let { mapOf("Referer" to it) } ?: emptyMap(),
        )
        val item = MediaItemFactory.from(result, title)
        player.setMediaItem(item, startPositionMs)
        player.prepare()
        player.playWhenReady = true
        _state.value = _state.value.copy(error = null, ended = false)
        return true
    }

    fun playPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs)
            .coerceIn(0, player.duration.coerceAtLeast(0))
        player.seekTo(target)
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        player.setPlaybackSpeed(clamped)
        _state.value = _state.value.copy(speed = clamped)
    }

    /** Polls the live position; called on a UI ticker while visible. */
    fun syncPosition() {
        _state.value = _state.value.copy(
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
        )
    }

    fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0)
    fun durationMs(): Long = player.duration.coerceAtLeast(0)

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}

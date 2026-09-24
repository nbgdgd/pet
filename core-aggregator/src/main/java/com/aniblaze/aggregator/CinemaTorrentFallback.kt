package com.aniblaze.aggregator

import com.aniblaze.aggregator.model.ContentResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional last-resort cinema transport.
 *
 * The contract lives in the aggregator module so the ordinary cinema source does
 * not depend on a native torrent implementation. Implementations must return null
 * when disabled, unconfigured or unavailable. It is never called for anime ids.
 */
interface CinemaTorrentFallback {
    /** Live transport telemetry shown by the player while the torrent is resolving/playing. */
    val status: StateFlow<TorrentStreamStatus>

    /** [manual] bypasses only the automatic-fallback switch after an explicit tap. */
    suspend fun resolve(identity: CinemaTorrentIdentity, manual: Boolean = false): ContentResult?

    /** Stop an obsolete local stream when another title replaces it. */
    fun stop()

    /**
     * Fully delete the known torrent with its files (explicit user delete,
     * expired pause). Unlike [stop], nothing is kept for resume.
     */
    fun delete()

    /** Controls for the active stream, also used by the Torrent downloads screen. */
    fun pause()
    fun resume()

    /**
     * Last torrent known to the engine, including a paused one whose process
     * died: enough to show it in Downloads and to re-open it on demand.
     * Null when no torrent was ever started (or it was fully deleted).
     */
    val persisted: StateFlow<PersistedTorrent?>

    /**
     * Priority for the currently open page: if the known torrent belongs to
     * [contentId] (base id, without the ":tN" translation suffix) and is
     * paused, resume it. A torrent of any other title is left untouched.
     */
    suspend fun prioritize(contentId: String)

    /** Re-open the [persisted] torrent after a process restart. False if none. */
    suspend fun resumePersisted(): Boolean
}

enum class TorrentStreamStage {
    IDLE,
    SEARCHING,
    METADATA,
    CONNECTING,
    BUFFERING,
    STREAMING,
    PAUSED,
    ERROR,
}

data class TorrentStreamStatus(
    val stage: TorrentStreamStage = TorrentStreamStage.IDLE,
    val source: String = "",
    val downloadBytesPerSecond: Long = 0L,
    val peers: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val fileName: String = "",
    val detail: String = "",
    /** Title id this stream was opened for (may carry a ":tN" suffix). */
    val contentId: String = "",
    /** Release quality label from the index ("1080p", "WEB-DL"…). */
    val quality: String = "",
    /** Torrent info-hash hex, identifies the exact release. */
    val infoHash: String = "",
)

/**
 * A torrent the engine knows about, persisted across process restarts.
 * [pausedAtMs] = 0 while streaming; otherwise the moment it was paused —
 * entries paused longer than [PAUSED_TTL_MS] are auto-deleted with their files.
 */
data class PersistedTorrent(
    val magnet: String,
    val fileIndex: Int?,
    val source: String,
    val quality: String,
    val infoHash: String,
    val contentId: String,
    val fileName: String,
    val createdAtMs: Long,
    val pausedAtMs: Long,
) {
    companion object {
        const val PAUSED_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}

data class CinemaTorrentIdentity(
    val imdbId: String,
    val isSeries: Boolean,
    val season: Int = 0,
    val episode: Int = 0,
    /** Title id the stream is opened for (used for pause/resume priority). */
    val contentId: String = "",
)

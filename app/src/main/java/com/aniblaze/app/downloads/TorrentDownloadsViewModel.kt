package com.aniblaze.app.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.CinemaTorrentFallback
import com.aniblaze.aggregator.PersistedTorrent
import com.aniblaze.aggregator.TorrentStreamStage
import com.aniblaze.aggregator.TorrentStreamStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TorrentDownloadsViewModel @Inject constructor(
    private val torrent: CinemaTorrentFallback,
) : ViewModel() {
    val status: StateFlow<TorrentStreamStatus> = torrent.status
    val persisted: StateFlow<PersistedTorrent?> = torrent.persisted

    fun pause() = torrent.pause()

    fun resume() {
        // Live paused stream resumes instantly; otherwise re-open the
        // persisted torrent (network resolve on a background thread).
        if (torrent.status.value.stage == TorrentStreamStage.PAUSED) {
            torrent.resume()
        } else {
            viewModelScope.launch { torrent.resumePersisted() }
        }
    }

    /** "Остановить и удалить": full delete with files, nothing kept. */
    fun stop() = torrent.delete()
}

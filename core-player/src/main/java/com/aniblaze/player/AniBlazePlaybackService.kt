package com.aniblaze.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps audio (and PiP video) alive when the app is backgrounded, and exposes
 * the standard system media notification / lock-screen controls. Bound to the
 * single app-wide [ExoPlayer] owned by [PlaybackController].
 */
@AndroidEntryPoint
class AniBlazePlaybackService : MediaSessionService() {

    @Inject lateinit var controller: PlaybackController

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, controller.player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            // The player is a singleton; don't release it here, just detach.
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

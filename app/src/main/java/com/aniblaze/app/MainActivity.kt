package com.aniblaze.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniblaze.app.navigation.AppNavHost
import com.aniblaze.player.PlaybackController
import com.aniblaze.settings.OnboardingScreen
import com.aniblaze.ui.theme.AniBlazeTheme
import com.aniblaze.ui.theme.OledBlack
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playbackController: PlaybackController
    @Inject lateinit var adManager: com.aniblaze.app.ads.AdManager

    /** Title id carried by a "new episode" notification tap, consumed once by the
     *  nav host. Held as state so a tap while the app is already running (which
     *  arrives via [onNewIntent], not onCreate) still navigates. */
    private val pendingContentId = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(com.aniblaze.app.sync.AggregatorSyncWorker.EXTRA_CONTENT_ID)
            ?.let { pendingContentId.value = it }
    }

    /** When minimised during playback, drop into Picture-in-Picture. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (playbackController.inPlayer && playbackController.isPlaying()) {
            enterPip()
        }
    }

    /** Enter PiP mode with remote control actions for play/pause. */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            // Add remote actions for PiP controls (play/pause, skip)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setActions(
                    listOf(
                        android.app.RemoteAction(
                            android.graphics.drawable.Icon.createWithResource(
                                this,
                                if (playbackController.isPlaying())
                                    android.R.drawable.ic_media_pause
                                else android.R.drawable.ic_media_play
                            ),
                            if (playbackController.isPlaying()) "Пауза" else "Воспроизвести",
                            if (playbackController.isPlaying()) "Пауза" else "Воспроизвести",
                            android.app.PendingIntent.getBroadcast(
                                this,
                                0,
                                android.content.Intent("com.aniblaze.ACTION_PLAY_PAUSE"),
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            ),
                        ),
                    ),
                )
            }
            runCatching { enterPictureInPictureMode(builder.build()) }
        }
    }

    /** Opt into the panel's highest refresh rate (e.g. 120 Hz) at the current resolution. */
    private fun requestHighestRefreshRate() {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val current = display?.mode ?: return
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.refreshRate > current.refreshRate + 1f) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()
        // Cold start from a notification tap.
        intent?.getStringExtra(com.aniblaze.app.sync.AggregatorSyncWorker.EXTRA_CONTENT_ID)
            ?.let { pendingContentId.value = it }
        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val fontScale by appViewModel.fontScale.collectAsStateWithLifecycle()
            val systemDensity = LocalDensity.current
            val scaledDensity = remember(systemDensity, fontScale) {
                Density(systemDensity.density, systemDensity.fontScale * fontScale)
            }
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                AniBlazeTheme {
                    Surface(Modifier.fillMaxSize(), color = OledBlack) {
                    val notifPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) {}
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    val onboarded by appViewModel.onboarded.collectAsStateWithLifecycle()
                    when (onboarded) {
                        false -> OnboardingScreen(onDone = {})
                        true -> AppNavHost(
                            playInterstitial = { onContinue ->
                                adManager.showThen(this@MainActivity, onContinue)
                            },
                            // Notification tap → that title's page.
                            openContentId = pendingContentId.value,
                            onContentIdConsumed = { pendingContentId.value = null },
                        )
                        null -> Unit // brief splash on the black surface
                    }
                    }
                }
            }
        }
    }
}

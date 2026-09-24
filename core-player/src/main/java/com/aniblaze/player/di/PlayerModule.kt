package com.aniblaze.player.di

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideTrackSelector(@ApplicationContext context: Context): DefaultTrackSelector =
        DefaultTrackSelector(context, AdaptiveTrackSelection.Factory()).apply {
            // Start adaptive streams at the best quality the network allows.
            setParameters(buildUponParameters().setForceHighestSupportedBitrate(false))
        }

    /**
     * Shared HTTP factory so [com.aniblaze.player.PlaybackController] can set the
     * per-stream Referer before prepare(): sibnet's CDN 400s without one, and
     * Media3 has no per-MediaItem headers — only factory-level defaults, which new
     * data sources pick up on creation.
     */
    @Provides
    @Singleton
    fun provideHttpDataSourceFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

    @Provides
    @Singleton
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        trackSelector: DefaultTrackSelector,
        httpFactory: DefaultHttpDataSource.Factory,
    ): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
            )
            .setTrackSelector(trackSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 50_000,
                        /* bufferForPlaybackMs = */ 1_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                    )
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
}

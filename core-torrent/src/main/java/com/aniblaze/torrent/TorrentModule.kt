package com.aniblaze.torrent

import com.aniblaze.aggregator.CinemaTorrentFallback
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TorrentModule {
    @Binds
    @Singleton
    abstract fun bindCinemaTorrentFallback(impl: CinemaTorrentFallbackImpl): CinemaTorrentFallback
}

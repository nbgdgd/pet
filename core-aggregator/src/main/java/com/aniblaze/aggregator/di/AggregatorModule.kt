package com.aniblaze.aggregator.di

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.repository.AnimeRepository
import com.aniblaze.aggregator.repository.AnimeRepositoryImpl
import com.aniblaze.aggregator.source.AniLibriaSource
import com.aniblaze.aggregator.source.AnimeOnSource
import com.aniblaze.aggregator.source.AnimeVostSource
import com.aniblaze.aggregator.source.AnixartSource
import com.aniblaze.aggregator.source.KinogoSource
import com.aniblaze.aggregator.source.KodikSource
import com.aniblaze.aggregator.source.LampaCatalogSource
import com.aniblaze.aggregator.source.RezkaSource
import com.aniblaze.aggregator.source.ShikimoriSource
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AggregatorModule {

    // Anixart: free, huge catalog, many voiceovers (озвучки) — the primary source.
    @Binds @IntoSet
    abstract fun bindAnixartSource(impl: AnixartSource): ContentAggregator

    // AniLibria: open API, no token, direct HLS — reliable single-dub fallback.
    @Binds @IntoSet
    abstract fun bindAniLibriaSource(impl: AniLibriaSource): ContentAggregator

    // AnimeVost: progressive MP4 (instant range-seek) — the player's «Источник»
    // picker resolves through it by title. Not in DEFAULT_SOURCES, so it never
    // slows the catalog merge.
    @Binds @IntoSet
    abstract fun bindAnimeVostSource(impl: AnimeVostSource): ContentAggregator

    // AnimeOn: открытый API animeon.cc по shikimori_id. Нужен ради ПОКРЫТИЯ —
    // у одной серии тут бывает до девяти озвучек, у Anixart набор другой, так что
    // «недоступна ни в одной озвучке» у Anixart здесь вполне может играть.
    @Binds @IntoSet
    abstract fun bindAnimeOnSource(impl: AnimeOnSource): ContentAggregator

    @Binds @IntoSet
    abstract fun bindShikimoriSource(impl: ShikimoriSource): ContentAggregator

    @Binds @IntoSet
    abstract fun bindKodikSource(impl: KodikSource): ContentAggregator

    // Kinogo (via the cinemar.cc balancer): films / series / cartoons for the
    // "Кино" tab. Works from outside CIS (HDrezka geo-blocks streams abroad).
    // Kept out of the anime catalog; always active for id-routing.
    @Binds @IntoSet
    abstract fun bindKinogoSource(impl: KinogoSource): ContentAggregator

    // Desktop-equivalent cinema catalog: TMDB cards + Lampa plugin balancers.
    @Binds @IntoSet
    abstract fun bindLampaCatalogSource(impl: LampaCatalogSource): ContentAggregator

    // HDrezka: cinema-only source (films/series/cartoons). Anime-merge methods
    // return empty lists, so binding it cannot pollute the anime catalog.
    @Binds @IntoSet
    abstract fun bindRezkaSource(impl: RezkaSource): ContentAggregator

    @Binds @Singleton
    abstract fun bindRepository(impl: AnimeRepositoryImpl): AnimeRepository
}

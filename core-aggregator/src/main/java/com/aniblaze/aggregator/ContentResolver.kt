package com.aniblaze.aggregator

import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.NoSourceException
import com.aniblaze.aggregator.model.StreamMode
import com.aniblaze.database.dao.LinkCacheDao
import com.aniblaze.database.entity.LinkCacheEntity
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.aggregator.source.LampaCatalogSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-source resolution strategy with a persistent link cache.
 *
 * Enabled sources (per [SettingsDataStore]) are queried concurrently; the first
 * to yield a reachable stream — in the user's configured priority order — wins.
 *
 * A fresh resolve is always attempted first: it returns the full quality list
 * for the player's picker and the freshest (non-expired) stream URLs. The
 * persistent link cache is only used as a fallback when every source fails,
 * so a flaky network still yields a previously-working link.
 */
@Singleton
class ContentResolver @Inject constructor(
    private val aggregators: Set<@JvmSuppressWildcards ContentAggregator>,
    private val linkCacheDao: LinkCacheDao,
    private val settings: SettingsDataStore,
    private val cinemaSource: LampaCatalogSource,
    private val torrentFallback: CinemaTorrentFallback,
) {

    suspend fun resolve(
        contentId: String,
        segment: Int,
        mode: StreamMode = StreamMode.AUTO,
    ): ContentResult {
        // A torrent is scoped to one player item. Starting any new resolution must
        // retire the previous local HTTP stream before an old movie can leak through.
        torrentFallback.stop()
        if (mode == StreamMode.TORRENT) {
            val identity = cinemaSource.torrentIdentity(contentId, segment)
                ?: throw NoSourceException("Торрент доступен только для раздела кино.")
            return torrentFallback.resolve(identity, manual = true)
                ?: throw NoSourceException("Торрент не найден. Проверьте адрес аддона и подключение.")
        }
        val enabled = settings.settings.first().enabledSources
        Timber.d("[Resolver] resolve id=%s seg=%d enabled=%s", contentId, segment, enabled)
        val active = aggregators
            .filter { it.name in enabled || it.alwaysActive }
            .ifEmpty {
                Timber.w("[Resolver] no matched sources, falling back to ALL: %s", aggregators.map { it.name })
                aggregators.toList()
            }
        Timber.d("[Resolver] active sources: %s", active.map { it.name })

        // Primary path: resolve fresh so we get the full quality list + live URLs.
        raceSources(active, contentId, segment)?.let { result ->
            Timber.d("[Resolver] resolved: source=%s quality=%s variants=%d", result.source, result.quality, result.variants?.size ?: 0)
            cache(result, contentId, segment)
            return result
        }

        // Fallback: a previously cached link keeps playback alive if sources fail.
        cached(active, contentId, segment)?.let {
            Timber.d("[Resolver] sources failed, using cached link via %s", it.source)
            return it
        }

        // Deliberately last and deliberately cinema-only. Torrent lookup must not
        // race ordinary providers, and anime ids can never reach this branch.
        if (mode == StreamMode.AUTO) cinemaSource.torrentIdentity(contentId, segment)?.let { identity ->
            torrentFallback.resolve(identity, manual = false)?.let { result ->
                Timber.d("[Resolver] cinema torrent fallback: %s", result.quality)
                return result // localhost URLs are session-bound and must not enter LinkCache.
            }
        }

        Timber.w("[Resolver] ALL sources failed to resolve %s #%d", contentId, segment)
        throw NoSourceException("No enabled source resolved $contentId #$segment")
    }

    private suspend fun cached(
        active: List<ContentAggregator>,
        contentId: String,
        segment: Int,
    ): ContentResult? {
        val now = now()
        for (aggregator in active) {
            val hit = linkCacheDao.get(aggregator.name, contentId, segment, now) ?: continue
            return ContentResult(
                location = hit.location,
                quality = hit.quality,
                source = hit.source,
            )
        }
        return null
    }

    private suspend fun raceSources(
        active: List<ContentAggregator>,
        contentId: String,
        segment: Int,
    ): ContentResult? = coroutineScope {
        val deferred = active.map { aggregator ->
            aggregator to async {
                runCatching { aggregator.extractContent(contentId, segment) }
                    .onFailure { Timber.w(it, "[%s] extract failed", aggregator.name) }
                    .getOrNull()
            }
        }
        // Preserve priority: await in the configured order and take the first hit.
        for ((_, job) in deferred) {
            job.await()?.let { return@coroutineScope it }
        }
        null
    }

    private suspend fun cache(result: ContentResult, contentId: String, segment: Int) {
        val ttl = if (result.isHls || result.isDash) HLS_TTL_MS else FILE_TTL_MS
        linkCacheDao.put(
            LinkCacheEntity(
                source = result.source,
                contentId = contentId,
                segment = segment,
                location = result.location,
                quality = result.quality,
                expiresAt = now() + ttl,
            ),
        )
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val HLS_TTL_MS = 6L * 60 * 60 * 1000   // 6h for adaptive manifests
        const val FILE_TTL_MS = 24L * 60 * 60 * 1000 // 24h for static files
    }
}

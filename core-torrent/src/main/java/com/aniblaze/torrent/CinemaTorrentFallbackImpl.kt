package com.aniblaze.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aniblaze.aggregator.CinemaTorrentFallback
import com.aniblaze.aggregator.CinemaTorrentIdentity
import com.aniblaze.aggregator.PersistedTorrent
import com.aniblaze.aggregator.TorrentStreamStage
import com.aniblaze.aggregator.TorrentStreamStatus
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.database.settings.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CinemaTorrentFallbackImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsDataStore,
) : CinemaTorrentFallback {
    private val _status = MutableStateFlow(TorrentStreamStatus())
    override val status: StateFlow<TorrentStreamStatus> = _status.asStateFlow()

    /** Persisted (possibly paused) torrent; initialising the engine reads it from disk. */
    override val persisted: StateFlow<PersistedTorrent?> by lazy { engine.persisted }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val index = StremioTorrentIndex(http)
    private val engineDelegate = lazy { TorrentStreamEngine(File(context.cacheDir, "cinema-torrent")) }
    private val engine by engineDelegate

    override suspend fun resolve(identity: CinemaTorrentIdentity, manual: Boolean): ContentResult? {
        return try {
            val result = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val prefs = settings.settings.first()
                    if ((!prefs.cinemaTorrentFallback && !manual) || prefs.cinemaTorrentAddonUrl.isBlank()) {
                        _status.value = TorrentStreamStatus()
                        return@withContext null
                    }
                    if (prefs.cinemaTorrentWifiOnly && !isUnmetered()) {
                        Timber.i("[Torrent] fallback skipped: current network is metered")
                        _status.value = TorrentStreamStatus(
                            stage = TorrentStreamStage.ERROR,
                            detail = "Торрент разрешён только по Wi-Fi",
                        )
                        return@withContext null
                    }
                    val primaryUrl = prefs.cinemaTorrentAddonUrl
                    // Primary + built-in reserve indexes (Comet, MediaFusion),
                    // queried CONCURRENTLY: different indexes know different
                    // hashes, so one blocked/empty index no longer means
                    // "no torrent" (matters on filtered RF networks where a
                    // single host may be unreachable).
                    val addonUrls = torrentAddonUrls(primaryUrl, prefs.cinemaTorrentExtraAddons)
                    _status.value = TorrentStreamStatus(
                        stage = TorrentStreamStage.SEARCHING,
                        source = addonSourceLabel(primaryUrl),
                        contentId = identity.contentId,
                    )
                    val found = coroutineScope {
                        addonUrls.map { url ->
                            async {
                                val label = addonSourceLabel(url)
                                runCatching {
                                    index.find(
                                        url,
                                        identity.imdbId,
                                        identity.isSeries,
                                        identity.season,
                                        identity.episode,
                                    )
                                }.onFailure {
                                    Timber.w(it, "[Torrent] addon lookup failed: %s", label)
                                }.getOrDefault(emptyList()).map { label to it }
                            }
                        }.awaitAll().flatten()
                    }
                    val candidates = mergeAddonCandidates(found)
                    if (candidates.isEmpty()) {
                        _status.value = TorrentStreamStatus(
                            stage = TorrentStreamStage.ERROR,
                            detail = "Раздача не найдена",
                        )
                    }
                    for ((source, candidate) in candidates.take(MAX_CANDIDATES)) {
                        val local = runCatching {
                            engine.open(
                                candidate.magnet(),
                                candidate.fileIndex,
                                source,
                                identity.contentId,
                                candidate.label,
                                candidate.infoHash,
                                onStatus = { status ->
                                    _status.value = status.copy(
                                        source = source,
                                        contentId = identity.contentId,
                                        quality = candidate.label,
                                        infoHash = candidate.infoHash,
                                    )
                                },
                            )
                        }
                            .onFailure { Timber.w(it, "[Torrent] candidate failed: %s", candidate.label) }
                            .getOrNull() ?: continue
                        return@withContext ContentResult(
                            location = local.url,
                            quality = candidate.label,
                            source = source,
                            metadata = mapOf(
                                "fileName" to local.fileName,
                                "fileSize" to local.fileSize.toString(),
                                "infoHash" to candidate.infoHash,
                                "source" to source,
                            ),
                            variants = listOf(StreamVariant(candidate.label, local.url)),
                        )
                    }
                    _status.value = TorrentStreamStatus(
                        stage = TorrentStreamStage.ERROR,
                        detail = "Нет пиров или поток не запустился",
                    )
                    null
                }
            }
            if (result == null) {
                if (engineDelegate.isInitialized()) engine.stop()
                if (_status.value.stage != TorrentStreamStage.ERROR) {
                    _status.value = TorrentStreamStatus(
                        stage = TorrentStreamStage.ERROR,
                        detail = "Торрент не успел запуститься",
                    )
                }
            }
            result
        } catch (cancelled: CancellationException) {
            if (engineDelegate.isInitialized()) engine.stop()
            _status.value = TorrentStreamStatus()
            throw cancelled
        }
    }

    override fun stop() {
        // Title replaced (or any new resolve): retire the live HTTP stream but
        // KEEP downloaded pieces + the persisted record. The owning page can
        // resume it via prioritize(); a 3-day pause auto-deletes it.
        if (engineDelegate.isInitialized()) engine.stop(keepData = true)
        _status.value = TorrentStreamStatus()
    }

    /** Fully delete the known torrent and its files (explicit user delete). */
    override fun delete() {
        if (engineDelegate.isInitialized()) engine.deletePersisted()
        _status.value = TorrentStreamStatus()
    }

    override fun pause() {
        if (engineDelegate.isInitialized()) engine.pause()
    }

    override fun resume() {
        if (engineDelegate.isInitialized()) engine.resume()
    }

    override suspend fun prioritize(contentId: String) {
        val base = contentId.substringBefore(":t")
        if (base.isBlank()) return
        // Live paused stream of this exact title → instant resume.
        val live = _status.value
        if (live.stage == TorrentStreamStage.PAUSED &&
            live.contentId.substringBefore(":t") == base
        ) {
            resume()
            return
        }
        // No live stream: re-open the persisted one (e.g. after the page was
        // closed or the process restarted). Existing pieces are rechecked.
        val stored = engine.persisted.value
        if (stored != null && stored.contentId.substringBefore(":t") == base) {
            Timber.i("[Torrent] prioritizing persisted torrent for %s", base)
            resumePersisted()
        }
    }

    override suspend fun resumePersisted(): Boolean {
        val stored = engine.persisted.value ?: return false
        if (stored.pausedAtMs > 0L &&
            System.currentTimeMillis() - stored.pausedAtMs > PersistedTorrent.PAUSED_TTL_MS
        ) {
            Timber.i("[Torrent] persisted torrent expired (>3d paused), deleting")
            engine.deletePersisted()
            _status.value = TorrentStreamStatus()
            return false
        }
        return try {
            val result = withTimeoutOrNull(RESUME_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    engine.open(
                        stored.magnet,
                        stored.fileIndex,
                        stored.source,
                        stored.contentId,
                        stored.quality,
                        stored.infoHash,
                        onStatus = { status ->
                            _status.value = status.copy(
                                source = stored.source,
                                contentId = stored.contentId,
                                quality = stored.quality,
                                infoHash = stored.infoHash,
                            )
                        },
                    )
                }
            }
            if (result == null) {
                _status.value = TorrentStreamStatus(
                    stage = TorrentStreamStage.ERROR,
                    source = stored.source,
                    contentId = stored.contentId,
                    quality = stored.quality,
                    infoHash = stored.infoHash,
                    detail = "Торрент не успел запуститься",
                )
            }
            result != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Timber.w(error, "[Torrent] resumePersisted failed")
            _status.value = TorrentStreamStatus(
                stage = TorrentStreamStage.ERROR,
                source = stored.source,
                contentId = stored.contentId,
                quality = stored.quality,
                infoHash = stored.infoHash,
                detail = error.message ?: "Не удалось продолжить загрузку",
            )
            false
        }
    }

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun addonSourceLabel(url: String): String = runCatching {
        val host = URI(url).host.lowercase(Locale.ROOT).removePrefix("www.")
        if (host.isBlank()) return@runCatching "Torrent"
        return@runCatching when {
            host.contains("torrentio") -> "TorrentIO"
            host.contains("comet") -> "Comet"
            host.contains("mediafusion") -> "MediaFusion"
            host.contains("knightcrawler") -> "KnightCrawler"
            host.contains("stremio") -> host.substringBefore('.')
                .replaceFirstChar { it.uppercaseChar() }
            else -> host.substringBefore('.')
                .replaceFirstChar { it.uppercaseChar() }
        }
    }.getOrDefault("Torrent")

    private companion object {
        const val MAX_CANDIDATES = 3
        const val FALLBACK_TIMEOUT_MS = 150_000L
        const val RESUME_TIMEOUT_MS = 90_000L
    }
}

/**
 * Ordered addon list: the user's primary first, then the built-in reserve
 * indexes (skipping whichever equals the primary).
 */
internal fun torrentAddonUrls(primaryUrl: String, extraAddons: Boolean): List<String> {
    val primary = primaryUrl.trim()
    if (!extraAddons) return listOf(primary)
    return (listOf(primary) + SettingsDataStore.BUILTIN_TORRENT_ADDONS)
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()
}

/**
 * Merge per-addon candidate lists: dedupe by release (info-hash + file),
 * keep the best-ranked entry, strongest first. Scores share one ranking
 * function, so cross-addon comparison is apples-to-apples.
 */
internal fun mergeAddonCandidates(
    found: List<Pair<String, TorrentCandidate>>,
): List<Pair<String, TorrentCandidate>> =
    found.groupBy { (_, candidate) -> candidate.infoHash.lowercase() to candidate.fileIndex }
        .values
        .mapNotNull { group -> group.maxByOrNull { (_, candidate) -> candidate.score } }
        .sortedByDescending { (_, candidate) -> candidate.score }

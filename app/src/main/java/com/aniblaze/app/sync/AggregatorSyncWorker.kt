package com.aniblaze.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.database.dao.FavoriteDao
import com.aniblaze.database.dao.LinkCacheDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

/**
 * Periodic maintenance worker: probes sources for liveness, evicts expired cached
 * links, and notifies about favourites that gained a new episode.
 *
 * Notification rules, and why they are what they are:
 *
 *  - Triggered by the EPISODE COUNT AT THE SOURCE growing, not by the title's
 *    broadcast weekday. A Russian dub lands roughly a day after the Japanese
 *    broadcast, so an air-date alert fires while there is still nothing to watch.
 *    An episode appearing in the source listing means it is playable now.
 *
 *  - The last seen count is persisted per title. The previous version re-posted for
 *    every favourite whose broadcast day happened to be today, on EVERY worker run —
 *    i.e. all day long, repeatedly. Nothing is posted until a count actually rises,
 *    and the first run only records a baseline.
 *
 *  - Tapping opens the title itself: the content id travels as an intent extra and
 *    MainActivity turns it into a detail-screen navigation. The old code used the
 *    bare launcher intent, which could only ever open the home screen.
 */
@HiltWorker
class AggregatorSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val aggregators: Set<@JvmSuppressWildcards ContentAggregator>,
    private val linkCacheDao: LinkCacheDao,
    private val favoriteDao: FavoriteDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val results = aggregators.map { aggregator ->
            async {
                val alive = runCatching { aggregator.validateSource("") }.getOrDefault(false)
                Timber.d("source %s alive=%b", aggregator.name, alive)
                alive
            }
        }.awaitAll()

        runCatching { linkCacheDao.evictExpired(System.currentTimeMillis()) }
        runCatching { notifyNewEpisodes() }

        if (results.any { it }) Result.success() else Result.retry()
    }

    private suspend fun notifyNewEpisodes() {
        val favorites = favoriteDao.favoritesOnce()
        if (favorites.isEmpty()) return
        val seen = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        for (favorite in favorites) {
            val count = episodeCount(favorite.id)
            if (count <= 0) continue
            val previous = seen.getInt(favorite.id, 0)
            // The stored count only ever grows. episodeCount() asks whichever source
            // answers first, and several sources don't check that the id is theirs —
            // so a foreign (smaller) answer could drop the baseline and make the next
            // correct reading look like a brand-new episode, notifying on every sweep.
            if (count > previous) {
                seen.edit().putInt(favorite.id, count).apply()
                // previous == 0 -> first time we've seen this title: baseline only.
                if (previous > 0) notify(favorite.id, favorite.title, count)
            }
        }
    }

    /** Episode count from whichever source recognises this id. */
    private suspend fun episodeCount(contentId: String): Int {
        for (aggregator in aggregators) {
            val segments = runCatching { aggregator.getContentSegments(contentId) }.getOrDefault(emptyList())
            if (segments.isNotEmpty()) return segments.size
        }
        return 0
    }

    private fun notify(contentId: String, title: String, episode: Int) {
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Новые серии", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        // Carry the title id so the tap lands on that title's page. Each title gets a
        // distinct request code, otherwise PendingIntents collide and every
        // notification opens whichever title was queued first.
        val intent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_CONTENT_ID, contentId)
            } ?: return
        val requestCode = contentId.hashCode()
        val pending = PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(appContext)
        }
        val notification = builder
            .setContentTitle("Вышла новая серия")
            .setContentText("$title · серия $episode")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        // Stable per-title id: a second episode replaces that title's notification
        // instead of stacking a new one for the same show.
        runCatching { nm.notify(NOTIF_BASE + (contentId.hashCode() and 0xFFFF), notification) }
    }

    companion object {
        const val UNIQUE_NAME = "aggregator-sync"
        const val EXTRA_CONTENT_ID = "aniblaze.extra.contentId"
        private const val CHANNEL = "new_episodes"
        private const val PREFS = "episode_counts"
        private const val NOTIF_BASE = 2000
    }
}

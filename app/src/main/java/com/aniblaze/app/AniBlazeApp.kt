package com.aniblaze.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.aniblaze.app.ads.AdManager
import com.aniblaze.app.sync.AggregatorSyncWorker
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.ProxyConfig
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class AniBlazeApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var adManager: AdManager
    @Inject lateinit var settings: SettingsDataStore
    @Inject lateinit var proxyConfig: ProxyConfig

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        adManager.initialize()
        scheduleSync()
        syncProxyConfig()
    }

    /**
     * Pushes the stored proxy into the OkHttp selector holder and keeps it in
     * sync: the selector is consulted per request, so a toggle in Settings
     * applies to the very next parser/addon call.
     */
    private fun syncProxyConfig() {
        appScope.launch {
            runCatching {
                settings.settings.collect { prefs ->
                    proxyConfig.update(
                        enabled = prefs.proxyEnabled,
                        host = prefs.proxyHost,
                        port = prefs.proxyPort,
                        type = prefs.proxyType,
                        user = prefs.proxyUser,
                        pass = prefs.proxyPass,
                    )
                }
            }.onFailure { Timber.e(it, "proxy sync failed") }
        }
    }

    /**
     * Планировщик уносится с главного потока.
     *
     * `WorkManager.getInstance()` — это не «взять готовый объект»: при первом вызове он
     * поднимает СВОЮ базу Room, то есть открывает файл и читает с диска. Стоял он прямо
     * в Application.onCreate, до первого кадра, и добавлял к холодному запуску ровно
     * столько, сколько в этот момент занят диск. Задача периодическая, раз в шесть
     * часов, — ей всё равно, поставят её на пять миллисекунд позже.
     */
    private fun scheduleSync() {
        appScope.launch {
            val request = PeriodicWorkRequestBuilder<AggregatorSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(this@AniBlazeApp).enqueueUniquePeriodicWork(
                    AggregatorSyncWorker.UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { Timber.e(it, "sync schedule failed") }
        }
    }

    /**
     * Загрузчик картинок на всё приложение. Раньше Coil собирал его сам, с настройками
     * по умолчанию.
     *
     *  • память под битмапы: 30% вместо 25% — постеров на экране два-три десятка, и
     *    прокрутка назад не должна упираться в декодирование заново;
     *  • диск: 256 МБ. Постеры приходят с images.weserv.nl готовыми вебпэшками по
     *    ступеням ширины (см. posterUrl), их немного, и повторно они нужны постоянно;
     *  • заголовки кэша не спрашиваем. Обложка тайтла не меняется, а без этого Coil на
     *    каждый повторный показ ходил бы в сеть за «а не устарело ли» — на мобильном
     *    канале это и есть та пауза, из-за которой карточка секунду серая.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.30).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .networkCachePolicy(CachePolicy.ENABLED)
        .build()
}

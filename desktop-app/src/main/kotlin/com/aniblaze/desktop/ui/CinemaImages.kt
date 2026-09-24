package com.aniblaze.desktop.ui

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.net.URI

/** Cinema gets its own request queue, so chat avatars cannot hold up its posters. */
internal object CinemaImages {
    private var client: OkHttpClient? = null
    private var directory: File? = null
    private var loader: ImageLoader? = null

    @Synchronized fun configure(imageClient: OkHttpClient, appDir: File) {
        client = imageClient.newBuilder()
            .dispatcher(Dispatcher().apply { maxRequests = 24; maxRequestsPerHost = 8 })
            .build()
        directory = File(appDir, "cinema_image_cache")
    }

    @Synchronized fun get(context: PlatformContext): ImageLoader? {
        loader?.let { return it }
        val http = client ?: return null
        val cacheDir = directory ?: return null
        return create(context, http, cacheDir).also { loader = it }
    }

    internal fun create(context: PlatformContext, http: OkHttpClient, cacheDir: File): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).weakReferencesEnabled(false).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
            .build()
}

internal fun isCinemaPoster(contentId: String): Boolean {
    if (contentId.startsWith("tmdb:") || contentId.startsWith("tmdbtv:")) return true
    val host = runCatching { URI(contentId.substringBefore(":t")).host.orEmpty().lowercase() }.getOrDefault("")
    return listOf("lordfilm.org", "zetflix.club", "kinozapas.net", "kinogo.ec").any { host == it || host.endsWith(".$it") }
}

@Composable
internal fun posterImageLoader(contentId: String): ImageLoader {
    val context = LocalPlatformContext.current
    return if (isCinemaPoster(contentId)) CinemaImages.get(context) ?: SingletonImageLoader.get(context)
    else SingletonImageLoader.get(context)
}

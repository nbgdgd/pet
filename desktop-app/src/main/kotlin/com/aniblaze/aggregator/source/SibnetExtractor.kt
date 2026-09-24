package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.network.HttpClient
import timber.log.Timber

/**
 * video.sibnet.ru — хостинг с ПРОГРЕССИВНЫМ MP4 (range-seekable), которым
 * пользуются AniDUB на старых тайтлах и агрегатор YummyAnime.
 *
 * Страница `shell.php?videoid=…` несёт адрес файла прямо в разметке плеера:
 * `src: "/v/….mp4"`. CDN проверяет Referer — его плеер получает через
 * `ContentResult.referer` (см. [REFERER]).
 */
class SibnetExtractor(private val http: HttpClient) {

    suspend fun extract(rawUrl: String): List<StreamVariant> {
        val page = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
        // Sibnet отвечает HTTP 400 на User-Agent okhttp — нужен браузерный, даже если
        // клиент собран без UserAgentInterceptor (как в тестах).
        val html = http.getHtml(page, referer = REFERER, headers = mapOf("User-Agent" to UA)) ?: return emptyList()
        val path = Regex("""src:\s*"(/v/[^"]+\.mp4)"""").find(html)?.groupValues?.get(1)
            ?: return emptyList<StreamVariant>().also {
                Timber.w("[Sibnet] page without mp4: %s", page.take(80))
            }
        return listOf(StreamVariant("auto", "https://video.sibnet.ru$path"))
    }

    companion object {
        const val REFERER = "https://video.sibnet.ru/"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        fun owns(url: String): Boolean = url.contains("sibnet", ignoreCase = true)
    }
}

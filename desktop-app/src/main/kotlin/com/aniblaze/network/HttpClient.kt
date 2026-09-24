package com.aniblaze.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Сколько ждать первых байт при проверке ссылки на жизнь (см. [HttpClient.probe]). */
private const val PROBE_TIMEOUT_MS = 4_000L

/** A response whose status code the caller cares about. See [HttpClient.getText]. */
data class TextResponse(val code: Int, val body: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Сервер ответил, но ответ нельзя считать успешным результатом источника. */
class HttpStatusException(
    val method: String,
    val url: String,
    val statusCode: Int,
) : IOException("$method $url -> HTTP $statusCode")

/** Сеть не дала HTTP-ответа вообще: timeout, DNS, reset и т.п. */
class SourceTransportException(
    val method: String,
    val url: String,
    cause: IOException,
) : IOException("$method failed: $url", cause)

/**
 * Thin coroutine-friendly wrapper around [OkHttpClient] used by the aggregator
 * engine. Centralises GET / HEAD requests and referer handling so individual
 * source implementations stay focused on parsing.
 */
@Singleton
class HttpClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {

    /**
     * Fetches a page body. null означает только честный 404; транспорт и прочие
     * HTTP-коды бросаются наверх, чтобы UI не показывал сбой как пустой каталог.
     */
    suspend fun getHtml(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            // «Вежливые» API (Shikimori, Jikan): ответ из дискового кэша, запросы не
            // чаще правила хоста, один повтор после 429 — см. HostPolicy.
            val rule = HostPolicy.ruleFor(url)
            if (rule != null) {
                HostPolicy.cached(url, rule)?.let { return@withContext it.body }
                HostPolicy.throttle(url, rule)
            }
            try {
                val builder = Request.Builder().url(url).get()
                referer?.let { builder.header("Referer", it) }
                headers.forEach { (k, v) -> builder.header(k, v) }
                var attempt = 0
                while (true) {
                    attempt++
                    val outcome = okHttpClient.newCall(builder.build()).execute().use { response ->
                        if (response.code == 404) {
                            Timber.w("GET %s -> HTTP 404", url)
                            if (rule != null) HostPolicy.store(url, null)
                            return@use null
                        }
                        if (response.code == 429 && rule != null && attempt == 1) {
                            val retryAfter = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 10) ?: 2L
                            Timber.w("GET %s -> HTTP 429, retry in %ds", url, retryAfter)
                            return@use RETRY to retryAfter
                        }
                        if (!response.isSuccessful) {
                            Timber.w("GET %s -> HTTP %d", url, response.code)
                            throw HttpStatusException("GET", url, response.code)
                        }
                        val body = response.body?.string() ?: throw IOException("GET $url -> empty body")
                        if (rule != null) HostPolicy.store(url, body)
                        body
                    }
                    if (outcome is Pair<*, *> && outcome.first === RETRY) {
                        Thread.sleep((outcome.second as Long) * 1000)
                        HostPolicy.throttle(url, rule!!)
                        continue
                    }
                    return@withContext outcome as String?
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } catch (status: HttpStatusException) {
                throw status
            } catch (io: IOException) {
                Timber.e(io, "GET failed: %s", url)
                throw SourceTransportException("GET", url, io)
            }
        }

    private companion object {
        /** Маркер «повторить после 429» внутри getHtml. */
        val RETRY = Any()
    }

    /**
     * Отдаёт ли адрес первые байты. Код ответа, либо null — если сервер не ответил.
     *
     * Тело НЕ читается: по этим адресам лежит видео, и `body.string()` скачал бы
     * серию целиком. Запрашивается диапазон в два байта — HEAD не годится, часть
     * CDN отвечает на него иначе, чем на настоящее чтение.
     *
     * Срок у проверки СВОЙ, короткий. Общие сроки клиента (соединение 8 с, чтение
     * 10 с) рассчитаны на страницы каталога, и по ним одна проверка съедала до
     * одиннадцати секунд — замерено 18.08 по журналу, между `stream.resolve.start` и
     * `stream.probe`. Смысла в таком ожидании нет: раздающий, который не отдал двух
     * байт за четыре секунды, не отдаст и видео.
     */
    suspend fun probe(url: String, referer: String? = null, timeoutMs: Long = PROBE_TIMEOUT_MS): Int? =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).get().header("Range", "bytes=0-1")
                referer?.let { builder.header("Referer", it) }
                // newBuilder() делит с исходным клиентом пул соединений и диспетчер —
                // это настройка поверх, а не второй клиент.
                okHttpClient.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(builder.build()).execute().use { it.code }
            }.onFailure { Timber.w(it, "probe failed: %s", url) }.getOrNull()
        }

    /**
     * GET that reports the status code alongside the body.
     *
     * [getHtml] throws away everything that isn't 2xx, which makes "the server
     * answered, there is no data" indistinguishable from "the network is down".
     * AniSkip answers `{"found":false}` with HTTP 404 — a real answer worth
     * caching — and treating it as a failure meant re-querying the same dead
     * episode on every poll. Callers that need to tell those apart use this.
     */
    suspend fun getText(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): TextResponse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).get()
                referer?.let { builder.header("Referer", it) }
                headers.forEach { (k, v) -> builder.header(k, v) }
                okHttpClient.newCall(builder.build()).execute().use { response ->
                    TextResponse(response.code, response.body?.string())
                }
            }.onFailure { Timber.w(it, "GET(text) failed: %s", url) }.getOrNull()
        }

    /** POST form; null означает только 404, остальные сбои не маскируются. */
    suspend fun postForm(url: String, params: Map<String, String>, referer: String? = null): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder().apply {
                    params.forEach { (k, v) -> add(k, v) }
                }.build()
                val builder = Request.Builder().url(url).post(body)
                    .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:125.0)")
                referer?.let { builder.header("Referer", it) }
                okHttpClient.newCall(builder.build()).execute().use { response ->
                    if (response.code == 404) return@use null
                    if (!response.isSuccessful) {
                        Timber.w("POST %s -> HTTP %d", url, response.code)
                        throw HttpStatusException("POST", url, response.code)
                    }
                    JSONObject(response.body?.string() ?: throw IOException("POST $url -> empty body"))
                }
            } catch (status: HttpStatusException) {
                throw status
            } catch (io: IOException) {
                Timber.e(io, "POST failed: %s", url)
                throw SourceTransportException("POST", url, io)
            }
        }

    /** Form POST returning the raw body — for endpoints whose reply is a JSON ARRAY
     *  (AnimeVost's /playlist), which [postForm]'s JSONObject parse cannot hold. */
    suspend fun postFormRaw(url: String, params: Map<String, String>, referer: String? = null): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder().apply {
                    params.forEach { (k, v) -> add(k, v) }
                }.build()
                val builder = Request.Builder().url(url).post(body)
                    .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:125.0)")
                referer?.let { builder.header("Referer", it) }
                okHttpClient.newCall(builder.build()).execute().use { response ->
                    if (response.code == 404) return@use null
                    if (!response.isSuccessful) {
                        Timber.w("POST %s -> HTTP %d", url, response.code)
                        throw HttpStatusException("POST", url, response.code)
                    }
                    response.body?.string() ?: throw IOException("POST $url -> empty body")
                }
            } catch (status: HttpStatusException) {
                throw status
            } catch (io: IOException) {
                Timber.e(io, "POST failed: %s", url)
                throw SourceTransportException("POST", url, io)
            }
        }

    /** POSTs a raw JSON body and returns the JSON response as a String, or null. */
    suspend fun postJson(url: String, json: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body)
                    .header("Accept", "application/json")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.code == 404) return@use null
                    if (!response.isSuccessful) {
                        Timber.w("POST(json) %s -> HTTP %d", url, response.code)
                        throw HttpStatusException("POST", url, response.code)
                    }
                    response.body?.string() ?: throw IOException("POST $url -> empty body")
                }
            } catch (status: HttpStatusException) {
                throw status
            } catch (io: IOException) {
                Timber.e(io, "POST(json) failed: %s", url)
                throw SourceTransportException("POST", url, io)
            }
        }

    /**
     * Issues a HEAD request to confirm a streaming resource is actually
     * reachable before it is handed to the player. Returns true on 2xx/3xx.
     */
    suspend fun isReachable(url: String, referer: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).head()
                referer?.let { builder.header("Referer", it) }
                okHttpClient.newCall(builder.build()).execute().use { response ->
                    response.isSuccessful || response.code in 300..399
                }
            }.getOrElse {
                Timber.w(it, "HEAD failed: %s", url)
                false
            }
        }
}

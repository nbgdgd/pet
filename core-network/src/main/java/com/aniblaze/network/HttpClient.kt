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
import javax.inject.Inject
import javax.inject.Singleton

/** A response whose status code the caller cares about. See [HttpClient.getText]. */
data class TextResponse(val code: Int, val body: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Thin coroutine-friendly wrapper around [OkHttpClient] used by the aggregator
 * engine. Centralises GET / HEAD requests and referer handling so individual
 * source implementations stay focused on parsing.
 */
@Singleton
class HttpClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {

    /** Fetches a page body as a UTF-8 String, or null on any failure. */
    suspend fun getHtml(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).get()
                referer?.let { builder.header("Referer", it) }
                headers.forEach { (name, value) -> builder.header(name, value) }
                okHttpClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("GET %s -> HTTP %d", url, response.code)
                        return@use null
                    }
                    response.body?.string()
                }
            }.onFailure { Timber.e(it, "GET failed: %s", url) }.getOrNull()
        }

    /**
     * Отдаёт ли адрес первые байты. Код ответа, либо null — если сервер не ответил.
     *
     * Ссылки solodcdn протухают по времени (в хвосте адреса стоит срок годности вида
     * `:2026080619`), и за один сеанс все 22 адреса ответили 404 — включая тот, что
     * тремя минутами раньше исправно играл. Плеер узнавал об этом только по таймауту
     * старта: девять секунд на каждую озвучку и каждое качество, со стороны —
     * зависшее приложение. Двухбайтовый запрос выносит приговор за ~110 мс.
     *
     * Тело НЕ читается: по этим адресам лежит видео, и `body.string()` скачал бы
     * серию целиком. Запрашивается диапазон в два байта — HEAD не годится, часть
     * CDN отвечает на него иначе, чем на настоящее чтение.
     *
     * null означает «не определилось» (таймаут, обрыв, неизвестный хост), и это НЕ
     * приговор: вызывающий обязан отдать ссылку плееру, а не объявить источник
     * мёртвым из-за моргнувшей сети.
     */
    suspend fun probe(url: String, referer: String? = null): Int? =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).get().header("Range", "bytes=0-1")
                referer?.let { builder.header("Referer", it) }
                okHttpClient.newCall(builder.build()).execute().use { it.code }
            }.onFailure { Timber.w(it, "probe failed: %s", url) }.getOrNull()
        }

    /**
     * GET that reports the status code alongside the body.
     *
     * [getHtml] throws away everything that isn't 2xx, which makes "the server
     * answered, there is no data" indistinguishable from "the network is down".
     * AniSkip answers `{"found":false}` with HTTP 404 — a real answer worth
     * caching — and treating it as a failure meant re-querying the same dead
     * episode forever. Callers that need to tell those apart use this.
     */
    suspend fun getText(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): TextResponse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).get()
                referer?.let { builder.header("Referer", it) }
                headers.forEach { (name, value) -> builder.header(name, value) }
                okHttpClient.newCall(builder.build()).execute().use { response ->
                    TextResponse(response.code, response.body?.string())
                }
            }.onFailure { Timber.w(it, "GET(text) failed: %s", url) }.getOrNull()
        }

    /** POSTs form-encoded body and returns the JSON response, or null. */
    suspend fun postForm(url: String, params: Map<String, String>, referer: String? = null): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder().apply {
                    params.forEach { (k, v) -> add(k, v) }
                }.build()
                val builder = Request.Builder().url(url).post(body)
                    .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:125.0)")
                referer?.let { builder.header("Referer", it) }
                okHttpClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("POST %s -> HTTP %d", url, response.code)
                        return@use null
                    }
                    response.body?.string()?.let { JSONObject(it) }
                }
            }.onFailure { Timber.e(it, "POST failed: %s", url) }.getOrNull()
        }

    /** POSTs form-encoded body and returns the raw response String, or null.
     *  For endpoints that answer with a JSON ARRAY (AnimeVost /playlist), which
     *  [postForm]'s JSONObject wrapper would reject. */
    suspend fun postFormRaw(url: String, params: Map<String, String>, referer: String? = null): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder().apply {
                    params.forEach { (k, v) -> add(k, v) }
                }.build()
                val builder = Request.Builder().url(url).post(body)
                    .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:125.0)")
                referer?.let { builder.header("Referer", it) }
                okHttpClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("POST(raw) %s -> HTTP %d", url, response.code)
                        return@use null
                    }
                    response.body?.string()
                }
            }.onFailure { Timber.e(it, "POST(raw) failed: %s", url) }.getOrNull()
        }

    /** POSTs a raw JSON body and returns the JSON response as a String, or null. */
    suspend fun postJson(url: String, json: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body)
                    .header("Accept", "application/json")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("POST(json) %s -> HTTP %d", url, response.code)
                        return@use null
                    }
                    response.body?.string()
                }
            }.onFailure { Timber.e(it, "POST(json) failed: %s", url) }.getOrNull()
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

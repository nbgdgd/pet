package com.aniblaze.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpClientFailureTest {
    private fun client(code: Int) = HttpClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build(),
    )

    @Test
    fun `серверная ошибка не превращается в пустой ответ`() {
        assertFailsWith<HttpStatusException> {
            runBlocking { client(503).getHtml("https://example.com/catalog") }
        }
    }

    @Test
    fun `настоящий not found остается отсутствием результата`() {
        assertNull(runBlocking { client(404).getHtml("https://example.com/title") })
    }
}

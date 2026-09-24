package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class OpenRouterSemanticsTest {
    private fun response(indices: List<Int>, finish: String = "stop"): String {
        val items = indices.map { index -> JSONObject().put("index", index).also { item ->
            AnimeSemantics.fields.forEach { item.put(it, JSONArray(listOf("путешествие"))) }
        } }
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", JSONObject().put("items", JSONArray(items)).toString())))).toString()
    }
    @Test fun `strict response parsing rejects missing duplicated mismatched and truncated profiles`() {
        val api = OpenRouterSemantics({ null })
        assertEquals(2, api.parse(response(listOf(1, 0)), 2).size)
        for (body in listOf(response(listOf(0, 0)), response(listOf(1, 2)), response(listOf(0)),
            response(listOf(0, 1), "length"), "not json")) {
            assertFailsWith<SemanticApiException> { api.parse(body, 2) }
        }
    }
    @Test fun `key only in authorization public metadata only and provider price capped`() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(response(listOf(0)).toResponseBody()).build()
        }.build()
        val api = OpenRouterSemantics({ "test-secret" }, client)
        api.extract(listOf(Anime("private-local-id", "Public title", "poster", description = "Public synopsis")))
        val request = assertNotNull(captured)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url.toString())
        assertEquals("Bearer test-secret", request.header("Authorization"))
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertFalse(body.contains("test-secret"))
        assertFalse(body.contains("private-local-id"))
        assertTrue(body.contains("json_schema"))
        assertTrue(body.contains("max_price"))
        assertFalse(body.contains("lastWatched"))
        assertEquals(OpenRouterSemantics.MODEL, JSONObject(body).getString("model"))
    }
    @Test fun `http errors do not expose response content or secrets`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(401).message("Unauthorized").protocol(Protocol.HTTP_1_1)
                .body("Echoed sensitive content must not reach diagnostics".toResponseBody()).build()
        }.build()
        val error = assertFailsWith<SemanticApiException> {
            OpenRouterSemantics({ "test-secret" }, client).extract(listOf(Anime("a", "A", "")))
        }
        assertEquals(401, error.status)
        assertFalse(error.toString().contains("sensitive"))
        assertFalse(error.toString().contains("test-secret"))
    }
}

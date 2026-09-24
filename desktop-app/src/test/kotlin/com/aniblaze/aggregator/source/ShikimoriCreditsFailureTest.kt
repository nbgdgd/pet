package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.StudioCredit
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShikimoriCreditsFailureTest {
    private fun shikimori(code: Int, body: String): ShikimoriSource {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Unavailable")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return ShikimoriSource(HttpClient(client), client)
    }

    @Test fun `studio request failure is not cached as an empty catalog`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val code = if (calls == 1) 503 else 200
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message(if (code == 200) "OK" else "Unavailable")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val source = ShikimoriSource(HttpClient(client), client)

        assertFailsWith<IOException> { source.studioTitles(StudioCredit(11, "Madhouse"), page = 1) }
        assertFalse(source.studioTitles(StudioCredit(11, "Madhouse"), page = 1).hasMore)
        assertEquals(2, calls)
    }

    @Test fun `raw full studio page keeps pagination alive before playback filtering`() = runBlocking {
        val body = (1..24).joinToString(prefix = "[", postfix = "]") { id ->
            "{\"id\":$id,\"russian\":\"Title $id\",\"score\":\"7.0\"}"
        }
        val page = shikimori(200, body).studioTitles(StudioCredit(11, "Madhouse"), page = 1)
        assertTrue(page.hasMore)
        assertTrue(page.titles.size == 24)

        val empty = shikimori(200, "[]").studioTitles(StudioCredit(11, "Madhouse"), page = 1)
        assertFalse(empty.hasMore)
    }

    @Test fun `персонажи из ответа roles - главные первыми, портрет абсолютный, без пустых`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = when {
                chain.request().url.encodedPath.endsWith("/roles") -> """[
                    {"roles":["Supporting"],"character":{"id":2,"name":"Sylphiette","russian":"Сильфиетта","image":{"preview":"/system/characters/preview/2.jpg"}}},
                    {"roles":["Main"],"character":{"id":1,"name":"Rudeus","russian":"Рудеус","image":{"preview":"/system/characters/preview/1.jpg"}}},
                    {"roles":["Director"],"person":{"id":7,"name":"Okamoto","russian":"Окамото"}},
                    {"roles":["Main"],"character":{"id":3,"name":"","russian":""}}
                ]"""
                else -> "{\"studios\":[{\"id\":11,\"name\":\"Studio Bind\"}]}"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val credits = ShikimoriSource(HttpClient(client), client).titleCredits(Anime("1", "Test", ""))!!
        assertEquals(listOf("Рудеус", "Сильфиетта"), credits.characters.map { it.name })
        assertTrue(credits.characters.first().main)
        assertEquals("https://shikimori.one/system/characters/preview/1.jpg", credits.characters.first().image)
        assertEquals(listOf("Окамото"), credits.directors.map { it.name })
    }

    @Test fun `живой ответ roles - у персонажей есть портреты`() = runBlocking {
        val roles = javaClass.getResource("/fixtures/shikimori-roles-50265.json")!!.readText()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath.endsWith("/roles")) roles else "{\"studios\":[]}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val credits = ShikimoriSource(HttpClient(client), client).titleCredits(Anime("50265", "Spy x Family", ""))!!
        assertEquals(16, credits.characters.size)
        // У одного персонажа заглушка missing_preview — портрета нет, остальные с Shikimori.
        assertEquals(15, credits.characters.count { it.image.startsWith("https://shikimori.one/system/characters/preview/") }, credits.characters.map { it.image }.toString())
        assertEquals("Йор Форджер", credits.characters.first().name)
    }

    @Test fun `портретов нет у Shikimori - добираются у Jikan по латинскому имени, с сэйю`() = runBlocking {
        val roles = """[
            {"roles":["Main"],"character":{"id":267433,"name":"Kunon Gurion","russian":"Кунон Гурион","image":{"preview":"/assets/globals/missing_preview.jpg"}}},
            {"roles":["Supporting"],"character":{"id":267434,"name":"Iko Round","russian":"Ико Раунд","image":{"preview":"/assets/globals/missing_preview.jpg"}}}
        ]"""
        val jikan = """{"data":[
            {"character":{"mal_id":267433,"name":"Gurion, Kunon","images":{"jpg":{"image_url":"https://cdn.myanimelist.net/images/characters/1/1.jpg"}}},
             "role":"Main","voice_actors":[{"person":{"name":"Uchiyama, Kouki"},"language":"Japanese"},{"person":{"name":"Someone"},"language":"English"}]},
            {"character":{"mal_id":9,"name":"Nobody","images":{"jpg":{"image_url":"https://cdn.myanimelist.net/images/questionmark_23.gif"}}},"role":"Supporting","voice_actors":[]}
        ]}"""
        var jikanCalls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = when {
                chain.request().url.host.contains("jikan") -> { jikanCalls++; jikan }
                path.endsWith("/roles") -> roles
                else -> "{\"studios\":[]}"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val source = ShikimoriSource(HttpClient(client), client)
        val credits = source.titleCredits(Anime("60810", "Kunon", ""))!!
        assertEquals(1, jikanCalls)
        val kunon = credits.characters.first()
        assertEquals("Кунон Гурион", kunon.name)
        assertEquals("https://cdn.myanimelist.net/images/characters/1/1.jpg", kunon.image)
        assertEquals("Kouki Uchiyama", kunon.seiyuu)
        assertEquals("", credits.characters[1].image, "без пары у MAL — портрета нет, но персонаж остаётся")

        // Список Jikan сам по себе: заглушка-«вопросик» не считается портретом.
        val parsed = source.parseJikanCharacters(jikan)
        assertEquals(listOf("Kunon Gurion", "Nobody"), parsed.map { it.name })
        assertEquals("", parsed[1].image)
    }

    @Test fun `title credits failure is retried instead of cached as missing`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            // Добор персонажей у Jikan — отдельный запрос, к повтору Shikimori не относится.
            if (!chain.request().url.host.contains("jikan")) calls++
            val firstFailure = calls == 1
            val body = when {
                firstFailure -> "{}"
                chain.request().url.encodedPath.endsWith("/roles") -> "[]"
                else -> "{\"studios\":[]}"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (firstFailure) 503 else 200)
                .message(if (firstFailure) "Unavailable" else "OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val source = ShikimoriSource(HttpClient(client), client)
        val anime = Anime("1", "Test", "")

        assertFailsWith<IOException> { source.titleCredits(anime) }
        assertNotNull(source.titleCredits(anime))
        assertEquals(3, calls)
    }
}

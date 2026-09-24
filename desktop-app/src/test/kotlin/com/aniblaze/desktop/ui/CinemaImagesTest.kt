package com.aniblaze.desktop.ui

import kotlin.test.*
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class CinemaImagesTest {
    @Test fun `cinema poster reopens from disk with memory disabled and server no-cache`() = runBlocking {
        val downloads = AtomicInteger()
        val bytes = javaClass.getResourceAsStream("/pet/drizz/spritesheet.webp")!!.use { it.readBytes() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloads.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Cache-Control", "no-cache")
                .body(bytes.toResponseBody("image/webp".toMediaType())).build()
        }.build()
        val loader = CinemaImages.create(PlatformContext.INSTANCE, client, Files.createTempDirectory("cinema-posters-").toFile())
        try {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data("https://image.tmdb.org/t/p/w342/test.webp").size(72, 108)
                .memoryCachePolicy(CachePolicy.DISABLED).build()
            val first = assertIs<SuccessResult>(loader.execute(request))
            assertEquals(DataSource.NETWORK, first.dataSource)
            val second = assertIs<SuccessResult>(loader.execute(request))
            assertEquals(DataSource.DISK, second.dataSource)
            assertEquals(1, downloads.get(), "Repeated opening should not revalidate/download the poster")
        } finally { loader.shutdown() }
    }

    @Test fun `cinema queue accepts cinema identities and leaves anime out`() {
        for (id in listOf("tmdb:42", "tmdbtv:42:t2", "https://lordfilm.org/film/42", "https://www.zetflix.club/42.html", "https://kinozapas.net/42"))
            assertTrue(isCinemaPoster(id), id)
        for (id in listOf("an:42", "shiki:42", "https://shikimori.one/animes/42", "https://lordfilm.org.example.com/42", "nonsense"))
            assertFalse(isCinemaPoster(id), id)
    }
}

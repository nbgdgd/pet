package com.aniblaze.torrent

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioTorrentIndexTest {
    @Test
    fun `parses documented infoHash fileIdx and tracker fields`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{
              "streams": [
                {"name":"1080p WEB-DL", "description":"Seeds 42", "infoHash":"0123456789abcdef0123456789abcdef01234567", "fileIdx":3,
                 "sources":["tracker:udp://tracker.example:80", "dht:ignored"]}
              ]
            }"""),
        )
        server.start()
        try {
            val result = StremioTorrentIndex(OkHttpClient()).find(
                server.url("/manifest.json").toString(),
                "tt1234567",
                isSeries = false,
                season = 0,
                episode = 0,
            ).single()
            assertEquals(3, result.fileIndex)
            assertEquals("1080p · Torrent", result.label)
            assertTrue(result.magnet().contains("0123456789abcdef0123456789abcdef01234567"))
            assertTrue(result.magnet().contains("tracker.example"))
            assertEquals("/stream/movie/tt1234567.json", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `prefers web 1080 over cam and oversized 4k`() {
        val good = StremioTorrentIndex.rank("1080p WEB-DL seeds 20")
        assertTrue(good > StremioTorrentIndex.rank("1080p CAMRip seeds 100"))
        assertTrue(good > StremioTorrentIndex.rank("2160p 4K seeds 5"))
    }

    @Test
    fun `prefers streamable mobile file over huge remux`() {
        val mobile = StremioTorrentIndex.rank("1080p WEB-DL 👤 35 💾 2.4 GB")
        val remux = StremioTorrentIndex.rank("1080p BluRay REMUX 👤 40 💾 38.2 GB")
        assertTrue(mobile > remux)
    }
}

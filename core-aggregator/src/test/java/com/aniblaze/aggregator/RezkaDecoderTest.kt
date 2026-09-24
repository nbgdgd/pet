package com.aniblaze.aggregator

import com.aniblaze.aggregator.source.RezkaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Validates the HDrezka "clearTrash" de-obfuscation by re-creating the exact
 * obfuscation the site applies (base64 → chunk → append a trash token per chunk,
 * joined by `//_//`, `#h` prefix) and asserting the decoder recovers the plaintext.
 */
class RezkaDecoderTest {

    private fun obfuscate(plain: String): String {
        val b64 = Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        val trash = RezkaSource.TRASH_CODES.first() // a real trash token
        return "#h" + b64.chunked(7).joinToString("//_//") { chunk -> chunk + trash }
    }

    @Test
    fun `decodes an obfuscated movie stream payload`() {
        val plain = "[360p]https://cdn.example/360.mp4 or https://alt.example/360.mp4," +
            "[720p]https://cdn.example/720.mp4,[1080p]https://cdn.example/1080.mp4"
        val decoded = RezkaSource.decodeTrash(obfuscate(plain))
        assertEquals(plain, decoded)
    }

    @Test
    fun `decoded payload parses into ordered qualities via the same format`() {
        val plain = "[480p]https://c/480.mp4,[1080p]https://c/1080.mp4,[360p]https://c/360.mp4"
        val decoded = RezkaSource.decodeTrash(obfuscate(plain))
        // The stream list is comma-separated [quality]url entries.
        val qualities = decoded.split(",").mapNotNull {
            Regex("\\[([^\\]]+)]").find(it)?.groupValues?.get(1)
        }
        assertEquals(listOf("480p", "1080p", "360p"), qualities)
        assertTrue(decoded.contains(".mp4"))
    }

    @Test
    fun `blank input yields blank`() {
        assertEquals("", RezkaSource.decodeTrash(""))
    }
}

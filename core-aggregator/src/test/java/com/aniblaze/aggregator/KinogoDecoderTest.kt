package com.aniblaze.aggregator

import com.aniblaze.aggregator.source.KinogoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Verifies the cinemar.cc lenient decode + tolerant stream extraction, and that
 * series group by (season, episode) with each episode's dubs as voiceovers —
 * NOT collapsed by dub name (the earlier bug that showed one 90-min "episode").
 */
class KinogoDecoderTest {

    private fun obfuscate(json: String): String {
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return "#238abc123prefix" + b64 // junk prefix (no "W3si"), then real base64
    }

    @Test
    fun `film exposes its dubs, no seasons`() {
        val json = """[{"title":"Дубляж","file":"https://c/movies/x/hls.m3u8"},""" +
            """{"title":"Закадровый","file":"https://c/movies/y/hls.m3u8"}]"""
        val (isSerial, entries) = KinogoSource.extractEntries(obfuscate(json))
        assertFalse(isSerial)
        assertEquals(2, entries.size)
        assertEquals("Дубляж", entries[0].voice)
        assertEquals("", entries[0].episode)
        assertEquals("https://c/movies/x/hls.m3u8", entries[0].file)
    }

    @Test
    fun `series group by episode with dubs inside`() {
        val json = """[{"title":"Сезон 1","folder":[""" +
            """{"title":"Серия 1","folder":[""" +
            """{"title":"Оригинал","file":"https://c/tv/s1e1a.m3u8"},""" +
            """{"title":"Дубляж","file":"https://c/tv/s1e1b.m3u8"}]},""" +
            """{"title":"Серия 2","folder":[""" +
            """{"title":"Оригинал","file":"https://c/tv/s1e2.m3u8"}]}]}]"""
        val (isSerial, entries) = KinogoSource.extractEntries(obfuscate(json))
        assertTrue(isSerial)
        assertEquals(3, entries.size) // 2 dubs of ep1 + 1 of ep2
        // Episode 1 carries season/episode; the label before the file is the dub.
        assertEquals("Сезон 1", entries[0].season)
        assertEquals("Серия 1", entries[0].episode)
        assertEquals("Оригинал", entries[0].voice)
        assertEquals("Дубляж", entries[1].voice)
        assertEquals("Серия 2", entries[2].episode)
        // Distinct episodes = 2 (dubs collapse), NOT 1.
        val episodes = entries.map { it.season + "|" + it.episode }.distinct()
        assertEquals(2, episodes.size)
    }
}

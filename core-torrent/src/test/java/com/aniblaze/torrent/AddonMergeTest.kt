package com.aniblaze.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonMergeTest {
    private fun candidate(hash: String, score: Int, fileIndex: Int? = 0) =
        TorrentCandidate(
            infoHash = hash,
            fileIndex = fileIndex,
            trackers = emptyList(),
            label = "1080p · Torrent",
            score = score,
        )

    @Test
    fun `dedupes same release across addons keeping strongest score`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val merged = mergeAddonCandidates(
            listOf(
                "TorrentIO" to candidate(hash, score = 100),
                "Comet" to candidate(hash, score = 400),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("Comet", merged.single().first)
    }

    @Test
    fun `keeps distinct releases sorted by score`() {
        val merged = mergeAddonCandidates(
            listOf(
                "TorrentIO" to candidate("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 100),
                "MediaFusion" to candidate("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 500),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals("MediaFusion", merged.first().first)
    }

    @Test
    fun `same hash different file index are distinct`() {
        val hash = "cccccccccccccccccccccccccccccccccccccccc"
        val merged = mergeAddonCandidates(
            listOf(
                "TorrentIO" to candidate(hash, score = 100, fileIndex = 0),
                "TorrentIO" to candidate(hash, score = 90, fileIndex = 2),
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `addon list starts with primary and dedupes builtins`() {
        val urls = torrentAddonUrls(
            "https://torrentio.strem.fun/manifest.json",
            extraAddons = true,
        )
        assertEquals("https://torrentio.strem.fun/manifest.json", urls.first())
        assertTrue(urls.any { it.contains("comet") })
        assertTrue(urls.any { it.contains("mediafusion") })
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `extra addons off leaves primary only`() {
        val urls = torrentAddonUrls("https://example.com/addon.json", extraAddons = false)
        assertEquals(listOf("https://example.com/addon.json"), urls)
    }
}

package com.aniblaze.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentRangeServerTest {
    @Test
    fun `parses bounded open and suffix ranges`() {
        assertEquals(100L..199L, TorrentRangeServer.parseRange("bytes=100-199", 1_000L))
        assertEquals(100L..999L, TorrentRangeServer.parseRange("bytes=100-", 1_000L))
        assertEquals(900L..999L, TorrentRangeServer.parseRange("bytes=-100", 1_000L))
    }

    @Test
    fun `rejects range outside file`() {
        assertNull(TorrentRangeServer.parseRange("bytes=1000-1100", 1_000L))
        assertNull(TorrentRangeServer.parseRange("nonsense", 1_000L))
    }
}

package com.aniblaze.aggregator.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamModeTest {
    @Test fun routeValuesAreParsedAndUnknownFallsBackToAuto() {
        assertEquals(StreamMode.PARSER, StreamMode.fromRoute("parser"))
        assertEquals(StreamMode.TORRENT, StreamMode.fromRoute("TORRENT"))
        assertEquals(StreamMode.AUTO, StreamMode.fromRoute("unknown"))
        assertEquals(StreamMode.AUTO, StreamMode.fromRoute(null))
    }
}

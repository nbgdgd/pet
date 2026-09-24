package com.aniblaze.featureplayer

import com.aniblaze.aggregator.TorrentStreamStage
import com.aniblaze.aggregator.TorrentStreamStatus
import com.aniblaze.aggregator.model.StreamMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatusTest {
    @Test fun loadingNamesTheRequestedTransport() {
        assertEquals("Загрузка через парсер…", streamStatusLabel(StreamMode.PARSER, "", true))
        assertEquals("Загрузка через торрент…", streamStatusLabel(StreamMode.TORRENT, "", true))
        assertEquals("Загрузка через торрент…", streamStatusLabel(StreamMode.AUTO, "TorrentIO", true))
    }

    @Test fun resolvedSourceNamesTheActualTransportAndProvider() {
        assertEquals("Торрент", streamStatusLabel(StreamMode.TORRENT, "Torrent", false))
        assertEquals("Торрент", streamStatusLabel(StreamMode.AUTO, "TorrentIO", false))
        assertEquals("Парсер · Lampa/cdnvideohub", streamStatusLabel(StreamMode.PARSER, "Lampa/cdnvideohub", false))
    }

    @Test fun torrentTelemetryShowsActualRatePeersAndDownloadedPercent() {
        val status = TorrentStreamStatus(
            stage = TorrentStreamStage.STREAMING,
            downloadBytesPerSecond = 3L * 1024 * 1024 + 512 * 1024,
            peers = 7,
            downloadedBytes = 25,
            totalBytes = 100,
        )

        assertEquals("Торрент · 3,5 МБ/с · 7 пиров · 25%", torrentStatusLabel(status))
    }

    @Test fun torrentLaunchStageIsVisibleBeforeSpeedExists() {
        assertEquals(
            "Торрент · загрузка метаданных…",
            torrentStatusLabel(TorrentStreamStatus(stage = TorrentStreamStage.METADATA)),
        )
    }
}

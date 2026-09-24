package com.aniblaze.app.downloads

import com.aniblaze.aggregator.TorrentStreamStage
import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentDownloadsFormatTest {
    @Test fun formatsLiveTorrentTelemetry() {
        assertEquals("2,5 МБ/с", formatTorrentRate(2L * 1024 * 1024 + 512 * 1024))
        assertEquals("1,5 ГБ", formatTorrentBytes(1536L * 1024 * 1024))
    }

    @Test fun pausedStateHasExplicitLabel() {
        assertEquals("Загрузка на паузе", torrentStageLabel(TorrentStreamStage.PAUSED))
    }
}

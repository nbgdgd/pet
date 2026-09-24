package com.aniblaze.torrent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frostwire.jlibtorrent.LibTorrent
import com.frostwire.jlibtorrent.SessionManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTorrentSmokeTest {
    @Test
    fun nativeEngineLoadsAndStartsOnDevice() {
        assertTrue(LibTorrent.version().isNotBlank())
        val session = SessionManager(false)
        session.start()
        try {
            assertTrue(session.isRunning)
        } finally {
            session.stop()
        }
    }
}

package com.aniblaze.desktop.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsCommitGuardTest {
    @Test
    fun `кадр пропускается при критическом остатке commit`() {
        assertFalse(WindowsCommitGuard.hasHeadroom(200L * 1024 * 1024, 8L * 1024 * 1024))
        assertTrue(WindowsCommitGuard.hasHeadroom(2L * 1024 * 1024 * 1024, 8L * 1024 * 1024))
    }
}

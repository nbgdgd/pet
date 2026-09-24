package com.aniblaze.desktop.player

import com.aniblaze.desktop.AppSettings
import java.nio.file.Files
import kotlin.test.*

class ChatDockTest {
    @Test fun `left and right keep identical widths and nonoverlapping video and chat`() {
        for (width in listOf(320, 800, 1280, 1920, 3840)) {
            for (requested in listOf(0, 240, 330, 560, 5000)) {
                val left = chatDockGeometry(width, requested, "left")
                val right = chatDockGeometry(width, requested, "right")
                assertEquals(left.mainWidth, right.mainWidth)
                assertEquals(left.chatWidth, right.chatWidth)
                assertEquals(width, left.mainWidth + left.chatWidth)
                assertEquals(0, left.chatX)
                assertEquals(left.chatWidth, left.mainX)
                assertEquals(right.mainWidth, right.chatX)
                assertEquals(0, right.mainX)
                assertTrue(left.mainWidth >= width / 2)
            }
        }
    }
    @Test fun `hidden chat gives all width back to video`() {
        assertEquals(ChatDockGeometry(1920, 0, 0, 0), chatDockGeometry(1920, 0, "left"))
    }
    @Test fun `settings changes are immediate and survive disk reload`() {
        val file = Files.createTempDirectory("aniblaze-chat-setting").resolve("state.json").toFile()
        val settings = AppSettings(file)
        assertEquals("right", settings.state.value.chatSide)
        settings.setChatSide("left")
        settings.setChatPanelWidth(440)
        assertEquals("left", settings.state.value.chatSide)
        settings.flush()
        val restarted = AppSettings(file)
        assertEquals("left", restarted.state.value.chatSide)
        assertEquals(440, restarted.state.value.chatPanelWidth)
        restarted.setChatSide("right")
        restarted.flush()
        assertEquals("right", AppSettings(file).state.value.chatSide)
    }
    @Test fun `invalid legacy side uses right and zero width is safe`() {
        assertEquals(0, chatDockGeometry(0, 330, "left").mainWidth)
        assertEquals(0, chatDockGeometry(1200, 330, "broken").mainX)
    }
}

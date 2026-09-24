package com.aniblaze.desktop.player

import com.aniblaze.desktop.AppSettings
import java.nio.file.Files
import kotlin.test.*

class ChatTextSizeTest {
    @Test fun `all font presets update immediately and survive restart on both sides`() {
        val file = Files.createTempDirectory("chat-font").resolve("state.json").toFile()
        val settings = AppSettings(file)
        for (side in listOf("left", "right")) for ((size, _) in CHAT_FONT_PRESETS) {
            settings.setChatSide(side)
            settings.setChatFontSize(size)
            assertEquals(size, settings.state.value.chatFontSize)
            settings.flush()
            val restored = AppSettings(file).state.value
            assertEquals(size, restored.chatFontSize)
            assertEquals(side, restored.chatSide)
            assertEquals(330, restored.chatPanelWidth)
            assertEquals(size * 1.35f, chatLineHeight(size))
        }
    }
    @Test fun `text size does not alter dock on resize or fullscreen widths`() {
        for (width in listOf(640, 1280, 1920, 3840)) for (side in listOf("left", "right")) {
            val expected = chatDockGeometry(width, 320, side)
            for ((size, _) in CHAT_FONT_PRESETS) {
                assertTrue(chatLineHeight(size) > size)
                assertEquals(expected, chatDockGeometry(width, 320, side))
            }
        }
    }
}

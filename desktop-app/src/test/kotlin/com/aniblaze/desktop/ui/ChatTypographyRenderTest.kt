@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Surface
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.player.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.nio.file.Files
import kotlin.test.*

class ChatTypographyRenderTest {
    @Test fun `chat menu changes real message layout without scaling header on both sides and resize`() = runBlocking {
        val file = Files.createTempDirectory("chat-render").resolve("state.json").toFile()
        val settings = AppSettings(file)
        val feed = ChatFeed()
        val message = "Тестовое сообщение для проверки переноса строк и размера текста в чате."
        feed.push(ChatMessage(1, "Зритель", 0xFFFF6A3D, null, "", true, null, message, SceneMood.CALM))
        val owners = mutableSetOf<SemanticsOwner>()
        val listener = object : PlatformContext.SemanticsOwnerListener {
            override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners.add(semanticsOwner) }
            override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners.remove(semanticsOwner) }
            override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
            override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
        }
        val context = object : ComposeSceneContext {
            override val platformContext = object : PlatformContext by PlatformContext.Empty {
                override val semanticsOwnerListener = listener
            }
        }
        val scene = CanvasLayersComposeScene(size = IntSize(900, 600), coroutineContext = coroutineContext, composeSceneContext = context)
        val surface = Surface.makeRasterN32Premul(1920, 1080)
        scene.setContent {
            AniBlazeTheme { CompositionLocalProvider(LocalAppSettings provides settings) {
                val state by settings.state.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    ChatSidePanel(feed, state.chatFontSize, {}, {}, Modifier.width(state.chatPanelWidth.dp)
                        .fillMaxHeight().align(if (state.chatSide == "left") Alignment.CenterStart else Alignment.CenterEnd))
                }
            } }
        }
        suspend fun frames() { repeat(8) { delay(10); scene.render(surface.canvas.asComposeCanvas(), System.nanoTime()) } }
        fun nodes() = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        fun textNode(text: String) = nodes().first { node -> node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text.contains(text) } }
        fun layout(node: SemanticsNode): TextLayoutResult {
            val result = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(result))
            return result.single()
        }
        fun click(node: SemanticsNode) {
            val clickable = generateSequence(node) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
            assertTrue(clickable.config[SemanticsActions.OnClick].action!!.invoke())
        }
        try {
            frames()
            // Exercise the actual new menu item, not just the setter.
            click(nodes().first { it.config.contains(SemanticsProperties.ContentDescription) &&
                "Режимы чата" in it.config[SemanticsProperties.ContentDescription] })
            frames()
            click(textNode("Макс."))
            frames()
            assertEquals(20, settings.state.value.chatFontSize)
            val shown = feed.messages.toList()
            for ((speed, label) in CHAT_SPEED_PRESETS) {
                click(textNode(label))
                frames()
                assertEquals(speed, settings.state.value.chatSpeed)
                assertEquals(shown, feed.messages.toList(), "Speed must not clear the displayed chat")
                assertEquals(20, settings.state.value.chatFontSize)
            }
            // Close the popup through a state-independent headless back/outside event
            // is unnecessary: resize and typography assertions include the live menu.
            for (side in listOf("left", "right")) for (size in listOf(11, 13, 16, 20)) {
                settings.setChatSide(side)
                settings.setChatFontSize(size)
                scene.size = if (size == 20) IntSize(1920, 1080) else IntSize(900, 600)
                frames()
                val actual = layout(textNode(message))
                assertEquals(size.toFloat(), actual.layoutInput.style.fontSize.value)
                assertEquals(chatLineHeight(size), actual.layoutInput.style.lineHeight.value)
                assertEquals(13f, layout(textNode("Чат")).layoutInput.style.fontSize.value)
                assertTrue(textNode(message).boundsInRoot.width in 1f..330f)
            }
            settings.flush()
            assertEquals(20, AppSettings(file).state.value.chatFontSize)
            assertEquals(2f, AppSettings(file).state.value.chatSpeed)
        } finally { scene.close(); surface.close(); settings.flush() }
    }
}

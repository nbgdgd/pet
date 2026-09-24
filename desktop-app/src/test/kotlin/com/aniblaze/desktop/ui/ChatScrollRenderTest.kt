@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.unit.IntSize
import com.aniblaze.desktop.player.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import kotlin.test.*

class ChatScrollRenderTest {
    @Test fun `half speed scroll reveals a new paragraph gradually rather than teleporting it`() = runBlocking {
        val feed = ChatFeed().also { it.speed = .5f }
        fun message(id: Int, text: String) = ChatMessage(id.toLong(), "Зритель", 0xFFFF6A3D,
            null, "", true, null, text, SceneMood.CALM)
        repeat(16) { feed.push(message(it + 1, "Реплика ${it + 1}: обсуждаем сюжет этой серии.")) }
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
        val scene = CanvasLayersComposeScene(size = IntSize(380, 650), coroutineContext = coroutineContext, composeSceneContext = context)
        val surface = Surface.makeRasterN32Premul(380, 650)
        var time = System.nanoTime()
        suspend fun frames(count: Int) { repeat(count) {
            delay(2); time += 20_000_000; scene.render(surface.canvas.asComposeCanvas(), time)
        } }
        fun scroll(): Float = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        scene.setContent { AniBlazeTheme { ChatSidePanel(feed, 20, {}, {}, Modifier.fillMaxSize()) } }
        try {
            frames(300)
            val before = scroll()
            feed.push(message(17, "Большая новая реплика про героев и события этой серии. ".repeat(6)))
            frames(6)
            val early = scroll()
            frames(18)
            val middle = scroll()
            frames(100)
            val end = scroll()
            assertTrue(early >= before, "$before -> $early")
            assertTrue(middle > early, "$early -> $middle")
            assertTrue(end > middle, "$middle -> $end")
            assertEquals(17, feed.messages.size)
        } finally { scene.close(); surface.close() }
    }
}

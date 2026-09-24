@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.ui.AniBlazeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import kotlin.test.*

class TitleTabBarRenderTest {
    @Test fun `wheel and mouse drag scroll tabs without selecting or closing and click still works`() = runBlocking {
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
        val scene = CanvasLayersComposeScene(size = IntSize(480, 80), coroutineContext = coroutineContext, composeSceneContext = context)
        val surface = Surface.makeRasterN32Premul(480, 80)
        var time = System.nanoTime()
        suspend fun frames(n: Int = 20) { repeat(n) { delay(3); time += 20_000_000; scene.render(surface.canvas.asComposeCanvas(), time) } }
        fun nodes() = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        fun scroll() = nodes().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        fun mouse(kind: PointerEventType, x: Float, down: Boolean = false) = scene.sendPointerEvent(
            eventType = kind, position = Offset(x, 16f), type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = down),
        )
        val nav = NavController()
        repeat(12) { nav.openTitle(Anime("test:$it", "Тайтл $it", ""), false) }
        nav.selectTab("test:0")
        scene.setContent { AniBlazeTheme { TitleTabBar(nav) } }
        try {
            frames(40)
            val start = scroll()
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(160f, 16f), scrollDelta = Offset(0f, 4f), type = PointerType.Mouse)
            frames()
            val wheeled = scroll()
            assertTrue(wheeled > start, "vertical wheel did not move tabs: $start -> $wheeled")
            mouse(PointerEventType.Press, 350f, true); frames(3)
            mouse(PointerEventType.Move, 330f, true); frames(3)
            mouse(PointerEventType.Move, 220f, true); frames(3)
            mouse(PointerEventType.Move, 120f, true); frames(3)
            mouse(PointerEventType.Release, 120f); frames(40)
            assertTrue(scroll() > wheeled, "mouse drag did not move tabs")
            assertEquals("test:0", nav.activeTabId, "drag selected a tab")
            assertEquals(12, nav.tabs.size, "drag closed a tab")
            val label = nodes().first {
                it.config.contains(SemanticsProperties.Text) && it.boundsInRoot.left > 10f && it.boundsInRoot.right < 430f
            }
            val expected = label.config[SemanticsProperties.Text].first().text.removePrefix("Тайтл ")
            mouse(PointerEventType.Press, label.boundsInRoot.center.x, true); frames(2)
            mouse(PointerEventType.Release, label.boundsInRoot.center.x); frames(40)
            assertEquals("test:$expected", nav.activeTabId, "ordinary click was eaten")
            nav.selectTab("test:11"); frames(80)
            assertTrue(nodes().any { it.config.contains(SemanticsProperties.Text) &&
                it.config[SemanticsProperties.Text].any { text -> text.text == "Тайтл 11" } &&
                it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= 480 }, "active far tab not revealed")
            Unit
        } finally { scene.close(); surface.close() }
    }
}

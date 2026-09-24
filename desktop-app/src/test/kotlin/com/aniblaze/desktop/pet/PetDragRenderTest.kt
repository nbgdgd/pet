@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.pet

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import kotlin.test.*

class PetDragRenderTest {
    @Test fun `drag moves pet without opening card and position survives remount and resize`() = runBlocking {
        val owners = mutableSetOf<SemanticsOwner>()
        val listener = object : PlatformContext.SemanticsOwnerListener {
            override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners.add(semanticsOwner) }
            override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners.remove(semanticsOwner) }
            override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
            override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
        }
        val context = object : ComposeSceneContext {
            override val platformContext = object : PlatformContext by PlatformContext.Empty { override val semanticsOwnerListener = listener }
        }
        val scene = CanvasLayersComposeScene(size = IntSize(800, 600), coroutineContext = coroutineContext, composeSceneContext = context)
        val surface = Surface.makeRasterN32Premul(800, 600)
        var x by mutableStateOf(1f); var y by mutableStateOf(1f)
        var mounted by mutableStateOf(true)
        var saves = 0
        scene.setContent {
            if (mounted) PetDesktopPosition(x, y, { a, b -> x = a; y = b; saves++ }) { modifier, drag, end ->
                PetCorner(PetDef.of("drizz"), PetMood.IDLE, 1f, null, listOf("Тестовая статистика"), "Тайтл",
                    resume = null, recommendation = null, onAnotherRecommendation = null, onOpenRecommendation = null,
                    onHide = {}, modifier = modifier, onDrag = drag, onDragEnd = end)
            }
        }
        var time = System.nanoTime()
        suspend fun frames(n: Int = 15) { repeat(n) { delay(3); time += 20_000_000; scene.render(surface.canvas.asComposeCanvas(), time) } }
        fun nodes() = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        fun pet() = nodes().first { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains("Питомец Drizz") }
        fun expanded() = nodes().any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == "Тестовая статистика" } }
        fun mouse(kind: PointerEventType, p: Offset, down: Boolean) = scene.sendPointerEvent(kind, p,
            type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = down))
        try {
            frames()
            val start = pet().boundsInRoot.center
            mouse(PointerEventType.Press, start, true); frames(2)
            for (i in 1..5) { mouse(PointerEventType.Move, start - Offset(i * 25f, i * 20f), true); frames(3) }
            mouse(PointerEventType.Release, start - Offset(125f, 100f), false); frames()
            val moved = pet().boundsInRoot.center
            assertTrue(moved.x < start.x - 80 && moved.y < start.y - 60, "$start -> $moved")
            assertFalse(expanded(), "drag opened the card")
            assertEquals(1, saves, "position saved on every mouse tick")
            mounted = false; frames(); mounted = true; frames()
            assertEquals(moved, pet().boundsInRoot.center)
            mouse(PointerEventType.Press, moved, true); frames(2)
            mouse(PointerEventType.Release, moved, false); frames()
            assertTrue(expanded(), "normal click lost")
            // Close by clicking the pet again before testing a much smaller viewport.
            val current = pet().boundsInRoot.center
            mouse(PointerEventType.Press, current, true); frames(2)
            mouse(PointerEventType.Release, current, false); frames()
            scene.size = IntSize(380, 300); frames()
            val bounds = pet().boundsInRoot
            assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 380 && bounds.bottom <= 300, "$bounds")
            Unit
        } finally { scene.close(); surface.close() }
    }
}

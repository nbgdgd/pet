@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import kotlin.test.*

class DescriptionCardTest {
    @Test fun `paragraphs preserve source wording without empty lines`() {
        assertEquals(listOf("Первая строка.", "Вторая строка!", "Третья."), descriptionParagraphs(" Первая строка.\r\n\r\nВторая строка!\rТретья. "))
        assertTrue(descriptionParagraphs(" \n ").isEmpty())
    }

    @Test fun `preview opens a scrollable reader and close returns to compact card`() = runBlocking {
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
        val scene = CanvasLayersComposeScene(size = IntSize(760, 620), coroutineContext = coroutineContext, composeSceneContext = context)
        val surface = Surface.makeRasterN32Premul(760, 620)
        var reading by mutableStateOf(false)
        val description = (1..8).joinToString("\n\n") { "Абзац $it. Говорят, что родителей не выбирают. Жаль, будь существуй возможность сделать выбор, Юкито Ураба предпочёл бы каких-нибудь других папу и маму. Необязательно богатых или знаменитых, или даже добрых." }
        scene.setContent { AniBlazeTheme {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                if (reading) DescriptionReader("Камикацу: Работа бога в мире без богов", description, { reading = false }, Modifier.fillMaxSize())
                else DescriptionPreview(description, { reading = true }, Modifier.fillMaxWidth())
            }
        } }
        var time = System.nanoTime()
        suspend fun frames() { repeat(30) { delay(3); time += 20_000_000; scene.render(surface.canvas.asComposeCanvas(), time) } }
        fun nodes() = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
        fun save(name: String) { surface.makeImageSnapshot().use { image -> image.encodeToData()?.use { data ->
            val file = java.io.File("build/reports/description/$name.png"); file.parentFile.mkdirs(); file.writeBytes(data.bytes)
        } } }
        try {
            frames()
            val read = nodes().first { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == "Читать полностью" } }
            assertTrue(read.boundsInRoot.bottom < 300, "preview takes too much vertical space")
            save("preview")
            read.config[SemanticsActions.OnClick].action?.invoke(); frames()
            assertTrue(reading)
            val scroll = nodes().first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            assertTrue(scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0)
            save("reader")
            nodes().first { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains("Закрыть описание") }
                .config[SemanticsActions.OnClick].action?.invoke()
            frames(); assertFalse(reading)
            scene.size = IntSize(380, 620); frames()
            assertTrue(nodes().filter { it.config.contains(SemanticsProperties.Text) }.all { it.boundsInRoot.right <= 380f })
            Unit
        } finally { scene.close(); surface.close() }
    }
}

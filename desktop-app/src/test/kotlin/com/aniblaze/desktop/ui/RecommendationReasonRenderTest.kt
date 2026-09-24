@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.jetbrains.skia.Surface
import java.nio.file.Files
import kotlin.test.*

/** A real Compose tree; no Computer Use or interaction with the user's app. */
class RecommendationReasonRenderTest {
    @Test fun `source thumbnail opens its title and explanation fits fixed card space`() = runBlocking {
        val dir = Files.createTempDirectory("reason-render")
        val poster = dir.resolve("poster.png").toFile()
        poster.writeBytes(java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aMioAAAAASUVORK5CYII="))
        val source = Anime("source", "Основание рекомендации", poster.absolutePath)
        val reason = Recommender.Explanation(Recommender.ReasonFactor.SEMANTIC,
            "Вы высоко оценили похожие тайтлы\nисследование мира · меланхолия",
            listOf(Recommender.TasteSource(source, 6.0, Recommender.HistorySignal.HIGH_RATING, 5)),
            emptyList(), listOf("исследование мира", "меланхолия"), null, 90.0,
            mapOf(Recommender.ReasonFactor.SEMANTIC to 40.0))
        val owners = mutableListOf<SemanticsOwner>()
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
        var opened: Anime? = null
        val scene = CanvasLayersComposeScene(size = IntSize(340, 90), coroutineContext = coroutineContext, composeSceneContext = context)
        val surface = Surface.makeRasterN32Premul(340, 90)
        try {
            scene.setContent { AniBlazeTheme { Box(Modifier.width(320.dp)) { RecommendationReason(reason) { opened = it } } } }
            repeat(15) { delay(10); scene.render(surface.canvas.asComposeCanvas(), System.nanoTime()) }
            val nodes = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            val thumbnail = nodes.single { it.config.contains(SemanticsProperties.ContentDescription) }
            assertEquals(24f, thumbnail.boundsInRoot.width)
            assertTrue(thumbnail.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(source.id, opened?.id)
            val text = nodes.single { it.config.contains(SemanticsProperties.Text) }
            assertTrue(text.boundsInRoot.height <= 60f)
            val report = java.io.File("build/reports/semantic-recommendation-reason.png")
            report.parentFile.mkdirs()
            surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { report.writeBytes(it.bytes) } }
        } finally { scene.close(); surface.close() }
    }
}

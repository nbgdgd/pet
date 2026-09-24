@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aniblaze.aggregator.source.AniskipTimings
import com.aniblaze.aggregator.source.BalancerSource
import com.aniblaze.aggregator.source.EpisodeAirDates
import com.aniblaze.aggregator.source.KodikExtractor
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jetbrains.skia.Surface
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Питомец в обычном интерфейсе реально попадает на экран (в углу, с репликой). */
class PetCornerRenderTest {
    @Test fun `угол рисуется и говорит`() = runBlocking {
        val settings = AppSettings(Files.createTempDirectory("aniblaze-pet-corner").resolve("state.json").toFile())
        val okHttp = OkHttpClient(); val http = HttpClient(okHttp); val skip = AniskipTimings(http)
        val repository = DesktopRepository(
            aggregators = emptyList(), cinemaSources = emptyList(), settings = settings,
            aniskip = skip, balancer = BalancerSource(http, KodikExtractor(okHttp)), airDates = EpisodeAirDates(http, skip),
        )
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
        val surface = Surface.makeRasterN32Premul(900, 600)
        var time = System.nanoTime()
        suspend fun frames(count: Int) { repeat(count) { delay(2); time += 20_000_000; scene.render(surface.canvas.asComposeCanvas(), time) } }
        scene.setContent {
            Box(Modifier.fillMaxSize()) {
                // Как в App: экран внутри AnimatedContent, поверх него — питомец.
                androidx.compose.animation.AnimatedContent(targetState = 1) { _ ->
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101014)),
                    ) {
                        items(40) { androidx.compose.material3.Text("карточка $it", Modifier.padding(30.dp)) }
                    }
                }
                PetHost(
                    settings = settings, repository = repository, focus = null, playing = false,
                    onOpenTitle = {}, onResume = null,
                    modifier = Modifier.zIndex(1_000f).align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
        try {
            frames(40)
            val nodes = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            val texts = nodes.filter { it.config.contains(SemanticsProperties.Text) }
                .map { n -> n.config[SemanticsProperties.Text].joinToString { it.text } to n.boundsInRoot }
            assertTrue(texts.isNotEmpty(), "питомец не сказал ни слова: $texts")
            // Реплика — в правом нижнем углу сцены, а не за её пределами.
            val (_, bounds) = texts.filter { it.first.length > 3 && !it.first.startsWith("карточка") }.first()
            assertTrue(bounds.right <= 900f && bounds.bottom <= 600f && bounds.left > 450f && bounds.top > 300f, "не в углу: $bounds")
            // И спрайт реально нарисован поверх сетки: под ним оранжевые пиксели Claude.
            val img = surface.makeImageSnapshot()
            val bmp = org.jetbrains.skia.Bitmap().also { it.allocPixels(img.imageInfo); img.readPixels(it) }
            var orange = 0
            for (x in 700 until 884 step 2) for (y in 420 until 584 step 2) {
                val c = bmp.getColor(x, y); val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                if (r > 180 && g in 80..190 && b < 90) orange++
            }
            assertTrue(orange > 50, "спрайта не видно поверх сетки: оранжевых точек $orange")
            val petButton = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .filter { it.config.contains(SemanticsActions.OnClick) && it.boundsInRoot.left > 450f }
                .maxBy { it.boundsInRoot.bottom }
            petButton.config[SemanticsActions.OnClick].action?.invoke()
            frames(40)
            settings.saveProgress(com.aniblaze.aggregator.model.Anime("test:diary", "Test", ""), 1, 60_000, 1_400_000, 60_000)
            frames(40)
            val expandedTexts = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .filter { it.config.contains(SemanticsProperties.Text) }
                .flatMap { it.config[SemanticsProperties.Text].map { text -> text.text } }
            assertTrue("Дневник просмотра" in expandedTexts, "дневник не открылся по нажатию: $expandedTexts")
            assertTrue("Сегодня: 1 мин просмотра" in expandedTexts, "дневник не обновился после просмотра: $expandedTexts")
            surface.makeImageSnapshot().use { preview ->
                preview.encodeToData()?.use { data ->
                    val file = java.io.File("build/reports/pets/pet-diary.png")
                    file.parentFile.mkdirs()
                    file.writeBytes(data.bytes)
                }
            }
            Unit
        } finally { scene.close(); surface.close() }
    }
}

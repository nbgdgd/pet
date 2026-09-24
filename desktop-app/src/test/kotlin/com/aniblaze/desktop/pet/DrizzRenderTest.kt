@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import kotlin.test.*

class DrizzRenderTest {
    @Test fun `focus flips cannot strand offer and title card expires and reopens on click`() = runBlocking {
        val owners = mutableSetOf<SemanticsOwner>()
        var focused by mutableStateOf(true)
        val listener = object : PlatformContext.SemanticsOwnerListener {
            override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners.add(semanticsOwner) }
            override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners.remove(semanticsOwner) }
            override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
            override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
        }
        val context = object : ComposeSceneContext {
            override val platformContext = object : PlatformContext by PlatformContext.Empty {
                override val semanticsOwnerListener = listener
                override val windowInfo = object : WindowInfo by PlatformContext.Empty.windowInfo {
                    override val isWindowFocused: Boolean get() = focused
                }
            }
        }
        val scene = CanvasLayersComposeScene(size = IntSize(600, 400), coroutineContext = coroutineContext, composeSceneContext = context)
        val surface = Surface.makeRasterN32Premul(600, 400)
        var time = System.nanoTime()
        suspend fun advance(ms: Long) {
            val until = System.nanoTime() + ms * 1_000_000
            while (System.nanoTime() < until) {
                delay(10); time += 20_000_000; scene.render(surface.canvas.asComposeCanvas(), time)
            }
        }
        fun nodes() = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        fun texts() = nodes().filter { it.config.contains(SemanticsProperties.Text) }
            .flatMap { it.config[SemanticsProperties.Text].map { t -> t.text } }
        val session = PetPlaybackSession()
        var offer by mutableStateOf<PetAwayOffer?>(null)
        var mounted by mutableStateOf(true)
        scene.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                if (mounted) PetPlayerHost(
                    pet = PetDef.of("drizz"), scale = 1f, xFraction = .94f, yFraction = .12f,
                    onMove = { _, _ -> }, episode = 12, episodesAvailable = 28, episodesTotal = 28,
                    playing = true, buffering = false, positionMs = 300_000, durationMs = 1_440_000,
                    openedPositionMs = 0L,
                    quietWatching = true, session = session, modifier = Modifier.fillMaxSize(),
                    context = PetPlayerContext(titleName = "Провожающая в последний путь Фрирен", rating = 5, catalogRating = 8.7, ratingMax = 10.0),
                )
                PetOfferExpiry(offer) { offer = null }
                if (offer != null) Text("Предложение возврата")
            }
        }
        try {
            advance(1_200)
            assertTrue("Ваша оценка: 5/5" in texts(), "no automatic title card: ${texts()}")
            assertTrue("Оценка каталога: 8,7/10, сука" in texts())
            assertTrue("Серия 12 из 28" in texts())
            for (node in nodes().filter { it.config.contains(SemanticsProperties.Text) }) {
                assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= 600 && node.boundsInRoot.bottom < 400)
            }
            surface.makeImageSnapshot().use { preview -> preview.encodeToData()?.use { data ->
                val file = java.io.File("build/reports/pets/drizz-title.png")
                file.parentFile.mkdirs(); file.writeBytes(data.bytes)
            } }
            offer = PetAwayOffer(261_000, 60_000, System.currentTimeMillis() + 900)
            advance(100)
            assertNotNull(offer)
            repeat(3) { focused = false; advance(100); focused = true; advance(100) }
            advance(300)
            assertNull(offer, "focus changes cancelled expiry")
            assertFalse("Предложение возврата" in texts())
            offer = PetAwayOffer(10_000, 60_000, System.currentTimeMillis() + 300)
            advance(100)
            offer = PetAwayOffer(20_000, 60_000, System.currentTimeMillis() + 800)
            advance(350)
            assertNotNull(offer, "old offer timer dismissed replacement")
            advance(550)
            assertNull(offer)
            advance(6_500)
            assertFalse(texts().any { it.startsWith("Ваша оценка") }, "title card stuck")
            mounted = false; advance(100); mounted = true; advance(1_200)
            assertFalse(texts().any { it.startsWith("Ваша оценка") }, "card repeated on remount")
            nodes().filter { it.config.contains(SemanticsActions.OnClick) && it.boundsInRoot.left > 300 }
                .maxBy { it.boundsInRoot.bottom }.config[SemanticsActions.OnClick].action?.invoke()
            advance(150)
            assertTrue("Ваша оценка: 5/5" in texts(), "pet click did not reopen card")
            nodes().first { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .contains("Закрыть информацию Drizz") }.config[SemanticsActions.OnClick].action?.invoke()
            advance(150)
            assertFalse(texts().any { it.startsWith("Ваша оценка") })
            Unit
        } finally { scene.close(); surface.close() }
    }
}

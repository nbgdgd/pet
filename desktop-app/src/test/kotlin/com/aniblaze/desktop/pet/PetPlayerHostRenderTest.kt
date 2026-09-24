@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.pet

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Регресс на поломку «питомец молчит и не реагирует на паузу»: долгоживущий тик
 * держал ПЕРВЫЕ значения позиции и паузы, и снимок никогда не менялся. Тест гоняет
 * настоящую композицию и меняет вход по ходу.
 */
class PetPlayerHostRenderTest {
    @Test fun `события обновляют композицию а перемотка не завершает сезон`() = runBlocking {
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
        val scene = CanvasLayersComposeScene(size = IntSize(600, 400), coroutineContext = coroutineContext, composeSceneContext = context)
        val surface = Surface.makeRasterN32Premul(600, 400)
        var time = System.nanoTime()
        suspend fun frames(count: Int) {
            repeat(count) { delay(2); time += 20_000_000; scene.render(surface.canvas.asComposeCanvas(), time) }
        }
        suspend fun advance(ms: Long) {
            val until = System.nanoTime() + ms * 1_000_000
            while (System.nanoTime() < until) { frames(1); delay(18) }
        }
        fun texts(): List<String> = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            .filter { it.config.contains(SemanticsProperties.Text) }
            .map { node -> node.config[SemanticsProperties.Text].joinToString(" ") { it.text } }

        val session = PetPlaybackSession()
        var position by mutableStateOf(300_000L)
        var playing by mutableStateOf(true)
        var episode by mutableStateOf(3)
        var ended by mutableStateOf(false)
        var speech by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var pauseStartedAt by mutableStateOf(0L)
        scene.setContent {
            if (mounted) {
            PetPlayerHost(
                pet = PetDef.CLAUDE,
                scale = 1f,
                xFraction = 0.9f,
                yFraction = 0.1f,
                onMove = { _, _ -> },
                episode = episode,
                episodesAvailable = 12,
                episodesTotal = 12,
                playing = playing,
                buffering = false,
                positionMs = position,
                durationMs = 24 * 60_000L,
                session = session,
                ended = ended,
                userPaused = !playing && !ended,
                pauseStartedAtMs = pauseStartedAt,
                speechEnabled = speech,
                quietWatching = true,
                openedPositionMs = 0L,
                context = PetPlayerContext(watchedEpisodes = 3),
                modifier = Modifier.fillMaxSize(),
            )
            }
        }
        try {
            advance(1100)
            position = 24 * 60_000L
            advance(1100)
            assertTrue(texts().none { it.contains("досмотр") || it.contains("Сезон") }, "перемотка стала завершением: ${texts()}")
            playing = false
            ended = true
            frames(5)
            session.recordCompletion(3, PetEvent.EPISODE_DONE, true)
            advance(1100)
            assertTrue(texts().any { it == "Серия 3 досмотрена" }, "потеряно событие: ${texts()}")
            speech = false
            frames(5)
            assertTrue(texts().isEmpty(), "отключённые реплики остались: ${texts()}")
            speech = true
            episode = 4
            ended = false
            position = 300_000
            advance(4300)
            assertTrue(texts().any { it.contains("Просмотрено 3/12") }, "новая серия сохранила старые данные: ${texts()}")
            assertTrue(texts().none { it.contains("Серия 3 досмотрена") })
            // Уход с экрана не начинает уже долгую ручную паузу заново.
            mounted = false
            frames(5)
            pauseStartedAt = System.currentTimeMillis() - PetDirector.SLEEP_AFTER_MS - 1_000
            mounted = true
            advance(1100)
            assertTrue(texts().any { it in PetPhrases.IDLE_LONG }, "долгая пауза потерялась после возврата: ${texts()}")
        } finally { scene.close(); surface.close() }
    }
}

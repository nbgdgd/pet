@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.*
import com.aniblaze.aggregator.source.*
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real Compose tree rendered offscreen: no windows, mouse or Computer Use. */
class CardStabilityRenderTest {
    private class Source(val cards: List<Anime>) : ContentAggregator {
        override val name = "Anixart"
        val calls = AtomicInteger()
        override fun ownsContentId(contentId: String) = true
        override suspend fun search(query: String) = cards
        override suspend fun trending() = cards
        override suspend fun catalog(category: String): List<Anime> { calls.incrementAndGet(); return cards }
        override suspend fun catalogPage(sort: Int, page: Int): List<Anime> { calls.incrementAndGet(); return cards }
        override suspend fun latestReleases(page: Int) = cards
        override suspend fun extractContent(contentId: String, segment: Int): ContentResult? = null
        override suspend fun getContentSegments(contentId: String) = emptyList<Segment>()
        override suspend fun validateSource(contentId: String) = true
    }

    @Test fun `favorite and watched toggles preserve mounted poster nodes scroll and catalog requests`() = runBlocking {
        for (seeded in listOf(false, true)) {
        val dir = Files.createTempDirectory("card-render")
        val poster = dir.resolve("poster.png").toFile()
        poster.writeBytes(java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aMioAAAAASUVORK5CYII="))
        val cards = (0..39).map { i -> Anime("ax:${100+i}", "История ${('А'.code+i).toChar()} путь", poster.absolutePath,
            genres = "спорт", rating = 4.5, ratingVotes = 1000) }
        val source = Source(cards)
        val settings = AppSettings(dir.resolve("state.json").toFile())
        settings.setGridColumns(4)
        if (seeded) settings.toggleFavorite(Anime("ax:1", "Любимая основа", poster.absolutePath, genres = "спорт"))
        val client = OkHttpClient()
        val http = HttpClient(client)
        val skip = AniskipTimings(http)
        val repo = DesktopRepository(listOf(source), emptyList(), settings, aniskip = skip,
            balancer = BalancerSource(http, KodikExtractor(client)), airDates = EpisodeAirDates(http, skip))
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
        val scene = CanvasLayersComposeScene(size = IntSize(1000, 700), coroutineContext = coroutineContext, composeSceneContext = context)
        val surface = Surface.makeRasterN32Premul(1000, 700)
        scene.setContent {
            AniBlazeTheme {
                CompositionLocalProvider(LocalAppSettings provides settings) {
                    RecommendationsScreen(repo, settings) {}
                }
            }
        }
        suspend fun frames(count: Int = 12) {
            repeat(count) { delay(10); scene.render(surface.canvas.asComposeCanvas(), System.nanoTime()) }
        }
        fun nodes() = owners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        fun posters() = nodes().filter { it.config.contains(SemanticsProperties.ContentDescription) }
            .mapNotNull { node ->
                val title = node.config[SemanticsProperties.ContentDescription].singleOrNull()
                if (cards.any { it.title == title }) title!! to (node.id to node.boundsInRoot) else null
            }.toMap()
        try {
            frames(50)
            assertTrue(posters().size >= 4, "Real poster nodes must be mounted")
            nodes().first { it.config.contains(SemanticsActions.ScrollBy) }
                .config[SemanticsActions.ScrollBy].action?.invoke(0f, 480f)
            frames(30)
            val before = posters()
            val requests = source.calls.get()
            assertTrue(before.isNotEmpty())
            val target = cards.first { it.title in before.keys }
            settings.toggleFavorite(target)
            frames()
            assertEquals(before, posters(), "Add favourite must not replace nodes or move cards")
            settings.toggleFavorite(target)
            frames()
            assertEquals(before, posters(), "Remove favourite must not replace nodes or move cards")
            settings.setWatched(target.id, 1, true)
            frames(30)
            assertEquals(before, posters(), "Watched mark must not replace nodes or move cards")
            assertEquals(requests, source.calls.get(), "Feedback must not refetch catalogue")
        } finally { scene.close(); surface.close(); settings.flush() }
        }
    }
}

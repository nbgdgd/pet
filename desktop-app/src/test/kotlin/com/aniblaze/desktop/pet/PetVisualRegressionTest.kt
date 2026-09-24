@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.aniblaze.desktop.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Render actual Compose sprites, including reduced-motion resting poses. */
class PetVisualRegressionTest {
    @Test fun `all pets render idle sit sleep and celebration`() = runBlocking {
        val scene = CanvasLayersComposeScene(size = IntSize(900, 680), coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(900, 680)
        try {
            scene.setContent {
                Row(Modifier.fillMaxSize().background(Color(0xFF181820))) {
                    PetDef.ALL.forEach { pet ->
                        Column(Modifier.width(180.dp).padding(12.dp)) {
                            Text(pet.displayName, color = Color.White)
                            listOf(PetAction.IDLE, PetAction.SIT, PetAction.SLEEP, PetAction.CELEBRATE).forEach { action ->
                                Text(action.name, color = Color.LightGray)
                                PetSprite(pet, action, Modifier.size(120.dp, 120.dp), animate = false)
                            }
                        }
                    }
                }
            }
            var time = System.nanoTime()
            repeat(20) { delay(3); time += 20_000_000; scene.render(surface.canvas.asComposeCanvas(), time) }
            surface.makeImageSnapshot().use { image ->
                assertNotNull(image.encodeToData()).use { data ->
                    val file = File("build/reports/pets/pet-poses.png")
                    file.parentFile.mkdirs()
                    file.writeBytes(data.bytes)
                }
            }
        } finally { scene.close(); surface.close() }
    }
}

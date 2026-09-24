package com.aniblaze.desktop.pet

import com.aniblaze.desktop.AppSettings
import java.nio.file.Files
import kotlin.test.*

class PetPositionTest {
    @Test fun `card stays near sprite and within window`() {
        assertEquals(82f, petCardTop(210f, 80f, 120f, 600f))
        assertEquals(148f, petCardTop(60f, 80f, 120f, 600f))
        for (top in listOf(0f, 10f, 190f, 500f)) {
            assertTrue(petCardTop(top, 80f, 120f, 300f) in 0f..180f)
        }
    }
    @Test fun `desktop anchor keeps bottom edge when card opens and clamps on resize`() {
        assertEquals(480f, petDesktopCoordinate(1f, 600f, 120f))
        assertEquals(180f, petDesktopCoordinate(1f, 600f, 420f))
        assertEquals(0f, petDesktopCoordinate(.1f, 300f, 400f))
        assertEquals(180f, petDesktopCoordinate(Float.NaN, 300f, 120f))
    }
    @Test fun `activity and desktop position persist independently from player`() {
        val file = Files.createTempDirectory("drizz-position").resolve("state.json").toFile()
        val settings = AppSettings(file)
        settings.setPetPlayerPosition(.3f, .4f)
        settings.setPetDesktopPosition(.6f, .7f)
        settings.setDrizzActivity("active")
        settings.flush()
        val restored = AppSettings(file).state.value
        assertEquals("active", restored.drizzActivity)
        assertEquals(.6f, restored.petDesktopX)
        assertEquals(.7f, restored.petDesktopY)
        assertEquals(.3f, restored.petPlayerX)
        assertEquals(.4f, restored.petPlayerY)
        assertEquals(15_000L, drizzMotionInterval(0, "active"))
        assertEquals(30_000L, drizzMotionInterval(0, "normal"))
        assertEquals(90_000L, drizzMotionInterval(0, "calm"))
    }
}

package com.aniblaze.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoEnhanceLogicTest {

    @Test
    fun `off completely removes native sharpening`() {
        assertEquals(emptyList(), VideoEnhance.vlcOptions(VideoEnhance.Level.OFF))
    }

    @Test
    fun `native levels enable actual VLC sharpen filter`() {
        val medium = VideoEnhance.vlcOptions(VideoEnhance.Level.LIGHT)
        val strong = VideoEnhance.vlcOptions(VideoEnhance.Level.STRONG)

        assertEquals(":video-filter=sharpen", medium.first())
        assertEquals(":video-filter=sharpen", strong.first())
        assertTrue(medium.last().startsWith(":sharpen-sigma="))
        assertTrue(strong.last().startsWith(":sharpen-sigma="))
        assertTrue(VideoEnhance.Level.STRONG.nativeSigma!! > VideoEnhance.Level.LIGHT.nativeSigma!!)
    }

    @Test
    fun `unknown saved value safely falls back to off`() {
        assertEquals(VideoEnhance.Level.OFF, VideoEnhance.Level.of("old-or-corrupt-value"))
    }
}

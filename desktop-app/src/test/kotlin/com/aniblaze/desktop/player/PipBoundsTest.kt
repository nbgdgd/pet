package com.aniblaze.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Положение окошка «картинка в картинке»: разбор строки и защита от мусора. */
class PipBoundsTest {
    @Test fun `разбор x,y,w,h - целые, мусор и слишком маленькое окно - null`() {
        assertEquals(listOf(1200, 700, 420, 236), parsePipBounds("1200, 700,420,236"))
        assertNull(parsePipBounds(""))
        assertNull(parsePipBounds("1,2,3"))
        assertNull(parsePipBounds("a,b,c,d"))
        assertNull(parsePipBounds("0,0,100,60"), "уже минимума — по умолчанию")
    }
}

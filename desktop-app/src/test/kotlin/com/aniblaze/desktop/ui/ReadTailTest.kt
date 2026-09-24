package com.aniblaze.desktop.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadTailTest {
    @Test fun `хвост файла - последние строки без пустых, нет файла - пусто`() {
        val f = Files.createTempFile("aniblaze-log", ".log").toFile()
        f.writeText((1..10).joinToString("\n") { "line $it" } + "\n\n")
        assertEquals(listOf("line 8", "line 9", "line 10"), readTail(f, 3))
        assertEquals(10, readTail(f, 400).size)
        assertEquals(emptyList(), readTail(java.io.File(f.parentFile, "missing-${System.nanoTime()}.log"), 3))
    }
}

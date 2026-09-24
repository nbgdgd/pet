package com.aniblaze.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestartTest {

    @Test
    fun `restart flushes before launch and exit`() {
        val calls = mutableListOf<String>()

        val restarted = restartApplication(
            flush = { calls += "flush"; true },
            launch = { calls += "launch"; true },
            exit = { calls += "exit" },
        )

        assertTrue(restarted)
        assertEquals(listOf("flush", "launch", "exit"), calls)
    }

    @Test
    fun `failed flush keeps current process running`() {
        var launched = false
        var exited = false

        val restarted = restartApplication(
            flush = { false },
            launch = { launched = true; true },
            exit = { exited = true },
        )

        assertFalse(restarted)
        assertFalse(launched)
        assertFalse(exited)
    }

    @Test
    fun `failed launch keeps current process running after durable flush`() {
        var exited = false

        val restarted = restartApplication(
            flush = { true },
            launch = { false },
            exit = { exited = true },
        )

        assertFalse(restarted)
        assertFalse(exited)
    }
}

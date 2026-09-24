package com.aniblaze.desktop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HomeLoadingTest {
    @Test
    fun `network failure is returned to the feed instead of cancelling its collector`() = runBlocking {
        val failed = homePageAttempt<List<Int>> { error("offline") }
        assertTrue(failed.isFailure)

        val retried = homePageAttempt { listOf(1, 2, 3) }
        assertEquals(listOf(1, 2, 3), retried.getOrThrow())
    }

    @Test
    fun `real coroutine cancellation is never swallowed`() = runBlocking {
        assertFailsWith<CancellationException> {
            homePageAttempt<Unit> { throw CancellationException("screen left") }
        }
        Unit
    }

    @Test
    fun `tail page retries while the scroll position remains unchanged`() = runBlocking {
        var calls = 0
        val result = homePageWithRetry(attempts = 3, initialDelayMs = 0) {
            calls++
            if (calls == 1) error("temporary outage")
            listOf(42)
        }

        assertEquals(2, calls)
        assertEquals(listOf(42), result.getOrThrow())
    }
}

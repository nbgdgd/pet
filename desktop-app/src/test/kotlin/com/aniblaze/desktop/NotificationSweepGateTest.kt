package com.aniblaze.desktop

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationSweepGateTest {
    @Test
    fun `manual and background notification sweeps cannot overlap`() = runBlocking {
        val gate = NotificationSweepGate()
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        (1..8).map {
            async(Dispatchers.Default) {
                gate.serial {
                    val now = active.incrementAndGet()
                    maximum.updateAndGet { old -> maxOf(old, now) }
                    delay(5)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maximum.get())
        assertEquals(0, active.get())
    }
}

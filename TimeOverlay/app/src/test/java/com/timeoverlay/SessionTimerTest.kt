package com.timeoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GRACE = 30_000L
private const val A = "com.app.a"
private const val B = "com.app.b"

class SessionTimerTest {

    @Test
    fun `тот же пакет подряд — счёт продолжается`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(A, 0)
        timer.onForeground(A, 5_000)
        assertEquals(10_000, timer.elapsed(10_000))
    }

    @Test
    fun `нет пакета — нет сессии`() {
        val timer = SessionTimer(GRACE)
        assertEquals(0, timer.elapsed(1_000))
        assertFalse(timer.isRunning)
    }

    @Test
    fun `смена пакета обнуляет счёт для нового`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(A, 0)
        timer.onForeground(B, 20_000)
        assertEquals(0, timer.elapsed(20_000))
        assertEquals(3_000, timer.elapsed(23_000))
    }

    @Test
    fun `возврат внутри грации продолжает счёт`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(A, 0)
        timer.onForeground(B, 10_000)      // ушёл из A, накоплено 10 сек
        timer.onForeground(A, 20_000)      // вернулся через 10 сек — грация не истекла
        assertEquals(10_000, timer.elapsed(20_000))
        assertEquals(15_000, timer.elapsed(25_000))
    }

    @Test
    fun `возврат после грации начинает сессию заново`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(A, 0)
        timer.onForeground(B, 10_000)
        timer.onForeground(A, 10_000 + GRACE + 1)
        assertEquals(0, timer.elapsed(10_000 + GRACE + 1))
    }

    @Test
    fun `цепочка A B A восстанавливает сессию A а не B`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(A, 0)
        timer.onForeground(B, 8_000)       // A припаркован с 8 сек
        timer.onForeground(A, 12_000)      // B припаркован с 4 сек, A продолжается
        assertEquals(8_000, timer.elapsed(12_000))
        timer.onForeground(B, 14_000)      // возврат в B внутри грации
        assertEquals(4_000, timer.elapsed(14_000))
    }

    @Test
    fun `null паузит счёт и возврат внутри грации его продолжает`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(A, 0)
        timer.onForeground(null, 7_000)    // экран погас
        assertEquals(0, timer.elapsed(7_000))
        assertFalse(timer.isRunning)
        timer.onForeground(A, 12_000)      // экран включили
        assertEquals(7_000, timer.elapsed(12_000))
        assertTrue(timer.isRunning)
    }

    @Test
    fun `null после грации сбрасывает сессию`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(A, 0)
        timer.onForeground(null, 5_000)
        timer.onForeground(A, 5_000 + GRACE + 1)
        assertEquals(0, timer.elapsed(5_000 + GRACE + 1))
    }

    @Test
    fun `повторный null ничего не ломает`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(null, 1_000)
        timer.onForeground(null, 2_000)
        assertEquals(0, timer.elapsed(3_000))
    }

    @Test
    fun `изменение грации на лету влияет на следующий возврат`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(A, 0)
        timer.onForeground(B, 10_000)
        timer.graceMillis = 1_000
        timer.onForeground(A, 12_000)      // 2 сек паузы — уже больше новой грации
        assertEquals(0, timer.elapsed(12_000))
    }

    @Test
    fun `reset стирает всё`() {
        val timer = SessionTimer(GRACE)
        timer.onForeground(A, 0)
        timer.reset()
        assertFalse(timer.isRunning)
        timer.onForeground(A, 1_000)
        assertEquals(0, timer.elapsed(1_000))
    }
}

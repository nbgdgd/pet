package com.aniblaze.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.test.Test

/**
 * Отсечка «источник лежит».
 *
 * Повод — 21.08, когда `api.anixart.tv` отдавал 502. Приложение не падало: оно ЖДАЛО.
 * Перебор зеркал стоит одного таймаута каждое, а зеркала — это тот же Anixart, поэтому
 * ответить они не могли по определению: 14.5 секунды на прокси плюс столько же на
 * origin, и так на каждую строчку каталога и каждый список серий. Наружу это «ничего не
 * работает».
 */
class OutageGateTest {

    private var clock = 1_000L
    private fun gate(streak: Int = 3, pauseMs: Long = 30_000L) = OutageGate(streak, pauseMs) { clock }

    @Test
    fun `пока неудач мало, ходим в сеть как обычно`() {
        val gate = gate()
        assertFalse(gate.isOpen())
        gate.recordFailure()
        gate.recordFailure()
        assertFalse("две неудачи — это ещё не приговор источнику", gate.isOpen())
    }

    @Test
    fun `серия неудач подряд закрывает хост`() {
        val gate = gate()
        repeat(3) { gate.recordFailure() }
        assertTrue(gate.isOpen())
    }

    @Test
    fun `пауза сама кончается`() {
        val gate = gate()
        repeat(3) { gate.recordFailure() }
        clock += 29_999
        assertTrue(gate.isOpen())
        clock += 2
        assertFalse("после паузы обязана быть новая попытка, иначе источник не воскреснет", gate.isOpen())
    }

    @Test
    fun `успех снимает паузу немедленно`() {
        val gate = gate()
        repeat(3) { gate.recordFailure() }
        assertTrue(gate.isOpen())
        gate.recordSuccess()
        assertFalse("источник ответил — ждать оставшиеся полминуты незачем", gate.isOpen())
    }

    @Test
    fun `успех обнуляет счёт, а не только паузу`() {
        // Иначе редкие одиночные сбои копились бы неделями и однажды закрывали живой
        // хост на ровном месте.
        val gate = gate()
        gate.recordFailure()
        gate.recordFailure()
        gate.recordSuccess()
        gate.recordFailure()
        gate.recordFailure()
        assertFalse(gate.isOpen())
    }
}

package com.aniblaze.desktop.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Резкая перемотка ползунком и сохранение точки просмотра.
 *
 * ОДНА ПРИЧИНА НА ДВЕ ЖАЛОБЫ. Все команды libVLC идут через ОДИН поток. Каждый
 * `setTime` на адаптивном HLS заставляет демультиплексор заново тянуть и
 * декодировать сегмент, то есть занимает этот поток на секунды. Пока он занят,
 * опрос позиции стоит в очереди за ним и не отвечает — а сторож зависания считает
 * молчание опроса доказательством того, что libVLC встала, и бросает экземпляр.
 *
 * Отсюда обе жалобы разом: «резко скипаю полоску — поток встаёт» и «после закрытия
 * не запоминается тайминг». Второе — следствие первого: пока опрос не отвечает,
 * прежний код не доходил до сохранения прогресса вовсе, и точка на диске оставалась
 * той, что была ДО перемотки.
 */
class SeekAndProgressTest {

    // ---- очередь перемоток ----

    @Test
    fun `серия перемоток не копится в очереди`() {
        // ГЛАВНОЕ. Сто перемоток подряд не имеют права стать сотней обращений к
        // libVLC: столько setTime на HLS — это минуты занятого потока команд.
        val executor = Executors.newSingleThreadExecutor()
        val calls = AtomicInteger(0)
        val slow = CountDownLatch(1)
        val command = ConflatedLongCommand(executor) { _ ->
            calls.incrementAndGet()
            // Первый вызов держит поток, пока идут остальные заявки, — ровно как
            // настоящий setTime на HLS.
            if (calls.get() == 1) slow.await(2, TimeUnit.SECONDS)
        }
        repeat(100) { command.submit(it * 1000L) }
        slow.countDown()
        executor.shutdown()
        assertTrue("очередь не разошлась", executor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue("обращений к libVLC: ${calls.get()} — очередь копилась", calls.get() <= 3)
    }

    @Test
    fun `выполняется ПОСЛЕДНЯЯ заявка, а не первая`() {
        val executor = Executors.newSingleThreadExecutor()
        val seen = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val hold = CountDownLatch(1)
        val started = CountDownLatch(1)
        val command = ConflatedLongCommand(executor) { value ->
            seen += value
            if (seen.size == 1) { started.countDown(); hold.await(2, TimeUnit.SECONDS) }
        }
        command.submit(1_000L)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        // Пока первая держит поток, приходят ещё три: до libVLC обязана дойти
        // только последняя — промежуточные точки зрителю уже не нужны.
        command.submit(2_000L)
        command.submit(3_000L)
        command.submit(9_000L)
        hold.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("первая заявка обязана дойти", 1_000L, seen.first())
        assertEquals("и последняя тоже", 9_000L, seen.last())
        assertTrue("промежуточные не нужны: $seen", seen.size <= 3)
    }

    @Test
    fun `падение одной заявки не останавливает очередь`() {
        val executor = Executors.newSingleThreadExecutor()
        val seen = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val command = ConflatedLongCommand(executor) { value ->
            if (value == 1L) error("libVLC сказала нет")
            seen += value
        }
        command.submit(1L)
        Thread.sleep(50)
        command.submit(2L)
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf(2L), seen)
    }

    // ---- терпение сторожа ----

    @Test
    fun `во время перемотки сторож зависания терпит дольше`() {
        // setTime на HLS ЗАКОННО занимает поток команд на секунды. Считать это
        // зависанием — значит бросать живой плеер ровно тогда, когда зритель активно
        // им пользуется.
        assertTrue(
            "во время перемотки терпения обязано быть больше",
            wedgeTickLimit(seekInFlight = true) > wedgeTickLimit(seekInFlight = false),
        )
    }

    @Test
    fun `сторож зависания не отключается перемоткой совсем`() {
        // Если libVLC встала ВНУТРИ setTime, из этого состояния она сама не выйдет.
        // Терпение растёт, но остаётся конечным.
        assertTrue(wedgeTickLimit(seekInFlight = true) < 60)
    }

    // ---- сохранение точки просмотра ----

    @Test
    fun `точка сохраняется, даже когда опрос не отвечает`() {
        // ВТОРАЯ ЖАЛОБА. Прежде сохранение стояло ПОСЛЕ успешного опроса, и во время
        // зависания не выполнялось ни разу: на диске оставалась точка до перемотки.
        // Позиция интерфейса известна и без опроса — её и надо писать.
        assertTrue(
            shouldPersistProgress(
                positionMs = 600_000L,
                lengthMs = 1_400_000L,
                sinceLastSaveNanos = 3_000_000_000L,
                pollAnswering = false,
            ),
        )
    }

    @Test
    fun `точка не пишется чаще, чем нужно`() {
        assertTrue(
            !shouldPersistProgress(
                positionMs = 600_000L,
                lengthMs = 1_400_000L,
                sinceLastSaveNanos = 200_000_000L,
                pollAnswering = true,
            ),
        )
    }

    @Test
    fun `без длительности и позиции писать нечего`() {
        assertTrue(!shouldPersistProgress(0L, 1_400_000L, 10_000_000_000L, true))
        assertTrue(!shouldPersistProgress(600_000L, 0L, 10_000_000_000L, true))
    }

    @Test
    fun `окно потерь не больше двух секунд`() {
        // Насильное закрытие не даёт выполниться никакому коду приложения. Всё, что
        // переживает такое завершение, — уже записанное на диск.
        assertTrue(shouldPersistProgress(600_000L, 1_400_000L, 2_000_000_000L, true))
    }
}

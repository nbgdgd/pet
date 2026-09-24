package com.aniblaze.desktop.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Второй запуск, влетающий в ещё открывающийся первый.
 *
 * ИМЕННО ЭТО КЛИНИТ ПОТОК КОМАНД libVLC. Бросок зависшего экземпляра делает две вещи
 * разом: сам поднимает новый плеер И просит экран за свежей ссылкой. Ссылка приезжает
 * через полсекунды — и, поскольку адрес входит в ключи эффекта запуска, эффект
 * перезапускается поверх ещё не открывшегося потока. Журнал 17 августа, 01:28:
 *
 *     01:28:58.098  play.request  url=p14…f8b7e8fd   ← первый
 *     01:28:58.271  vlc.playing                       ← ещё открывается
 *     01:28:58.532  play.request  url=p12…f8b7e8fd   ← второй, через 0.43 с
 *     01:29:00.078  vlc.callTimeout                   ← поток команд заклинило
 *     01:29:07.770  vlc.abandon   commandThreadStuck
 *
 * Обрати внимание на адреса: у них ОДИН И ТОТ ЖЕ файл и подпись, разный только хост
 * раздачи (p14 против p12). То есть ради смены зеркала мы рушили живой запуск.
 */
class PlayRestartGuardTest {

    private val second = 1_000_000_000L

    @Test
    fun `новая серия запускается всегда`() {
        assertTrue(
            shouldRestartPlayback(
                sameMediaKey = false,
                sincePreviousPlayNanos = 0L,
                previousStarted = false,
            ),
        )
    }

    @Test
    fun `свежий адрес не рушит ещё открывающийся поток`() {
        // Ровно замеренный случай: 0.43 с после первого запуска, кадров ещё не было.
        assertFalse(
            shouldRestartPlayback(
                sameMediaKey = true,
                sincePreviousPlayNanos = 430_000_000L,
                previousStarted = false,
            ),
        )
    }

    @Test
    fun `если поток так и не открылся — новый адрес всё-таки пробуем`() {
        // Иначе мёртвая ссылка заперла бы серию навсегда: отсрочка обязана истечь.
        assertTrue(
            shouldRestartPlayback(
                sameMediaKey = true,
                sincePreviousPlayNanos = 4 * second,
                previousStarted = false,
            ),
        )
    }

    @Test
    fun `у играющего потока смена адреса разрешена сразу`() {
        // Это смена качества или озвучки по воле зрителя — ждать нечего и незачем.
        assertTrue(
            shouldRestartPlayback(
                sameMediaKey = true,
                sincePreviousPlayNanos = 100_000_000L,
                previousStarted = true,
            ),
        )
    }
}

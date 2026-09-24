package com.aniblaze.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сторож «нет кадров»: замер по журналу 17 августа, окно 00:42:26–00:43:02.
 *
 * Что там было (player-diagnostics.log):
 *
 *     00:42:28.9  vlc.statistics decoded=58,79; displayed=8,40   ← последние кадры
 *     00:42:33.9  vlc.statistics decoded=0,00;  displayed=0,00   ← декодер встал
 *     00:42:36.4  stall.started  ticks=1; position=311658        ← позиция замерла
 *     00:42:49.0  frames.missing afterSec=20                     ← первая реакция
 *     00:42:50.8  vlc.callTimeout                                ← через 1.9 с
 *     00:43:02.4  vlc.abandon    reason=commandThreadStuck
 *
 * То есть от остановки декодера до развязки прошло 29 секунд, из них 15 — до
 * первой попытки хоть что-то сделать. Тесты держат оба конца: срабатывать в
 * первые 10 секунд и не срабатывать там, где по журналу всё было в порядке.
 */
class NoFramesWatchdogTest {
    @Test
    fun `single repeated clock sample is not logged as a recovered stall`() {
        // VLC's public clock commonly advances in ~1 s steps while the UI polls more
        // often. One repeated sample is therefore normal aliasing, not a stall event.
        assertFalse(stallRecoveryIsDiagnostic(1))
        assertTrue(stallRecoveryIsDiagnostic(2))
    }

    /** Через сколько миллисекунд молчания декодера сторож потребовал вмешательства. */
    private fun tripAfterMs(
        ticks: Int,
        tickMs: Long,
        playing: (Int) -> Boolean = { true },
        decodedGrew: (Int) -> Boolean = { false },
        positionMoved: (Int) -> Boolean = { false },
        seekInFlight: (Int) -> Boolean = { false },
        everStarted: (Int) -> Boolean = { true },
        /** Наносекунд с последней перемотки на каждом тике. По умолчанию — давно. */
        sinceSeekNanos: (Int) -> Long = { 60_000_000_000L },
    ): Long? {
        var silenceMs = 0L
        for (i in 0 until ticks) {
            val tick = framelessWatchdogTick(
                playing = playing(i),
                decodedGrew = decodedGrew(i),
                positionMoved = positionMoved(i),
                seekInFlight = seekInFlight(i),
                everStarted = everStarted(i),
                silenceMs = silenceMs,
                sincePreviousTickMs = tickMs,
                sinceSeekNanos = sinceSeekNanos(i),
            )
            if (tick.replay) return tick.silenceMs
            silenceMs = tick.silenceMs
        }
        return null
    }

    @Test
    fun `происшествие 00-42 разрешается в первые десять секунд, а не за двадцать`() {
        // Кадры кончились в момент 0. Позиция по журналу шла ещё около пяти секунд
        // (последние кадры 00:42:28.9, stall.started 00:42:36.4) и лишь потом встала.
        val trip = tripAfterMs(
            ticks = 30,
            tickMs = 2_000L,
            positionMoved = { i -> (i + 1) * 2_000L <= 5_000L },
        )
        assertEquals(6_000L, trip, "сторож обязан вмешаться на шестой секунде тишины")
        assertTrue(trip!! <= 10_000L, "20 секунд ожидания — это и есть замерший кадр у зрителя")
    }

    @Test
    fun `счёт тишины идёт с остановки декодера, а не с остановки позиции`() {
        // Позиция замирает на пять секунд позже кадров: аудиобуфер доигрывает.
        // Если начинать счёт с неё, к порогу придём на пять секунд позже — ровно
        // та задержка, из-за которой лечение опоздало к живому потоку команд.
        val trip = tripAfterMs(
            ticks = 30,
            tickMs = 1_000L,
            positionMoved = { i -> (i + 1) * 1_000L <= 5_000L },
        )
        assertEquals(6_000L, trip)
    }

    @Test
    fun `молчание декодера при живой позиции поток не трогает`() {
        // Замерено: 00:24:42+00:24:47 и 00:37:18+00:37:23 — по два нулевых замера
        // подряд (это ≥10 с без единого кадра), оба раза вокруг app.hideToTray,
        // оба раза прошло само. Позиция при этом шла: ни одного stall.watchdog.
        assertEquals(null, tripAfterMs(ticks = 30, tickMs = 2_000L, positionMoved = { true }))
    }

    @Test
    fun `пауза, идущие кадры и незаземлившаяся перемотка обнуляют счёт`() {
        assertEquals(null, tripAfterMs(ticks = 30, tickMs = 2_000L, playing = { false }))
        assertEquals(null, tripAfterMs(ticks = 30, tickMs = 2_000L, decodedGrew = { true }))
        assertEquals(null, tripAfterMs(ticks = 30, tickMs = 2_000L, seekInFlight = { true }))
    }

    @Test
    fun `одиночный кадр посреди тишины начинает счёт заново`() {
        // На пятом замере декодер отдал кадр — значит поток жив, и терпение
        // обязано начаться с нуля, иначе сторож добьёт медленную, но живую отдачу.
        val trip = tripAfterMs(ticks = 5, tickMs = 2_000L, decodedGrew = { i -> i == 2 })
        assertEquals(null, trip)
    }

    @Test
    fun `только что поднятый плеер не убивают за то, что он ещё стартует`() {
        // ЭТО СЛУЧИЛОСЬ ВЖИВУЮ 17 августа, 01:09. Сторож справедливо бросил
        // зависший экземпляр — и тут же взялся за свежий, который ещё открывал
        // поток и по определению не отдал ни кадра:
        //
        //     01:09:17.8  frames.missing afterSec=6   → abandon, resumeAt=503878
        //     01:09:18.5  play.request   resumeMs=503878   ← новый экземпляр
        //     01:09:19.8 … 01:09:37.8    decoded=0 десять замеров подряд
        //     01:09:37.8  frames.missing afterSec=6; position=0
        //     01:09:37.8  vlc.abandon    resumeAt=0    ← точка просмотра ПОТЕРЯНА
        //     01:09:47.4  vlc.abandon    generation=2
        //
        // Круг замкнулся: каждый новый экземпляр убивали через шесть секунд после
        // рождения, ни один не успевал доиграть до первого кадра.
        //
        // «Кадров нет и позиция стоит» у ещё не стартовавшего плеера выполняется
        // ВСЕГДА. Пока он не отдал ни одного кадра, судить о нём этому сторожу
        // нечем — за старт отвечает отдельная отсечка play.neverStarted.
        assertEquals(
            null,
            tripAfterMs(ticks = 20, tickMs = 2_000L, everStarted = { false }),
        )
    }

    @Test
    fun `после первого кадра сторож включается как обычно`() {
        // Обратная сторона: отсрочка не должна стать вечной индульгенцией.
        assertTrue((tripAfterMs(ticks = 20, tickMs = 2_000L, everStarted = { true }) ?: Long.MAX_VALUE) <= 10_000L)
    }
    @Test
    fun `после перемотки сторож молчит, пока демультиплексор догружает`() {
        // Журнал 24.08: зритель дважды дёрнул ползунок, перемотка приземлилась за
        // 0.4 с — и сторож бросил ЖИВОЙ экземпляр ещё через шесть секунд, пока
        // адаптивный демультиплексор заново тянул сегмент. Замер из этого же файла:
        // на догрузку после перемотки уходит от полутора до шести секунд.
        //
        // Отсчёт по ЧАСАМ от выдачи перемотки, как у сторожа зависшей позиции: признак
        // «в полёте» снимается на приземлении позиции и кадров не гарантирует.
        val trip = tripAfterMs(
            ticks = 4,
            tickMs = 2_000,
            sinceSeekNanos = { i -> (i + 1) * 2_000_000_000L },
        )
        assertNull(trip, "первые девять секунд после перемотки — не обрыв")
    }

    @Test
    fun `отсрочка после перемотки не вечная`() {
        // Иначе перемотка стала бы способом отключить сторожа насовсем.
        val trip = tripAfterMs(
            ticks = 8,
            tickMs = 2_000,
            sinceSeekNanos = { i -> (i + 1) * 2_000_000_000L },
        )
        assertTrue(trip != null, "после отсрочки приговор обязан вынестись")
    }

    // ---- «открылся, но не отдал ни кадра» ------------------------------------------

    @Test
    fun `экземпляр без единого кадра признаётся мёртвым`() {
        // Дыра между двумя сторожами, журнал 24.08: libVLC сказала vlc.playing, доложила
        // позицию и двигала её (ни одного stall.started), а decoded=0,00 шло шестьдесят
        // замеров подряд — две минуты неподвижной картинки без единого приговора.
        assertTrue(
            framesNeverArrived(playing = true, everDecoded = false, seekInFlight = false, silenceMs = 20_000),
            "двадцать секунд «воспроизведения» без кадров — это мёртвый экземпляр",
        )
    }

    @Test
    fun `открытию потока дают время`() {
        // На открытие адаптивного HLS с холодным буфером законно уходит до десятка
        // секунд. Судить раньше — значит убивать здоровые запуски.
        assertFalse(
            framesNeverArrived(playing = true, everDecoded = false, seekInFlight = false, silenceMs = 12_000),
            "двенадцать секунд — это ещё открытие, а не смерть",
        )
    }

    @Test
    fun `отдавший хоть кадр судится обычным сторожем`() {
        // Иначе два сторожа спорят об одном экземпляре разными сроками.
        assertFalse(
            framesNeverArrived(playing = true, everDecoded = true, seekInFlight = false, silenceMs = 60_000),
        )
    }

    @Test
    fun `на паузе и в перемотке приговора нет`() {
        assertFalse(
            framesNeverArrived(playing = false, everDecoded = false, seekInFlight = false, silenceMs = 60_000),
        )
        assertFalse(
            framesNeverArrived(playing = true, everDecoded = false, seekInFlight = true, silenceMs = 60_000),
        )
    }

}

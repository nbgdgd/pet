package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.TitleComment
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Сессия показа комментариев. Каждый тест здесь — ПРОШЛАЯ ПОЛОМКА, доведённая до
 * воспроизводимой формы: качели «всё дублируется ↔ ничего нет» жили именно в этих
 * местах.
 */
class DanmakuSessionTest {

    private fun texts(n: Int): List<String> =
        (1..n).map { "Осмысленная реплика зрителя номер $it про эту серию" }

    private fun session(
        texts: List<String>,
        rate: DanmakuRate = DanmakuRate.NORMAL,
        seed: Long = 42L,
        key: String = "ax:1:5",
    ) = DanmakuSession(key, texts.size, false, rate, texts, seed)

    /** Прокрутить [minutes] минут просмотра; вернуть показанное в порядке выдачи. */
    private fun play(
        s: DanmakuSession,
        minutes: Int,
        hint: (tick: Int) -> Long = { 1_435_000L },
        playing: (tick: Int) -> Boolean = { true },
    ): List<Pair<Long, String>> {
        val shown = mutableListOf<Pair<Long, String>>()
        var watched = 0L
        val ticks = minutes * 60 * 2 // тик 500 мс
        for (t in 0 until ticks) {
            if (!playing(t)) continue
            watched += 500
            s.accumulate(500)
            val text = s.peekDue(laneFree = true) ?: continue
            s.commitShown(hint(t))
            shown.add(watched to text)
        }
        return shown
    }

    // ПОЛОМКА №1: дубликаты. Сессия не возвращает один текст дважды — по построению.
    @Test
    fun `за серию ни один текст не выдаётся дважды`() {
        val s = session(texts(20))
        val shown = play(s, minutes = 60)
        assertEquals("должно показаться всё, серия длинная", 20, shown.size)
        assertEquals("повторов быть не может", 20, shown.map { it.second }.toSet().size)
    }

    // ПОЛОМКА №2: «потом вообще исчезли». При честном запасе реплики идут всю серию.
    @Test
    fun `двадцать реплик распределяются по серии, а не молчат`() {
        val s = session(texts(20))
        val shown = play(s, minutes = 24)
        assertTrue("первая должна выйти в окно 8–15 с, вышла на ${shown.first().first} мс", shown.first().first <= 15_500L)
        assertTrue("за серию должно выйти большинство, вышло ${shown.size}", shown.size >= 12)
        // И никаких «десяти минут тишины» между соседними.
        val gaps = shown.zipWithNext { a, b -> b.first - a.first }
        assertTrue("максимальная пауза ${gaps.max()} мс", gaps.max() <= DanmakuRate.NORMAL.maxGapMs * 13 / 10)
    }

    // ПОЛОМКА №3: дрожащая длина HLS перезапускала цикл. Теперь длина — только
    // подсказка шага: дрожь не сбрасывает ни часы, ни показанное, ни очередь.
    @Test
    fun `дрожание длины не останавливает показ и не создаёт повторов`() {
        val s = session(texts(15))
        val jitter = longArrayOf(1_435_059L, 1_420_044L, 1_450_154L, 1_420_117L)
        val shown = play(s, minutes = 24, hint = { jitter[it % jitter.size] })
        assertTrue("с дрожащей длиной показ обязан идти, вышло ${shown.size}", shown.size >= 10)
        assertEquals("и без повторов", shown.size, shown.map { it.second }.toSet().size)
    }

    // ПОЛОМКА №4: пауза. Часы стоят, ничего не сгорает, после продолжения — дальше.
    @Test
    fun `пауза не двигает часы и не сжигает реплики`() {
        val s = session(texts(10))
        // 20 первых секунд на паузе: не показано НИЧЕГО.
        val pausedPart = play(s, minutes = 1, playing = { false })
        assertTrue(pausedPart.isEmpty())
        assertEquals(0, s.shownCount)
        // Продолжили — первая приходит по своему окну, состояние то же.
        val resumed = play(s, minutes = 5)
        assertTrue("после паузы показ продолжается", resumed.isNotEmpty())
    }

    // ПОЛОМКА №5 (перемотка/буферизация): у сессии нет ни позиции, ни состояния VLC —
    // ей просто не от чего сломаться. Проверяем протокол «показан = подтверждён».
    @Test
    fun `отменившийся показ не сжигает реплику`() {
        val s = session(texts(5))
        s.accumulate(20_000)
        val first = s.peekDue(laneFree = true)
        val second = s.peekDue(laneFree = true)
        assertEquals("peek ничего не тратит", first, second)
        assertEquals(0, s.shownCount)
        s.commitShown(0L)
        assertEquals(1, s.shownCount)
        s.accumulate(DanmakuRate.NORMAL.maxGapMs)
        assertNotEquals("после подтверждения — следующая", first, s.peekDue(laneFree = true))
    }

    @Test
    fun `занятые дорожки не тратят очередь`() {
        val s = session(texts(5))
        s.accumulate(20_000)
        assertNull(s.peekDue(laneFree = false))
        assertEquals("без свободной дорожки ничего не выдано и не потеряно", 0, s.shownCount)
    }

    // ПОЛОМКА №6: пересбор экрана пересоздавал колоду и обнулял «показано».
    @Test
    fun `пересбор композиции возвращает ту же сессию с тем же прогрессом`() {
        val comments = (1..12).map { comment("Реплика про серию номер $it, вполне осмысленная") }
        val a = DanmakuSessions.obtain("ax:7:3", comments, 3, false, DanmakuRate.NORMAL, seed = 1L)
        a.accumulate(20_000)
        a.peekDue(true)
        a.commitShown(0L)
        // «Recomposition»: тот же ключ, НОВЫЙ список тех же комментариев.
        val b = DanmakuSessions.obtain("ax:7:3", comments.toList(), 3, false, DanmakuRate.NORMAL, seed = 2L)
        assertEquals("та же сессия", a.id, b.id)
        assertEquals("прогресс не сброшен", 1, b.shownCount)
    }

    // ПОЛОМКА №7: смена серии — единственное, что честно всё пересоздаёт.
    @Test
    fun `смена серии даёт новую сессию с чистым состоянием`() {
        val five = (1..12).map { comment("Комментарий пятой серии номер $it, осмысленный") }
        val six = (1..12).map { comment("Комментарий шестой серии номер $it, осмысленный") }
        val a = DanmakuSessions.obtain("ax:7:5", five, 5, false, DanmakuRate.NORMAL, seed = 1L)
        a.accumulate(20_000); a.peekDue(true); a.commitShown(0L)
        val b = DanmakuSessions.obtain("ax:7:6", six, 6, false, DanmakuRate.NORMAL, seed = 1L)
        assertNotEquals("новая серия — новая сессия", a.id, b.id)
        assertEquals("показанное прошлой серии не тянется", 0, b.shownCount)
        assertTrue("очередь полная", b.usable > 0)
    }

    @Test
    fun `один цикл на сессию — счётчик это видит`() {
        val s = session(texts(3))
        assertEquals(1, s.schedulerEnter())
        assertEquals("второй цикл — уже перебор, лог это покажет", 2, s.schedulerEnter())
        s.schedulerExit(); s.schedulerExit()
    }

    private var nextId = 1L
    private fun comment(text: String) = TitleComment(
        id = nextId++, author = "кто-то", avatar = "", message = text,
        timestamp = 0L, votes = 0, isSpoiler = false,
    )
}

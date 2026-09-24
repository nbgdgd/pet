package com.aniblaze.featureplayer.danmaku

import com.aniblaze.aggregator.model.TitleComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отбор и расписание реплик, показываемых поверх видео.
 *
 * Проверяется главное, ради чего это писалось: не показать спойлер чужой серии, не
 * повторить одно и то же и не сжечь весь запас за первую минуту.
 */
class DanmakuTest {

    private var nextId = 1L

    private fun comment(
        message: String,
        episode: Int = 0,
        spoiler: Boolean = false,
        timestamp: Long = 0,
    ) = TitleComment(
        id = nextId++,
        author = "кто-то",
        avatar = "",
        message = message,
        timestamp = timestamp,
        votes = 0,
        isSpoiler = spoiler,
        episode = episode,
    )

    // ---- отбор ---------------------------------------------------------------

    @Test
    fun `spoiler contents become a neutral popup while normal comments remain`() {
        val pool = danmakuPool(
            listOf(
                comment("Он умирает в конце, если что", spoiler = true),
                comment("Она оказалась предателем"),
                comment("Опенинг тут просто отличный"),
            ),
        )
        assertEquals(listOf(NEW_MESSAGE_TEXT, "Опенинг тут просто отличный"), pool)
    }

    @Test
    fun `normal unscoped reactions are not mistaken for spoilers`() {
        val normal = listOf(
            comment("Сага о Грузине"),
            comment("Какая красивая рисовка"),
            comment("Опенинг тут просто отличный"),
            comment("Музыка в концовке звучит прекрасно"),
            comment("Музыка в финале звучит прекрасно"),
        )

        assertTrue(normal.none(::isPotentialDanmakuSpoiler))
        assertEquals(normal.map { it.message }, danmakuPool(normal))
    }

    @Test
    fun `too short, too long and shouting are dropped`() {
        val pool = danmakuPool(
            listOf(
                comment("первый!!"),
                comment("АААААААААААААААА"),
                comment("ссылка тут http://example.com смотрите"),
                comment("а".repeat(200)),
                comment("Нормальная реплика про серию"),
            ),
        )
        assertEquals(listOf("Нормальная реплика про серию"), pool)
    }

    @Test
    fun `the same text in any spelling shows once`() {
        val pool = danmakuPool(
            listOf(
                comment("Лучшее аниме сезона!!!"),
                comment("лучшее   аниме сезона"),
                comment("Лучшее аниме сезона 😀"),
            ),
        )
        assertEquals(1, pool.size)
    }

    @Test
    fun `whitespace is normalised before showing`() {
        val pool = danmakuPool(listOf(comment("  много    пробелов   внутри  ")))
        assertEquals(listOf("много пробелов внутри"), pool)
    }

    // ---- раскладка по сериям ---------------------------------------------------

    @Test
    fun `a comment tagged with another episode is never shown`() {
        val all = listOf(
            comment("Про шестую серию, это конец арки", episode = 6),
            comment("Про первую серию, отличное начало", episode = 1),
        )
        val forFirst = episodeScope(all, episode = 1)
        assertEquals(1, forFirst.size)
        assertEquals(1, forFirst.first().episode)
    }

    @Test
    fun `episodes with enough own comments do not touch the shared pool`() {
        // 25 своих — ровно порог: общий пул не подмешивается вовсе, и пересечений
        // между сериями не возникает в принципе.
        val own = (1..25).map { comment("Своя реплика номер $it тут", episode = 3) }
        val shared = (1..40).map { comment("Общая реплика номер $it тут") }
        val scoped = episodeScope(own + shared, episode = 3)
        assertEquals(25, scoped.size)
        assertTrue(scoped.all { it.episode == 3 })
    }

    @Test
    fun `neighbouring episodes get disjoint slices of the shared pool`() {
        val shared = (1..100).map { comment("Общая реплика номер $it тут") }
        val fifth = episodeScope(shared, episode = 5).map { it.id }.toSet()
        val sixth = episodeScope(shared, episode = 6).map { it.id }.toSet()
        assertTrue(fifth.isNotEmpty())
        assertTrue(sixth.isNotEmpty())
        assertTrue("доли серий пересеклись", fifth.intersect(sixth).isEmpty())
    }

    @Test
    fun `a film without episode numbers takes the whole pool`() {
        val shared = (1..30).map { comment("Общая реплика номер $it тут") }
        assertEquals(30, episodeScope(shared, episode = 0).size)
    }

    // ---- расписание -------------------------------------------------------------

    @Test
    fun `nothing appears before the first gap elapses`() {
        val session = DanmakuSession(
            episodeKey = "t:1",
            sourceSize = 3,
            freshFirst = false,
            rate = DanmakuRate.NORMAL,
            texts = listOf("одна", "две", "три"),
            seed = 42L,
        )
        // Первая реплика у «Обычно» не раньше восьмой секунды.
        session.accumulate(3_000)
        assertNull(session.peekDue(laneFree = true))
        session.accumulate(20_000)
        assertNotNull(session.peekDue(laneFree = true))
    }

    @Test
    fun `a busy lane holds the queue instead of burning it`() {
        val session = DanmakuSession("t:1", 2, false, DanmakuRate.OFTEN, listOf("одна", "две"), 7L)
        session.accumulate(60_000)
        assertNull(session.peekDue(laneFree = false))
        // Ничего не потрачено: как только дорожка освободится, реплика на месте.
        assertEquals("одна", session.peekDue(laneFree = true))
        assertEquals(0, session.shownCount)
    }

    @Test
    fun `a shown line never comes back`() {
        val session = DanmakuSession("t:1", 2, false, DanmakuRate.OFTEN, listOf("одна", "две"), 7L)
        session.accumulate(60_000)
        val first = session.peekDue(laneFree = true)
        session.commitShown(durationHintMs = 1_400_000)
        session.accumulate(600_000)
        val second = session.peekDue(laneFree = true)
        assertEquals(2, listOfNotNull(first, second).distinct().size)
    }

    @Test
    fun `a paused player does not spend the queue`() {
        val session = DanmakuSession("t:1", 3, false, DanmakuRate.NORMAL, listOf("a раз", "б два", "в три"), 3L)
        // accumulate просто не зовут, пока стоит пауза — часы не идут.
        repeat(100) { session.peekDue(laneFree = true) }
        assertEquals(0, session.shownCount)
        assertTrue(session.hasMore())
    }

    @Test
    fun `the queue runs out only after everything was shown`() {
        val session = DanmakuSession("t:1", 2, false, DanmakuRate.OFTEN, listOf("одна", "две"), 1L)
        repeat(2) {
            session.accumulate(600_000)
            session.peekDue(laneFree = true)
            session.commitShown(0)
        }
        assertFalse(session.hasMore())
        assertEquals(2, session.shownCount)
    }

    // ---- держатель сессии ---------------------------------------------------------

    @Test
    fun `the same episode keeps its session across screen rebuilds`() {
        DanmakuSessions.forget()
        val comments = (1..30).map { comment("Реплика номер $it тут") }
        val first = DanmakuSessions.obtain("t:1", comments, 1, false, DanmakuRate.NORMAL)
        // Пересбор экрана даёт НОВЫЙ список тех же комментариев — сессия обязана выжить.
        val again = DanmakuSessions.obtain("t:1", comments.toList(), 1, false, DanmakuRate.NORMAL)
        assertEquals(first.id, again.id)
    }

    @Test
    fun `switching episode starts a new session`() {
        DanmakuSessions.forget()
        val comments = (1..30).map { comment("Реплика номер $it тут") }
        val first = DanmakuSessions.obtain("t:1", comments, 1, false, DanmakuRate.NORMAL)
        val second = DanmakuSessions.obtain("t:2", comments, 2, false, DanmakuRate.NORMAL)
        assertTrue(first.id != second.id)
    }

    @Test
    fun `changing the rate rebuilds the session`() {
        DanmakuSessions.forget()
        val comments = (1..30).map { comment("Реплика номер $it тут") }
        val first = DanmakuSessions.obtain("t:1", comments, 1, false, DanmakuRate.NORMAL)
        val second = DanmakuSessions.obtain("t:1", comments, 1, false, DanmakuRate.OFTEN)
        assertTrue(first.id != second.id)
    }

    @Test
    fun `unknown rate key falls back to normal`() {
        assertEquals(DanmakuRate.NORMAL, DanmakuRate.of("что-то не то"))
        assertEquals(DanmakuRate.OFTEN, DanmakuRate.of("often"))
    }
}

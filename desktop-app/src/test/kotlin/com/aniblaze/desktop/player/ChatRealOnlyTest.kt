package com.aniblaze.desktop.player

import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Режим «только реальные зрители».
 *
 * Проверяется то, ради чего он и заводился: выдуманного зала не остаётся НИГДЕ, а
 * настоящие реплики при этом не вытесняются из ленты и доживают до зрителя. Отбор
 * идёт на стороне панели — [ChatEngine] о режиме не знает и знать не должен, поэтому
 * и проверять его здесь нечего.
 */
class ChatRealOnlyTest {

    private var nextId = 0L

    private fun message(
        real: Boolean,
        text: String = "текст",
        id: Long = ++nextId,
    ) = ChatMessage(
        id = id,
        nick = if (real) "Зритель" else "Бот",
        color = 0xFFFFFFFF,
        badge = null,
        avatar = "",
        real = real,
        replyTo = null,
        text = text,
        mood = SceneMood.CALM,
    )

    /** Своё сообщение — ровно то, что кладёт поле ввода: отрицательный номер. */
    private fun own(text: String = "моя реплика") = message(real = false, text = text, id = -System.nanoTime())

    @Test
    fun `выключенный режим не отсеивает ничего`() {
        assertTrue(keepInChatFeed(message(real = false), realOnly = false))
        assertTrue(keepInChatFeed(message(real = true), realOnly = false))
        assertTrue(keepInChatFeed(own(), realOnly = false))
    }

    @Test
    fun `включённый режим оставляет только настоящие реплики`() {
        assertTrue(keepInChatFeed(message(real = true), realOnly = true))
        assertFalse(keepInChatFeed(message(real = false), realOnly = true))
    }

    @Test
    fun `своё сообщение остаётся даже в самом строгом режиме`() {
        // Его написал живой человек — тот самый, для кого режим и включён. Строка,
        // исчезающая сразу после отправки, читается как поломка ввода.
        val mine = own()
        assertTrue(isOwnChatMessage(mine))
        assertTrue(keepInChatFeed(mine, realOnly = true))
    }

    @Test
    fun `сообщение движка своим не считается`() {
        // У движка счётчик всегда растёт от единицы, поэтому знак номера и работает
        // признаком авторства.
        assertFalse(isOwnChatMessage(message(real = false, id = 1L)))
        assertFalse(isOwnChatMessage(message(real = true, id = 900L)))
    }

    @Test
    fun `лента не пускает выдуманных в режиме только реальные`() {
        val feed = ChatFeed()
        feed.realOnly = true
        feed.push(message(real = false, text = "выдумка"))
        feed.push(message(real = true, text = "правда"))
        feed.push(own("моё"))
        assertEquals(listOf("правда", "моё"), feed.messages.map { it.text })
    }

    @Test
    fun `настоящую реплику не вытесняет поток выдуманных`() {
        // Ради этого отбор и стоит на входе в ленту, а не на отрисовке: потолок ленты
        // общий, и на «шторме» две сотни выдуманных сообщений набегают за пару минут —
        // единственная настоящая реплика серии оказалась бы выдавлена из буфера.
        val feed = ChatFeed()
        feed.realOnly = true
        feed.push(message(real = true, text = "единственная настоящая"))
        repeat(500) { feed.push(message(real = false)) }
        assertEquals(1, feed.messages.size)
        assertEquals("единственная настоящая", feed.messages.single().text)
    }

    @Test
    fun `включение посреди серии убирает уже показанных выдуманных`() {
        val feed = ChatFeed()
        feed.push(message(real = false, text = "выдумка"))
        feed.push(message(real = true, text = "правда"))
        feed.push(own("моё"))
        assertEquals(3, feed.messages.size)

        feed.realOnly = true
        feed.dropInvented()
        assertEquals(listOf("правда", "моё"), feed.messages.map { it.text })
    }

    @Test
    fun `выключение режима возвращает зал`() {
        val feed = ChatFeed()
        feed.realOnly = true
        feed.push(message(real = false, text = "не пущено"))
        feed.realOnly = false
        feed.push(message(real = false, text = "пущено"))
        assertEquals(listOf("пущено"), feed.messages.map { it.text })
    }

    @Test
    fun `пустое обсуждение и редкие реплики объясняются по-разному`() {
        // Молчание выглядит одинаково, а причин две, и зритель должен понимать,
        // ждать ему или выключать режим.
        val noComments = realOnlyEmptyNote(0)
        val rareComments = realOnlyEmptyNote(37)
        assertTrue("не сказано, что обсуждения нет", noComments.contains("не нашлось"))
        assertTrue("нет подсказки, как вернуть зал", noComments.contains("Выключи режим"))
        assertTrue("не показано, сколько реплик всё-таки есть", rareComments.contains("37"))
        assertFalse("редкие реплики не должны выглядеть как пустое обсуждение", rareComments.contains("не нашлось"))
    }

    @Test
    fun `отрицательный запас реплик считается пустым обсуждением`() {
        // Значение приходит из движка и в теории может не успеть проставиться;
        // подпись обязана остаться осмысленной.
        assertEquals(realOnlyEmptyNote(0), realOnlyEmptyNote(-1))
    }

    @Test
    fun `во время загрузки не показывается ложная пустота`() {
        val loading = realOnlyEmptyNote(0, RealCommentState.LOADING)
        val cached = realOnlyEmptyNote(0, RealCommentState.CACHED_REFRESHING)
        assertTrue(loading.contains("Загружаем"))
        assertFalse(loading.contains("не нашлось"))
        assertTrue(cached.contains("догружаем"))
        assertFalse(cached.contains("не нашлось"))
    }

    @Test
    fun `пустота объявляется только после завершённого обхода`() {
        val empty = realOnlyEmptyNote(0, RealCommentState.EMPTY)
        assertTrue(empty.contains("всём обсуждении этого сезона"))
        assertTrue(empty.contains("не нашлось"))
    }
}

package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.TitleComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import kotlin.test.Test

/**
 * Сбор обсуждения: что считать одной и той же записью и где кончается обход.
 *
 * Повод — жалоба «комментарии повторяются, и их мало». Оказалось, что это одна поломка,
 * а не две: страницы запрашивались с сортировкой «сначала лучшие», порядок по числу
 * голосов неоднозначен, окно страницы съезжало между запросами — и то, что приходило
 * дважды, ровно настолько же не приходило вовсе. Замерено 23.08 на «Наруто» (`ax:609`),
 * сорок страниц: 1000 записей, 766 разных.
 *
 * Здесь сторожится всё, что не зависит от сети.
 */
class CommentFeedTest {

    private fun comment(
        id: Long = 0,
        author: String = "kot",
        message: String = "норм",
        timestamp: Long = 1_700_000_000,
        votes: Int = 0,
    ) = TitleComment(
        id = id,
        author = author,
        avatar = "",
        message = message,
        timestamp = timestamp,
        votes = votes,
    )

    // ---- признак «та же запись» ------------------------------------------------------

    @Test
    fun `идентификатор источника решает всё`() {
        val a = comment(id = 42, message = "первый текст")
        val b = comment(id = 42, message = "текст поправили")
        assertEquals("одна запись, отредактированная — не две", commentIdentity(a), commentIdentity(b))
    }

    @Test
    fun `одинаковый id разных источников не смешивается`() {
        val a = comment(id = 42).copy(source = "anixart")
        val b = comment(id = 42).copy(source = "other")
        assertNotEquals(commentIdentity(a), commentIdentity(b))
    }

    @Test
    fun `fallback использует стабильный id автора`() {
        val a = comment(id = 0, author = "старый ник", message = "серия топ", timestamp = 100)
            .copy(source = "anixart", authorId = "user-7")
        val b = comment(id = 0, author = "новый ник", message = "серия топ", timestamp = 100)
            .copy(source = "anixart", authorId = "user-7")
        assertEquals(commentIdentity(a), commentIdentity(b))
    }

    @Test
    fun `разные записи одного автора остаются разными`() {
        // Ровно та ошибка, которой нельзя допустить, борясь с повторами: человек пишет
        // под серией дважды, и оба мнения настоящие.
        val a = comment(id = 0, author = "kot", message = "серия топ", timestamp = 100)
        val b = comment(id = 0, author = "kot", message = "а концовка слабая", timestamp = 200)
        assertNotEquals(commentIdentity(a), commentIdentity(b))
    }

    @Test
    fun `одинаковый текст от разных людей — две записи`() {
        val a = comment(id = 0, author = "kot", message = "топ")
        val b = comment(id = 0, author = "pes", message = "топ")
        assertNotEquals(commentIdentity(a), commentIdentity(b))
    }

    @Test
    fun `без идентификатора текст сравнивается нормализованным`() {
        val a = comment(id = 0, message = "  серия   топ\r\n")
        val b = comment(id = 0, message = "серия топ")
        assertEquals(commentIdentity(a), commentIdentity(b))
    }

    @Test
    fun `нормализация не трогает регистр`() {
        // «ору» и «ОРУ» — разное по интонации, и на экране это видно.
        assertNotEquals(normalizedMessage("ору"), normalizedMessage("ОРУ"))
        assertEquals("а вот\nпереводы строк\tи пробелы — да", "а вот переводы строк и пробелы — да", normalizedMessage("а вот\nпереводы  строк\tи пробелы — да"))
    }

    // ---- накопитель --------------------------------------------------------------------

    @Test
    fun `повтор внутри одной страницы отсеивается`() {
        val acc = CommentAccumulator()
        val fresh = acc.add(listOf(comment(id = 1), comment(id = 2), comment(id = 1)))
        assertEquals(2, fresh)
        assertEquals(2, acc.size)
    }

    @Test
    fun `повтор между первой и второй страницей отсеивается`() {
        // Это и есть съехавшее окно: третья запись первой страницы приезжает второй.
        val acc = CommentAccumulator()
        acc.add(listOf(comment(id = 1), comment(id = 2), comment(id = 3)))
        val fresh = acc.add(listOf(comment(id = 3), comment(id = 4)))
        assertEquals(1, fresh)
        assertEquals(4, acc.size)
    }

    @Test
    fun `повторно пришедший тот же ответ ничего не добавляет`() {
        val acc = CommentAccumulator()
        val page = listOf(comment(id = 1), comment(id = 2))
        acc.add(page)
        assertEquals(0, acc.add(page))
        assertEquals(2, acc.size)
    }

    @Test
    fun `кэш и сеть не складываются в двойной список`() {
        // Накопитель тот же самый при повторном открытии тайтла — сеть докладывает в
        // него же, и старое второй раз не приписывается.
        val cached = CommentAccumulator()
        cached.add(listOf(comment(id = 1), comment(id = 2)))
        val fromNetwork = listOf(comment(id = 1), comment(id = 2), comment(id = 3))
        assertEquals(1, cached.add(fromNetwork))
        assertEquals(3, cached.size)
    }

    @Test
    fun `порядок поступления сохраняется`() {
        val acc = CommentAccumulator()
        acc.add(listOf(comment(id = 3), comment(id = 1)))
        acc.add(listOf(comment(id = 1), comment(id = 2)))
        assertEquals(listOf(3L, 1L, 2L), acc.snapshot().map { it.id })
    }

    @Test
    fun `снимок не делится внутренностями`() {
        val acc = CommentAccumulator()
        acc.add(listOf(comment(id = 1)))
        val snapshot = acc.snapshot()
        acc.add(listOf(comment(id = 2)))
        assertEquals("уже отданный экрану список не должен меняться под ним", 1, snapshot.size)
    }

    @Test
    fun `двадцать с лишним страниц собираются целиком`() {
        // Длинный тайтл: у Наруто 258 страниц. Проверяем, что накопитель не считает
        // квадратично и ничего не теряет на объёме.
        val acc = CommentAccumulator()
        var id = 1L
        repeat(25) { acc.add((1..25).map { comment(id = id++) }) }
        assertEquals(625, acc.size)
        assertEquals(625, acc.snapshot().map { it.id }.toSet().size)
    }

    // ---- условия остановки ----------------------------------------------------------

    @Test
    fun `пустая страница заканчивает обход`() {
        assertEquals(
            CommentStop.EMPTY_PAGE,
            commentStop(page = 7, rawReceived = 0, rawSeen = 175, freshUnique = 0, barrenStreak = 0, totalCount = 0),
        )
    }

    @Test
    fun `конец по общему числу записей источника`() {
        // Считаем по ЧИСЛУ ЗАПИСЕЙ, а не по числу страниц: последнее у источника
        // ошибается. Замерено 23.08 на «Ван-Пис» — 509 записей при 25 на страницу это
        // 21 страница, а заявлено 20, и остановка по страницам теряла хвост.
        assertEquals(
            CommentStop.LAST_PAGE,
            commentStop(page = 257, rawReceived = 25, rawSeen = 6456, freshUnique = 25, barrenStreak = 0, totalCount = 6456),
        )
        assertNull(commentStop(page = 256, rawReceived = 25, rawSeen = 6425, freshUnique = 25, barrenStreak = 0, totalCount = 6456))
    }

    @Test
    fun `одна страница без новых записей обход НЕ заканчивает`() {
        // У съехавшего окна страница целиком из повторов встречается и посреди
        // обсуждения — обрывать на ней значит терять весь хвост.
        assertNull(commentStop(page = 4, rawReceived = 25, rawSeen = 125, freshUnique = 0, barrenStreak = 1, totalCount = 0))
        assertNull(commentStop(page = 5, rawReceived = 25, rawSeen = 150, freshUnique = 0, barrenStreak = 2, totalCount = 0))
    }

    @Test
    fun `страница из одних ответов концом НЕ считается`() {
        // Разбор выбрасывает ответы на комментарии: такая страница разбирается в ноль,
        // хотя у источника за ней есть ещё сотни. Пустоту считаем по СЫРЫМ записям.
        assertNull(
            commentStop(page = 3, rawReceived = 25, rawSeen = 100, freshUnique = 0, barrenStreak = 1, totalCount = 0),
        )
    }

    @Test
    fun `три страницы подряд без новых — это конец`() {
        assertEquals(
            CommentStop.NO_NEW,
            commentStop(page = 6, rawReceived = 25, rawSeen = 175, freshUnique = 0, barrenStreak = 3, totalCount = 0),
        )
    }

    @Test
    fun `предохранитель срабатывает только на сломанном источнике`() {
        // Бесконечно отвечающий одним и тем же источник ловится раньше — по «без
        // новых». Сюда доходит только тот, кто честно отдаёт новое без конца.
        assertEquals(
            CommentStop.SAFETY_LIMIT,
            commentStop(page = 999, rawReceived = 25, rawSeen = 25000, freshUnique = 25, barrenStreak = 0, totalCount = 0, safetyLimit = 1000),
        )
        assertNull(commentStop(page = 998, rawReceived = 25, rawSeen = 24975, freshUnique = 25, barrenStreak = 0, totalCount = 0, safetyLimit = 1000))
    }

    @Test
    fun `предохранитель не мешает длинным обсуждениям`() {
        // 258 страниц Наруто обязаны проходиться целиком, а не упираться в потолок.
        assertNull(
            commentStop(page = 200, rawReceived = 25, rawSeen = 5000, freshUnique = 20, barrenStreak = 0, totalCount = 6456),
        )
    }

    @Test
    fun `пул ограничен по памяти отдельно от числа страниц`() {
        assertEquals(
            CommentStop.CAPACITY_LIMIT,
            commentStop(
                page = 799,
                rawReceived = 25,
                rawSeen = 20_000,
                freshUnique = 25,
                barrenStreak = 0,
                totalCount = 30_000,
                uniqueSeen = 20_000,
            ),
        )
    }
}

package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.TitleComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Сначала популярные»: очередь настоящих реплик по убыванию голосов + подпись источника. */
class ChatPopularFirstTest {

    private fun comment(id: Long, votes: Int, source: String = "anixart", text: String = "реплика номер $id про сюжет") =
        TitleComment(id = id, source = source, author = "u$id", avatar = "", message = text, timestamp = 1_700_000_000 + id, votes = votes)

    @Test fun `популярные первыми и по убыванию`() {
        val pool = (1L..40L).map { comment(it, votes = ((it * 7919) % 100).toInt()) }
        val picks = chatPicksForEpisode(pool, episode = 0, freshFirst = false, limit = 10, popularFirst = true)
        assertEquals(10, picks.size)
        assertEquals(picks.map { it.votes }.sortedDescending(), picks.map { it.votes })
        assertEquals(pool.maxOf { it.votes }, picks.first().votes)
    }

    @Test fun `без настройки - равномерный срез, а не топ`() {
        val pool = (1L..40L).map { comment(it, votes = it.toInt()) }
        val picks = chatPicksForEpisode(pool, episode = 0, freshFirst = false, limit = 10, popularFirst = false)
        assertTrue(picks.map { it.votes } != (31..40).toList().sortedDescending(), "равномерный срез не должен совпасть с топом")
    }

    @Test fun `движок не тасует очередь в режиме популярных`() {
        val pool = (1L..30L).map { comment(it, votes = 100 - it.toInt()) }
        val engine = ChatSessions.obtain(
            "t:1", pool, viewers = 50, intensity = ChatIntensity.NORMAL, freshFirst = false,
            seed = 42L, episode = 1, popularFirst = true,
        )
        engine.setRealOnly(true)
        val shown = mutableListOf<Int>()
        var guard = 0
        while (shown.size < 8 && guard++ < 5_000) {
            engine.accumulate(CHAT_TICK_MS)
            engine.poll(guard * CHAT_TICK_MS, 24 * 60_000L, null, null, SceneSample.UNKNOWN, CHAT_TICK_MS)
                .forEach { m -> if (m.real) shown += pool.first { it.message == m.text }.votes }
        }
        assertEquals(shown.sortedDescending(), shown)
        assertEquals(99, shown.first())
        // Смена настройки — новая сессия: старая очередь уже в другом порядке.
        val other = ChatSessions.obtain("t:1", pool, 50, ChatIntensity.NORMAL, false, seed = 42L, episode = 1, popularFirst = false)
        assertTrue(other !== engine)
    }

    @Test fun `подпись источника у настоящей реплики`() {
        val pool = listOf(comment(1, 5, source = "yummy"), comment(2, 3, source = "anixart"))
        val engine = ChatSessions.obtain("t:2", pool, 50, ChatIntensity.NORMAL, false, seed = 1L, episode = 1, popularFirst = true)
        engine.setRealOnly(true)
        val real = mutableListOf<ChatMessage>()
        var guard = 0
        while (real.size < 2 && guard++ < 5_000) {
            engine.accumulate(CHAT_TICK_MS)
            real += engine.poll(guard * CHAT_TICK_MS, 24 * 60_000L, null, null, SceneSample.UNKNOWN, CHAT_TICK_MS).filter { it.real }
        }
        assertEquals(listOf("yummy", "anixart"), real.map { it.source })
        assertEquals("Yummy", chatSourceTag("yummy"))
        assertEquals("Anixart", chatSourceTag("anixart"))
        assertNull(chatSourceTag(""))
        val line = chatLine(real.first(), fontSize = 13).text
        assertTrue(line.contains("u1 Yummy ▲5:"), "нет подписи в строке: $line")
    }

    @Test fun `голоса и адресат в строке чата`() {
        val pool = listOf(
            comment(1, 222, source = "anixart"),
            comment(2, 1500, source = "anixart").copy(parentId = 1, replyTo = "u1"),
        )
        val engine = ChatSessions.obtain("t:3", pool, 50, ChatIntensity.NORMAL, false, seed = 1L, episode = 1, popularFirst = true)
        engine.setRealOnly(true)
        val real = mutableListOf<ChatMessage>()
        var guard = 0
        while (real.size < 2 && guard++ < 5_000) {
            engine.accumulate(CHAT_TICK_MS)
            real += engine.poll(guard * CHAT_TICK_MS, 24 * 60_000L, null, null, SceneSample.UNKNOWN, CHAT_TICK_MS).filter { it.real }
        }
        val reply = real.first { it.parentKey.isNotBlank() }
        assertEquals("u1", reply.replyTo)
        assertEquals(1500, reply.votes)
        val line = chatLine(reply, fontSize = 13).text
        assertTrue(line.contains("▲1,5к"), "нет голосов в строке: $line")
        assertTrue(!line.contains("@u1"), "«@ник» в строке — ветка рисуется вложением, а не упоминанием: $line")
        assertEquals("anixart:1", reply.parentKey)
        assertEquals("222", chatVotesLabel(222))
        assertEquals("12к", chatVotesLabel(12_400))
        assertEquals("2к", chatVotesLabel(2_000))
    }

    @Test fun `выделение по голосам - относительно пула сессии, как на Twitch`() {
        // Нишевый тайтл: голоса 1..20 — «заметно» уже от верхней четверти, «топ» — верхние 5 %.
        val pool = (1L..20L).map { comment(it, votes = it.toInt()) }
        val engine = ChatSessions.obtain("t:5", pool, 50, ChatIntensity.NORMAL, false, seed = 3L, episode = 1, popularFirst = true)
        engine.setRealOnly(true)
        val shown = mutableListOf<ChatMessage>()
        var guard = 0
        while (shown.size < 20 && guard++ < 20_000) {
            engine.accumulate(CHAT_TICK_MS)
            shown += engine.poll(guard * CHAT_TICK_MS, 24 * 60_000L, null, null, SceneSample.UNKNOWN, CHAT_TICK_MS).filter { it.real }
        }
        val byVotes = shown.associate { it.votes to it.voteTier }
        assertEquals(ChatVoteTier.TOP, byVotes[20])
        assertEquals(ChatVoteTier.NOTABLE, byVotes[16])
        assertEquals(ChatVoteTier.NONE, byVotes[5])
        assertEquals(ChatVoteTier.NONE, byVotes[1])
        // Выдуманный зал — никогда.
        val fake = ChatMessage(id = 1, nick = "n", color = 0xFFFFFFFF, badge = null, avatar = "", real = false,
            replyTo = null, text = "t", mood = SceneMood.CALM, votes = 999)
        assertEquals(ChatVoteTier.NONE, fake.voteTier)
    }

    @Test fun `крошечный пул без голосов - без выделения`() {
        val pool = (1L..5L).map { comment(it, votes = 2) }
        val engine = ChatSessions.obtain("t:6", pool, 50, ChatIntensity.NORMAL, false, seed = 3L, episode = 1, popularFirst = true)
        assertTrue(pool.all { engine.voteTierOf(it) == ChatVoteTier.NONE })
    }

    @Test fun `ветка - ответы идут блоком сразу за записью, сироты выбрасываются`() {
        val pool = buildList {
            for (i in 1L..12L) add(comment(i, votes = 10, source = "anixart"))
            add(comment(101, votes = 50, source = "anixart").copy(parentId = 3, replyTo = "u3"))
            add(comment(102, votes = 40, source = "anixart").copy(parentId = 3, replyTo = "u3"))
            add(comment(103, votes = 5, source = "anixart").copy(parentId = 3, replyTo = "u3"))
            add(comment(104, votes = 1, source = "anixart").copy(parentId = 3, replyTo = "u3"))
            add(comment(201, votes = 9, source = "yummy").copy(parentId = 3, replyTo = "кто-то")) // другой источник — не та ветка
            add(comment(301, votes = 9, source = "anixart").copy(parentId = 999, replyTo = "нет")) // сирота
        }
        val picks = chatPicksForEpisode(pool, episode = 0, freshFirst = false, limit = 12, popularFirst = false)
        assertTrue(picks.none { it.id == 301L }, "сирота попала в чат")
        assertTrue(picks.none { it.id == 201L }, "ответ чужого источника приклеился к ветке")
        val at = picks.indexOfFirst { it.id == 3L }
        assertEquals(listOf(101L, 102L, 103L), picks.subList(at + 1, at + 4).map { it.id })
        assertTrue(picks.none { it.id == 104L }, "больше $CHAT_REPLIES_PER_THREAD ответов в ветке")

        // Движок: генерируемый зал не вклинивается между записью и её ответами.
        val engine = ChatSessions.obtain("t:4", pool, 200, ChatIntensity.STORM, false, seed = 7L, episode = 1, popularFirst = false)
        val shown = mutableListOf<ChatMessage>()
        var guard = 0
        while (shown.count { it.real } < 15 && guard++ < 20_000) {
            engine.accumulate(CHAT_TICK_MS)
            shown += engine.poll(guard * CHAT_TICK_MS, 24 * 60_000L, null, null, SceneSample.UNKNOWN, CHAT_TICK_MS)
        }
        val parentAt = shown.indexOfFirst { it.threadKey == "anixart:3" }
        assertTrue(parentAt >= 0, "запись 3 не показана")
        assertEquals(listOf("anixart:3", "anixart:3", "anixart:3"), shown.subList(parentAt + 1, parentAt + 4).map { it.parentKey })
    }
}

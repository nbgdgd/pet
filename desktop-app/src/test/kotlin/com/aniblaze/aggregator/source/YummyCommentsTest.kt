package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.TitleComment
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Обсуждение с YummyAnime в общем накопителе чата: живая страница + правила слияния,
 * которые не зависят от сети.
 */
class YummyCommentsTest {

    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val source = YummyAnimeSource(com.aniblaze.network.HttpClient(okHttp), KodikExtractor(okHttp))

    @Test
    fun `живая страница обсуждения - помечена источником, без ответов и BB-кода`() = runBlocking {
        val top = source.comments("ya:111", 0, YummyAnimeSource.COMMENTS_TOP)
        assertTrue("обсуждение Наруто пусто", top.size >= 10)
        assertTrue("источник не yummy", top.all { it.source == "yummy" })
        assertTrue("есть пустые тексты", top.none { it.message.isBlank() })
        assertTrue("BB-теги не сняты: ${top.firstOrNull { it.message.contains("[") }?.message?.take(60)}", top.none { YummyAnimeSource.BB_TAG.containsMatchIn(it.message) })
        assertTrue("нет авторов", top.all { it.author.isNotBlank() })
        assertTrue("нет времени", top.all { it.timestamp > 1_500_000_000 })
        // Голова по лайкам действительно по лайкам.
        assertTrue("топ не по голосам: ${top.take(5).map { it.votes }}", top.first().votes >= top.last().votes)
        val fresh = source.comments("ya:111", 1, YummyAnimeSource.COMMENTS_FRESH)
        assertTrue("вторая страница свежих пуста", fresh.isNotEmpty())
        assertTrue("страницы совпадают", fresh.map { it.id }.toSet() != top.map { it.id }.toSet())
    }

    @Test
    fun `сверка полного обхода Anixart не выбрасывает записи Yummy`() {
        val acc = CommentAccumulator()
        val anixart = TitleComment(id = 1, source = "anixart", author = "a", avatar = "", message = "x", timestamp = 1, votes = 0)
        val gone = TitleComment(id = 2, source = "anixart", author = "b", avatar = "", message = "y", timestamp = 1, votes = 0)
        val yummy = TitleComment(id = 1, source = "yummy", author = "c", avatar = "", message = "z", timestamp = 1, votes = 0)
        acc.add(listOf(anixart, gone, yummy))
        // Одинаковый числовой id у разных источников — две разные записи.
        assertEquals(3, acc.size)
        acc.retainIdentities(setOf(commentIdentity(anixart)), keepSources = setOf("yummy"))
        assertEquals(listOf("anixart:id:1", "yummy:id:1"), acc.snapshot().map { commentIdentity(it) })
    }

    @Test
    fun `ветка ответов Yummy - с parentId и «@ник» родителя`() = runBlocking {
        val parent = source.comments("ya:111", 0, YummyAnimeSource.COMMENTS_TOP).firstOrNull { it.replyCount > 0 }
            ?: error("у Наруто нет веток в голове обсуждения")
        val replies = source.commentReplies(parent.id, parent.author)
        assertTrue("ветка ${parent.id} пуста (children_count=${parent.replyCount})", replies.isNotEmpty())
        assertTrue("parentId не проставлен", replies.all { it.parentId == parent.id })
        assertTrue("replyTo не проставлен", replies.all { it.replyTo == parent.author })
        assertTrue("источник не yummy", replies.all { it.source == "yummy" })
    }

    @Test
    fun `сверка не выбрасывает ответы, даже anixart-овские`() {
        val acc = CommentAccumulator()
        val top = TitleComment(id = 1, source = "anixart", author = "a", avatar = "", message = "x", timestamp = 1, votes = 0, replyCount = 1)
        val reply = TitleComment(id = 2, source = "anixart", author = "b", avatar = "", message = "y", timestamp = 1, votes = 0, parentId = 1, replyTo = "a")
        acc.add(listOf(top, reply))
        acc.retainIdentities(setOf(commentIdentity(top)), keepSources = setOf("yummy"), keepReplies = true)
        assertEquals(2, acc.size)
        acc.retainIdentities(setOf(commentIdentity(top)), keepSources = setOf("yummy"))
        assertEquals(1, acc.size)
    }
}

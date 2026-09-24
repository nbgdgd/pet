package com.aniblaze.aggregator.source

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** LIVE: ответы Anixart несут parentId и «@ник» родителя — ради ветки в чате. */
class AnixartRepliesLiveTest {
    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val source = AnixartSource(
        com.aniblaze.network.HttpClient(okHttp), KodikExtractor(okHttp), com.aniblaze.database.settings.SettingsDataStore(),
    )

    @Test
    fun `ветка ответов под записью Наруто`() = runBlocking {
        val page = source.comments("ax:609", 0, AnixartSource.COMMENTS_TOP)
        val parent = page.items.firstOrNull { it.replyCount > 0 } ?: error("в голове обсуждения нет веток")
        assertTrue("самостоятельные записи не должны быть ответами", page.items.all { it.parentId == 0L && it.replyTo.isBlank() })
        val replies = source.commentReplies(parent.id, parent.author)
        assertTrue("ветка ${parent.id} пуста (reply_count=${parent.replyCount})", replies.isNotEmpty())
        assertTrue("parentId не проставлен", replies.all { it.parentId == parent.id })
        assertTrue("replyTo не проставлен", replies.all { it.replyTo == parent.author })
    }
}

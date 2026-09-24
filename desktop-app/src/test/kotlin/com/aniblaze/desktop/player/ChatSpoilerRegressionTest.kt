package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.TitleComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSpoilerRegressionTest {
    private fun comment(
        id: Long,
        episode: Int,
        text: String,
        spoiler: Boolean = false,
    ) = TitleComment(
        id = id,
        source = "anixart",
        authorId = "u$id",
        author = "viewer$id",
        avatar = "",
        message = text,
        timestamp = 1_700_000_000L + id,
        votes = 0,
        isSpoiler = spoiler,
        episode = episode,
    )

    @Test
    fun `production chat never receives future episode comments`() {
        val pool = chatPicksForEpisode(
            comments = listOf(
                comment(1, 2, "Уже просмотренная вторая серия была отличной"),
                comment(2, 3, "Комментарий про текущую третью серию целиком"),
                comment(3, 4, "В четвёртой серии раскрывается важный секрет"),
                comment(4, 0, "Общее мнение без номера серии и без таймкода"),
                comment(5, 2, "Помеченный автором скрытый сюжетный поворот", spoiler = true),
            ),
            episode = 3,
            limit = 100,
        )

        assertEquals(setOf(1L, 2L, 4L, 5L), pool.map { it.id }.toSet())
        assertTrue(pool.none { it.episode > 3 })
    }

    @Test
    fun `normal current and unscoped comments stay visible`() {
        val previous = comment(1, 2, "Безопасное обсуждение прошлой серии")
        val current = comment(2, 3, "Какая красивая рисовка")
        val unscoped = comment(3, 0, "Сага о Грузине")

        assertFalse(isPotentialChatSpoiler(previous, currentEpisode = 3))
        assertFalse(isPotentialChatSpoiler(current, currentEpisode = 3))
        assertFalse(isPotentialChatSpoiler(unscoped, currentEpisode = 3))
        assertEquals(previous.message, danmakuPreviewText(previous, episode = 3))
        assertEquals(current.message, danmakuPreviewText(current, episode = 3))
        assertEquals(unscoped.message, danmakuPreviewText(unscoped, episode = 3))
    }

    @Test
    fun `explicit and high confidence plot reveals stay hidden`() {
        val sourceMarked = comment(1, 3, "Невинно выглядящий текст", spoiler = true)
        val deathReveal = comment(2, 3, "Главный герой погибает в финале")
        val betrayalReveal = comment(3, 0, "Она оказалась предателем")
        val future = comment(4, 4, "Отличная серия")

        assertTrue(isPotentialChatSpoiler(sourceMarked, currentEpisode = 3))
        assertTrue(isPotentialChatSpoiler(deathReveal, currentEpisode = 3))
        assertTrue(isPotentialChatSpoiler(betrayalReveal, currentEpisode = 3))
        assertTrue(isPotentialChatSpoiler(future, currentEpisode = 3))
        assertEquals(NEW_MESSAGE_TEXT, danmakuPreviewText(deathReveal, episode = 3))
        assertEquals(NEW_MESSAGE_TEXT, danmakuPreviewText(betrayalReveal, episode = 3))
    }

    @Test
    fun `potential spoiler is hidden in chat and neutral over video`() {
        val risky = ChatMessage(
            id = 1,
            nick = "viewer",
            color = 0xFFFFFFFF,
            badge = null,
            avatar = "",
            real = true,
            replyTo = null,
            text = "Главный герой погибает в финале",
            mood = SceneMood.CALM,
            potentialSpoiler = true,
        )

        assertEquals(HIDDEN_SPOILER_TEXT, chatPanelPreviewText(risky, revealed = false))
        assertEquals(risky.text, chatPanelPreviewText(risky, revealed = true))
        assertEquals(NEW_MESSAGE_TEXT, chatOverlayPreviewText(risky))
    }

    @Test
    fun `chat no longer disables danmaku and real messages are split without duplicates`() {
        assertTrue(shouldShowDanmakuOverlay(true, false, chatVisible = true, videoVisible = true))
        assertTrue(
            shouldShowDanmakuOverlay(
                commentsEnabled = true,
                commentsHidden = false,
                chatVisible = false,
                videoVisible = true,
            ),
        )

        val comments = (1L..8L).map { comment(it, 2, "Обычная реплика номер $it") }
        val split = splitCommentsForSurfaces(comments, chatEnabled = true, overlayEnabled = true)
        assertTrue(split.chat.isNotEmpty())
        assertTrue(split.overlay.isNotEmpty())
        assertTrue(split.chat.map { it.id }.toSet().intersect(split.overlay.map { it.id }.toSet()).isEmpty())
        assertEquals(comments.map { it.id }.toSet(), (split.chat + split.overlay).map { it.id }.toSet())
    }

    @Test
    fun `no chat surface renders after leaving player screen`() {
        assertFalse(shouldShowDanmakuOverlay(true, false, false, videoVisible = false))
        assertFalse(shouldShowChatOverlay(true, false, true, videoVisible = false))
    }

    @Test
    fun `hiding chat makes the separate danmaku setting effective again`() {
        assertTrue(shouldShowDanmakuOverlay(true, false, chatVisible = false, videoVisible = true))
    }
}

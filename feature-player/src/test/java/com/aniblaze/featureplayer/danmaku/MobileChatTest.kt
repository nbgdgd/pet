package com.aniblaze.featureplayer.danmaku

import com.aniblaze.aggregator.model.TitleComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileChatTest {
    private fun comment(id: Long, text: String, episode: Int = 0, spoiler: Boolean = false) =
        TitleComment(id, "user", "", text, id, 0, spoiler, episode = episode)

    @Test fun keepsRussianAndDropsEnglishParserComments() {
        val result = mobileChatComments(
            listOf(
                comment(1, "Очень хорошая серия, мне понравилась"),
                comment(2, "This episode was really good and emotional"),
            ),
            episode = 1,
        )
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun neverIncludesAnotherEpisode() {
        val result = mobileChatComments(
            listOf(
                comment(1, "Первая серия получилась отличной", episode = 1),
                comment(2, "Шестая серия раскрывает главный секрет", episode = 6),
            ),
            episode = 1,
        )
        assertFalse(result.any { it.episode == 6 })
    }

    @Test fun spoilerTextStaysAvailableForTapToReveal() {
        val source = comment(1, "В конце оказывается, что герой всё знал", spoiler = true)
        val result = mobileChatComments(listOf(source), episode = 1).single()
        assertTrue(isPotentialDanmakuSpoiler(result))
        assertEquals(source.message, result.message)
    }
}

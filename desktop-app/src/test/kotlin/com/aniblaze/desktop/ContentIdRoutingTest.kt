package com.aniblaze.desktop

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentIdRoutingTest {
    private class FakeSource(
        override val name: String,
        private val prefix: String,
    ) : ContentAggregator {
        override fun ownsContentId(contentId: String) = contentId.startsWith(prefix)
        override suspend fun extractContent(contentId: String, segment: Int): ContentResult? = null
        override suspend fun getContentSegments(contentId: String): List<Segment> = emptyList()
        override suspend fun search(query: String): List<Anime> = emptyList()
        override suspend fun trending(): List<Anime> = emptyList()
        override suspend fun validateSource(contentId: String) = true
    }

    @Test
    fun `чужой id не попадает в прямой запрос другого источника`() {
        val anixart = FakeSource("Anixart", "ax:")
        val libria = FakeSource("AniLibria", "al:")
        assertEquals(listOf(anixart), directContentSources(listOf(anixart, libria), "ax:20872"))
    }
}

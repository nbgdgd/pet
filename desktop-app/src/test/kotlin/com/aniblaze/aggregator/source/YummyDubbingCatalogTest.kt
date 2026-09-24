package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.DubStudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Каталог студии озвучки разбирается со страницы сайта (API такого фильтра не имеет).
 * Снимок страницы `site.yummyani.me/catalog/dubbing/17741` от 19.09.2026 — в fixtures.
 */
class YummyDubbingCatalogTest {
    private val html = javaClass.classLoader.getResourceAsStream("fixtures/yummy-dubbing-17741.html")!!
        .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun `со страницы снимаются все 24 карточки с id, названием, постером и оценкой`() {
        val cards = YummyAnimeSource.parseDubbingCards(html)
        assertEquals(24, cards.size)
        val first = cards.first()
        assertEquals("ya:1248", first.id)
        assertEquals("Re:Zero. Жизнь с нуля в альтернативном мире", first.title)
        assertEquals("https://static.yani.tv/posters/full/1527381687.jpg", first.poster)
        assertEquals(8.52, first.rating)
        assertEquals(10.0, first.ratingMax)
        assertEquals("Сериал", first.contentType)
        assertEquals(3_348_113, first.watchingCount)
        assertEquals(7_401, first.ratingVotes)
        assertTrue(cards.all { it.id.startsWith("ya:") && it.title.isNotBlank() && it.poster.startsWith("https://") })
        assertEquals(cards.size, cards.map { it.id }.toSet().size, "дубликаты id")
    }

    @Test fun `последняя страница берётся из пагинации, без пагинации - одна`() {
        assertEquals(4, YummyAnimeSource.dubbingLastPage(html, 17741))
        assertEquals(1, YummyAnimeSource.dubbingLastPage("<html>нет пагинации</html>", 17741))
        // Чужая студия в ссылках не считается.
        assertEquals(1, YummyAnimeSource.dubbingLastPage(html, 682))
    }

    @Test fun `озвучка в фильтре - условие с чипом, но не свойство карточки`() {
        val filter = CatalogFilter(dubbing = DubStudio.DEEP.key)
        assertTrue(!filter.isEmpty)
        assertEquals(1, filter.activeCount)
        assertEquals("Озвучка DEEP (лицензия)", filter.chips().single().label)
        assertEquals(CatalogFilter(), filter.chips().single().remove())
        // matches озвучку не проверяет — это делает источник списком.
        assertTrue(filter.matches(YummyAnimeSource.parseDubbingCards(html).first()) { emptySet() })
    }
}

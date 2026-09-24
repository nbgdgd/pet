package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
import kotlin.test.*

class HomeNavigationTest {
    @Test fun `back exits filter-only page without losing loaded rows or title tabs`() {
        val nav = NavController()
        val anime = Anime("test:1", "Test", "")
        nav.openTitle(anime, false)
        nav.goTopLevel(Screen.Home)
        val cache = HomeCache().apply {
            filter = CatalogFilter(tags = setOf("comedy")); filterOpen = true
            sections = mapOf("popular" to listOf(anime)); loaded = true
        }
        assertTrue(cache.hasLocalNavigation)
        assertTrue(nav.backFromHome(cache))
        assertEquals(Screen.Home, nav.current)
        assertTrue(cache.filter.isDefault)
        assertFalse(cache.filterOpen)
        assertFalse(cache.hasLocalNavigation)
        assertEquals(listOf(anime), cache.sections["popular"])
        assertTrue(cache.loaded)
        assertEquals(1, nav.tabs.size)
        assertFalse(nav.backFromHome(cache))
    }

    @Test fun `explicit home clears query category and filter but detail back preserves them`() {
        val nav = NavController()
        val cache = HomeCache().apply {
            expandedKey = "popular"; expandedTitle = "Популярное"; query = "test"
            filter = CatalogFilter(tags = setOf("fantasy")); filterOpen = true; browseFingerprint = "old"
        }
        nav.openTitle(Anime("test:1", "Test", ""), false)
        assertTrue(nav.backFromHome(cache))
        assertEquals(Screen.Home, nav.current)
        assertEquals("popular", cache.expandedKey)
        assertEquals(setOf("fantasy"), cache.filter.tags)
        cache.returnToLanding()
        assertNull(cache.expandedKey)
        assertEquals("", cache.query)
        assertEquals("", cache.browseFingerprint)
        assertFalse(cache.hasLocalNavigation)
    }

    @Test fun `opening filter panel alone can be undone`() {
        val cache = HomeCache().apply { filterOpen = true }
        assertTrue(cache.returnToLanding())
        assertFalse(cache.hasLocalNavigation)
    }
}

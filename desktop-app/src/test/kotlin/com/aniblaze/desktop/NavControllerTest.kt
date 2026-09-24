package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.StudioCredit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NavControllerTest {
    private fun title(id: String) = Anime(id = id, title = "Title $id", poster = "")

    @Test
    fun `selecting the already open tab does not add an invisible back step`() {
        val nav = NavController()
        val anime = title("a")

        nav.openTitle(anime, isCinema = false)
        nav.selectTab(anime.id)

        assertTrue(nav.back())
        assertEquals(Screen.Home, nav.current)
    }

    @Test
    fun `closing a tab removes its pages from browser history`() {
        val nav = NavController()
        val first = title("a")
        val closed = title("b")

        nav.openTitle(first, isCinema = false)
        nav.openTitle(closed, isCinema = false)
        nav.closeTab(closed.id)

        assertEquals(first.id, assertIs<Screen.Detail>(nav.current).anime.id)
        assertTrue(nav.back())
        assertEquals(Screen.Home, nav.current)
        assertTrue(nav.forward())
        assertEquals(first.id, assertIs<Screen.Detail>(nav.current).anime.id)
    }

    @Test
    fun `closing an inactive tab also removes its orphaned metadata pages`() {
        val nav = NavController()
        val closed = title("a")
        val current = title("b")

        nav.openTitle(closed, isCinema = false)
        nav.openStudio(StudioCredit(11, "Studio A"))
        nav.openTitle(current, isCinema = false)
        nav.closeTab(closed.id)

        assertEquals(current.id, assertIs<Screen.Detail>(nav.current).anime.id)
        assertTrue(nav.back())
        assertEquals(Screen.Home, nav.current)
    }

    @Test
    fun `closing the title behind the current metadata page replaces that orphan`() {
        val nav = NavController()
        val closed = title("a")
        val remaining = title("b")

        nav.openTitle(closed, isCinema = false)
        nav.openStudio(StudioCredit(11, "Studio A"))
        nav.openTitle(remaining, isCinema = false)
        assertTrue(nav.back())
        assertIs<Screen.Studio>(nav.current)

        nav.closeTab(closed.id)

        assertEquals(remaining.id, assertIs<Screen.Detail>(nav.current).anime.id)
        assertTrue(nav.back())
        assertEquals(Screen.Home, nav.current)
    }
}

package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.PersonCredit
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StudioCredit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DetachedMainWindowBridgeTest {
    private val anime = Anime(id = "anime-1", title = "Title", poster = "")
    private val segment = Segment(
        id = "episode-4",
        contentId = anime.id,
        number = 4,
        title = "4 серия",
    )

    @Test
    fun `request without a live main window is safely ignored`() {
        val bridge = DetachedMainWindowBridge()

        assertFalse(bridge.request(DetachedMainWindowRequest.Play(anime, segment)))
    }

    @Test
    fun `play request reaches main navigation and restores its window state`() {
        val bridge = DetachedMainWindowBridge()
        var visible = false
        var minimized = true
        var raiseRequests = 0
        var received: DetachedMainWindowRequest? = null
        bridge.handler = { request ->
            received = request
            visible = true
            minimized = false
            raiseRequests++
        }

        assertTrue(bridge.request(DetachedMainWindowRequest.Play(anime, segment)))

        val play = received as DetachedMainWindowRequest.Play
        assertSame(anime, play.anime)
        assertSame(segment, play.segment)
        assertTrue(visible)
        assertFalse(minimized)
        assertEquals(1, raiseRequests)
    }

    @Test
    fun `studio and director links are real main-window requests`() {
        val bridge = DetachedMainWindowBridge()
        val received = mutableListOf<DetachedMainWindowRequest>()
        bridge.handler = received::add
        val studio = StudioCredit(7, "Studio")
        val director = PersonCredit(9, "Director")

        assertTrue(bridge.request(DetachedMainWindowRequest.OpenStudio(studio)))
        assertTrue(bridge.request(DetachedMainWindowRequest.OpenPerson(director)))

        assertEquals(
            listOf(
                DetachedMainWindowRequest.OpenStudio(studio),
                DetachedMainWindowRequest.OpenPerson(director),
            ),
            received,
        )
    }
}

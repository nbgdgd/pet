package com.aniblaze.detail

import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.repository.TitleWatchState
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailScreenLogicTest {

    @Test
    fun `cinema series episodes are grouped by their real season`() {
        val segments = listOf(
            segment(1, "С1 · Серия 1"),
            segment(2, "С1 · Серия 2"),
            segment(3, "С2 · Серия 1 · Новый сезон"),
        )

        assertEquals(DetailContentKind.CINEMA_SERIES, detailContentKind("tmdbtv:42", segments))
        assertEquals(listOf(1, 2), groupCinemaSeasons(segments).keys.toList())
        assertEquals(listOf(1, 2), groupCinemaSeasons(segments).getValue(1).map(::cinemaEpisodeNumber))
        assertEquals("Новый сезон", cinemaEpisodeTitle(segments.last()))
    }

    @Test
    fun `cinema film stays a film instead of episode one`() {
        val segments = listOf(segment(1, "Смотреть", contentId = "tmdb:7"))

        assertEquals(DetailContentKind.CINEMA_MOVIE, detailContentKind("tmdb:7", segments))
    }

    @Test
    fun `anime episode list is not treated as cinema`() {
        val segments = listOf(segment(1, "Серия 1", contentId = "anixart:9"))

        assertEquals(DetailContentKind.OTHER, detailContentKind("anixart:9", segments))
    }

    @Test
    fun `episode count has correct russian ending`() {
        assertEquals("1 серия", episodeCountLabel(1))
        assertEquals("2 серии", episodeCountLabel(2))
        assertEquals("11 серий", episodeCountLabel(11))
        assertEquals("26 серий", episodeCountLabel(26))
    }

    @Test
    fun `franchise keeps release order and the current season keeps its place`() {
        val current = anime("ax:2", "Клинок · Поезд «Бесконечность»", 2020)
        val franchise = listOf(
            anime("ax:1", "Клинок, рассекающий демонов", 2019),
            current,
            anime("ax:3", "Клинок · Квартал красных фонарей", 2021),
        )

        assertEquals(
            listOf("ax:1", "ax:2", "ax:3"),
            seasonOptions(current, franchise).map { it.id },
        )
    }

    @Test
    fun `current title is appended when the franchise list misses it`() {
        val current = anime("al:9000", "Клинок, рассекающий демонов", 2019)
        val franchise = listOf(anime("ax:3", "Клинок · Квартал красных фонарей", 2021))

        assertEquals(
            listOf("ax:3", "al:9000"),
            seasonOptions(current, franchise).map { it.id },
        )
    }

    @Test
    fun `duplicate franchise entries collapse to one option`() {
        val current = anime("ax:1", "Клинок, рассекающий демонов", 2019)
        val franchise = listOf(current, anime("ax:1", "Клинок, рассекающий демонов", 2019))

        assertEquals(1, seasonOptions(current, franchise).size)
    }

    @Test
    fun `season labels come from progress rather than the opened season`() {
        val first = anime("ax:1", "Сезон 1", 2020, episodes = 12)
        val opened = anime("ax:2", "Сезон 2", 2021, episodes = 12)
        val third = anime("ax:3", "Сезон 3", 2022, episodes = 12)
        val rows = seasonListOptions(
            current = opened,
            seasons = listOf(first, opened, third),
            progress = mapOf(
                first.id to TitleWatchState(completedEpisodes = 12, lastUpdatedAt = 10),
                opened.id to TitleWatchState(completedEpisodes = 3, hasInProgress = true, lastUpdatedAt = 20),
            ),
            currentSegmentCount = 12,
        )

        assertEquals(listOf(1, 2, 3), rows.map { it.order })
        assertEquals(
            listOf(SeasonProgressLabel.WATCHED, SeasonProgressLabel.WATCHING, SeasonProgressLabel.NEXT),
            rows.map { it.label },
        )
    }

    @Test
    fun `opening an untouched later season does not make it current`() {
        val first = anime("ax:1", "Сезон 1", 2020, episodes = 12)
        val opened = anime("ax:2", "Сезон 2", 2021, episodes = 12)
        val rows = seasonListOptions(opened, listOf(first, opened), emptyMap(), 12)

        assertEquals(SeasonProgressLabel.NEXT, rows.first().label)
        assertEquals(null, rows.last().label)
    }

    @Test
    fun `next season is the first gap in watch order`() {
        val first = anime("ax:1", "Сезон 1", 2020, episodes = 12)
        val second = anime("ax:2", "Сезон 2", 2021, episodes = 12)
        val third = anime("ax:3", "Сезон 3", 2022, episodes = 12)
        val rows = seasonListOptions(
            current = second,
            seasons = listOf(first, second, third),
            progress = mapOf(
                first.id to TitleWatchState(completedEpisodes = 12),
                third.id to TitleWatchState(completedEpisodes = 12),
            ),
            currentSegmentCount = 12,
        )

        assertEquals(SeasonProgressLabel.NEXT, rows[1].label)
    }

    private fun anime(id: String, title: String, year: Int, episodes: Int = 0) =
        com.aniblaze.aggregator.model.Anime(
            id = id, title = title, poster = "", year = year, episodesTotal = episodes,
        )

    private fun segment(number: Int, title: String, contentId: String = "tmdbtv:42") = Segment(
        id = "$contentId#$number",
        contentId = contentId,
        number = number,
        title = title,
    )
}

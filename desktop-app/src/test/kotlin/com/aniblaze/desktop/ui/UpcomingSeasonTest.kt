package com.aniblaze.desktop.ui

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.source.EpisodeAirDates
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Страница ещё не вышедшего сезона: точность подписи ровно такая, как у источника, —
 * ничего не додумывается ни про время, ни про трейлер.
 */
class UpcomingSeasonTest {
    private val today = java.time.LocalDate.of(2026, 9, 21)
    private val zone = java.time.ZoneId.systemDefault()

    @Test fun `точное время - дата, время и обратный отсчёт`() {
        val at = java.time.LocalDate.of(2027, 1, 4).atTime(0, 0).atZone(zone).toEpochSecond()
        val schedule = EpisodeAirDates.TitleSchedule(status = EpisodeAirDates.Status.UPCOMING, premiereAt = at, premiereOn = java.time.LocalDate.of(2027, 1, 4))
        val now = at - (2 * 86_400 + 3 * 3600 + 4 * 60 + 5)
        val tiles = upcomingTiles(schedule, now, today)
        assertEquals(listOf("Статус", "Премьера", "До премьеры"), tiles.map { it.label })
        assertEquals("Ещё не вышел", tiles[0].value)
        assertEquals("4 янв 2027 в 00:00", tiles[1].value)
        assertEquals("2 д 03:04:05", tiles[2].value)
        // Время вышло — не «серии доступны», а «проверяем источник».
        assertEquals("Время вышло", upcomingTiles(schedule, at + 1, today)[2].value)
    }

    @Test fun `только дата - без таймера и без выдуманного часа`() {
        val schedule = EpisodeAirDates.TitleSchedule(status = EpisodeAirDates.Status.UPCOMING, premiereOn = java.time.LocalDate.of(2027, 1, 4))
        val tiles = upcomingTiles(schedule, 0L, today)
        assertEquals(listOf("Статус", "Премьера"), tiles.map { it.label })
        assertEquals("4 янв 2027", tiles[1].value)
        assertEquals("время не объявлено", tiles[1].caption)
    }

    @Test fun `только сезон - «Зима 2027», совсем ничего - «не объявлена»`() {
        val season = upcomingTiles(EpisodeAirDates.TitleSchedule(status = EpisodeAirDates.Status.UPCOMING, premiereSeason = "Зима 2027"), 0L, today)
        assertEquals("Зима 2027", season[1].value)
        assertEquals("точная дата не объявлена", season[1].caption)
        val none = upcomingTiles(EpisodeAirDates.TitleSchedule(status = EpisodeAirDates.Status.UPCOMING), 0L, today)
        assertEquals("Не объявлена", none[1].value)
        assertEquals(TileTone.MUTED, none[1].tone)
    }

    @Test fun `отсчёт форматируется без дней, когда их нет`() {
        assertEquals("00:00:59", countdownLabel(59))
        assertEquals("01:00:00", countdownLabel(3600))
        assertEquals("00:00:00", countdownLabel(-5))
    }

    @Test fun `пустой список серий - сбой, анонс, вышло без видео - различаются`() {
        val card = Anime(id = "ya:1", title = "t", poster = "")
        val upcoming = EpisodeAirDates.TitleSchedule(status = EpisodeAirDates.Status.UPCOMING)
        val airing = EpisodeAirDates.TitleSchedule(status = EpisodeAirDates.Status.AIRING)
        assertEquals(EmptyEpisodes.FAILED, emptyEpisodesState(card, upcoming, failed = true))
        assertEquals(EmptyEpisodes.UPCOMING, emptyEpisodesState(card, upcoming, failed = false))
        // Расписание молчит, но каталог говорит «анонс» — анонс.
        assertEquals(EmptyEpisodes.UPCOMING, emptyEpisodesState(card.copy(airingStatus = 3), EpisodeAirDates.TitleSchedule.EMPTY, failed = false))
        // Выходит/вышло, а видео нет — это НЕ анонс.
        assertEquals(EmptyEpisodes.NO_VIDEO_RELEASED, emptyEpisodesState(card, airing, failed = false))
        assertEquals(EmptyEpisodes.NO_VIDEO_RELEASED, emptyEpisodesState(card.copy(airingStatus = 1), EpisodeAirDates.TitleSchedule.EMPTY, failed = false))
        // Расписание говорит «выходит», хотя каталог отметил анонс, — верим расписанию.
        assertEquals(EmptyEpisodes.NO_VIDEO_RELEASED, emptyEpisodesState(card.copy(airingStatus = 3), airing, failed = false))
        assertEquals(EmptyEpisodes.NO_VIDEO, emptyEpisodesState(card, EpisodeAirDates.TitleSchedule.EMPTY, failed = false))
    }

    @Test fun `AniList - премьера точным временем, датой или сезоном, трейлер только с YouTube`() {
        val exact = JSONObject(
            """{"status":"NOT_YET_RELEASED","episodes":12,"nextAiringEpisode":{"episode":1,"airingAt":1798848000},
               "startDate":{"year":2027,"month":1,"day":2},"season":"WINTER","seasonYear":2027,
               "trailer":{"id":"abc123XYZ","site":"youtube","thumbnail":"https://i.ytimg.com/vi/abc123XYZ/hqdefault.jpg"},
               "airingSchedule":{"nodes":[{"episode":1,"airingAt":1798848000}]}}""",
        )
        val s = EpisodeAirDates.parseAniListMedia(exact)
        assertEquals(EpisodeAirDates.Status.UPCOMING, s.status)
        assertEquals(1798848000L, s.premiereAt)
        assertEquals(java.time.LocalDate.of(2027, 1, 2), s.premiereOn)
        assertEquals("Зима 2027", s.premiereSeason)
        assertEquals("abc123XYZ", s.trailerYoutubeId)
        assertEquals("https://www.youtube.com/watch?v=abc123XYZ", s.trailerUrl)

        val vague = JSONObject("""{"status":"NOT_YET_RELEASED","startDate":{"year":2027,"month":null,"day":null},"trailer":{"id":"x","site":"dailymotion"}}""")
        val v = EpisodeAirDates.parseAniListMedia(vague)
        assertEquals(0L, v.premiereAt)
        assertNull(v.premiereOn)
        assertEquals("2027", v.premiereSeason)
        assertTrue(v.trailerYoutubeId.isBlank(), "не-YouTube трейлер не подставляется")

        // У вышедшего тайтла premiereAt не выставляется даже при известной первой серии.
        val airing = JSONObject("""{"status":"RELEASING","airingSchedule":{"nodes":[{"episode":1,"airingAt":100}]}}""")
        assertEquals(0L, EpisodeAirDates.parseAniListMedia(airing).premiereAt)
    }

    @Test fun `Shikimori videos - сначала pv, иначе любой YouTube, не-YouTube пропускается`() {
        val body = """[
            {"kind":"op","url":"https://vk.com/video1","image_url":"//vk/1.jpg"},
            {"kind":"ed","url":"https://youtu.be/edXYZ12","image_url":"//img/ed.jpg"},
            {"kind":"pv","url":"https://www.youtube.com/watch?v=pvABC_9&t=1","image_url":"//img/pv.jpg"}
        ]"""
        assertEquals("pvABC_9" to "https://img/pv.jpg", EpisodeAirDates.parseShikimoriTrailer(body))
        assertEquals("edXYZ12" to "https://img/ed.jpg", EpisodeAirDates.parseShikimoriTrailer("""[{"kind":"ed","url":"https://youtu.be/edXYZ12","image_url":"//img/ed.jpg"}]"""))
        assertNull(EpisodeAirDates.parseShikimoriTrailer("""[{"kind":"pv","url":"https://vk.com/video1"}]"""))
        assertEquals("abc-_123", EpisodeAirDates.youtubeId("https://www.youtube.com/embed/abc-_123?rel=0"))
    }
}

package com.aniblaze.aggregator.source

import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Даты серий считаются из того, что отдают Shikimori/Jikan, — AniList, у которого
 * расписание было поштучным, отключил публичный API. Цифры в тестах взяты из живых
 * ответов, чтобы арифметика проверялась против реальности, а не против себя самой.
 */
class EpisodeAirDatesTest {

    private fun tokyo(date: String) = LocalDate.parse(date).atStartOfDay(BROADCAST_ZONE).toEpochSecond()

    @Test
    fun `airing show anchors on the exact next-episode timestamp`() {
        // Shikimori, mal 63508: ongoing, episodes_aired=6, next_episode_at 5 авг 18:00 MSK.
        val next = OffsetDateTime.parse("2026-08-05T18:00:00.000+03:00").toEpochSecond()
        val dates = deriveDates(
            total = 0, aired = 6, nextEpisode = 7, nextAt = next,
            firstAired = LocalDate.parse("2026-07-02"), releasedOn = null,
        )
        assertEquals(next, dates[7])
        // Шаг ровно неделя в обе стороны от якоря.
        assertEquals(next - 7 * 24 * 3600L, dates[6])
        assertEquals(next - 6 * 7 * 24 * 3600L, dates[1])
        assertEquals(7, dates.size)
    }

    @Test
    fun `finished show hits both known ends exactly`() {
        // Shikimori, mal 49596: 24 серии, 2022-10-09 → 2023-03-26. Между ними 24
        // недели, а не 23 — сезон уходил на новогодний перерыв, поэтому простой
        // недельный шаг от премьеры промахивался по финалу на целую серию.
        val dates = deriveDates(
            total = 24, aired = 24, nextEpisode = 0, nextAt = 0,
            firstAired = LocalDate.parse("2022-10-09"), releasedOn = LocalDate.parse("2023-03-26"),
        )
        assertEquals(tokyo("2022-10-09"), dates[1])
        assertEquals(tokyo("2023-03-26"), dates[24])
        assertEquals(24, dates.size)
        // 12-я серия действительно вышла 25 декабря — ровно 11 недель после премьеры;
        // перерыв пришёлся на новогодние праздники, то есть ПОСЛЕ неё.
        assertEquals(tokyo("2022-12-25"), dates[12])
        // День недели сохраняется у всех серий: сериал выходил по воскресеньям.
        val sunday = LocalDate.parse("2022-10-09").dayOfWeek
        assertTrue(
            (1..24).all {
                java.time.Instant.ofEpochSecond(dates.getValue(it)).atZone(BROADCAST_ZONE).dayOfWeek == sunday
            },
        )
        // Монотонность: каждая следующая серия не раньше предыдущей.
        assertTrue((2..24).all { dates.getValue(it) > dates.getValue(it - 1) })
    }

    @Test
    fun `single-drop release gives every episode the same date`() {
        val dates = deriveDates(
            total = 12, aired = 12, nextEpisode = 0, nextAt = 0,
            firstAired = LocalDate.parse("2024-01-05"), releasedOn = LocalDate.parse("2024-01-05"),
        )
        assertEquals(12, dates.size)
        assertTrue(dates.values.all { it == tokyo("2024-01-05") })
    }

    @Test
    fun `nothing known yields no dates rather than invented ones`() {
        assertTrue(deriveDates(0, 0, 0, 0, null, null).isEmpty())
        // Есть число серий, но не от чего отсчитывать — пусто, а не 1970 год.
        assertTrue(deriveDates(12, 0, 0, 0, null, null).isEmpty())
    }

    @Test
    fun `released с датой окончания сегодня или позже - ещё выходит`() {
        // Shikimori, mal 59193 (Реинкарнация безработного III), 19.09.2026: status=released,
        // released_on=2026-09-19, а 13-я серия по AniList выходила 20.09 — сезон не закрыт.
        val today = LocalDate.parse("2026-09-19")
        assertEquals(EpisodeAirDates.Status.AIRING, verifiedStatus(EpisodeAirDates.Status.FINISHED, today, today))
        assertEquals(EpisodeAirDates.Status.AIRING, verifiedStatus(EpisodeAirDates.Status.FINISHED, today.plusDays(8), today))
        // Действительно закончилось вчера — остаётся закрытым; без даты — верим источнику.
        assertEquals(EpisodeAirDates.Status.FINISHED, verifiedStatus(EpisodeAirDates.Status.FINISHED, today.minusDays(1), today))
        assertEquals(EpisodeAirDates.Status.FINISHED, verifiedStatus(EpisodeAirDates.Status.FINISHED, null, today))
        assertEquals(EpisodeAirDates.Status.AIRING, verifiedStatus(EpisodeAirDates.Status.AIRING, today.plusDays(30), today))
    }
}

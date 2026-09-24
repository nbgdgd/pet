package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlinx.coroutines.runBlocking
import com.aniblaze.network.HttpClient
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class RecentEpisodeEvidenceTest {
    private val now = Instant.parse("2026-09-02T18:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private fun card(count: Int = 9) = Anime("ax:1", "Сериал", "poster", status = "Series", year = 2026,
        airingStatus = 2, episodesAvailable = count, firstAiredAt = now - 57 * day, contentType = "TV")
    private fun event(next: Int = 10, at: Long = now + 6 * day) =
        CalendarEpisode(setOf("сериал", "series"), 2026, next, at)
    private fun enrich(a: Anime = card(), events: List<CalendarEpisode> = listOf(event())) =
        withRecentEpisodeEvidence(a, events, now)

    @Test fun `first launch fills using actual count plus calendar`() {
        val item = enrich()
        assertEquals(now - day, item.episodeEstimatedAt)
        assertEquals(0, item.episodeReleasedAt)
        assertEquals(9, observeEpisodeReleases(emptyList(), listOf(item), now).single().latestEpisode)
    }
    @Test fun `old undated cache can be bootstrapped without deleting it`() {
        val old = observeEpisodeReleases(emptyList(), listOf(card()), now - day)
        val next = observeEpisodeReleases(old, listOf(enrich()), now).single()
        assertEquals(old.single().sourceCounts, next.sourceCounts)
        assertTrue(next.estimated)
        assertEquals(now - day, next.latestAt)
    }
    @Test fun `unchanged episode cannot renew its date on refresh or restart`() {
        val file = Files.createTempDirectory("release-evidence").resolve("events.json").toFile()
        EpisodeReleaseTracker(file).observe(listOf(enrich()), now)
        val restarted = EpisodeReleaseTracker(file)
        restarted.observe(listOf(card().copy(episodeEstimatedAt = now)), now + day)
        assertEquals(now - day, restarted.recent(now + day).single().episodeEstimatedAt)
        assertTrue(restarted.recent(now + 15 * day).isEmpty())
    }
    @Test fun `old completed and upcoming do not use guessed dates`() {
        for (status in listOf(1, 3)) assertEquals(0, enrich(card().copy(airingStatus = status)).episodeEstimatedAt)
    }
    @Test fun `completed extra OVA still records real count growth`() {
        val old = observeEpisodeReleases(emptyList(), listOf(card().copy(airingStatus = 1)), now - day)
        val next = observeEpisodeReleases(old, listOf(card(10).copy(airingStatus = 1)), now).single()
        assertEquals(10, next.latestEpisode)
        assertFalse(next.estimated)
    }
    @Test fun `stale ongoing without new episodes does not pass weekly fallback`() {
        assertEquals(0, enrich(card(1).copy(firstAiredAt = now - 180 * day), emptyList()).episodeEstimatedAt)
        assertEquals(0, enrich(card().copy(firstAiredAt = now - 120 * day), emptyList()).episodeEstimatedAt)
    }
    @Test fun `calendar outage uses premiere and available count but not planned total`() {
        assertEquals(now - day, enrich(events = emptyList()).episodeEstimatedAt)
        assertEquals(0, enrich(card(0).copy(episodesTotal = 12), emptyList()).episodeEstimatedAt)
        assertEquals(0, enrich(card().copy(contentType = "OVA"), emptyList()).episodeEstimatedAt)
    }
    @Test fun `future episode and calendar count gaps cannot be guessed around`() {
        assertEquals(0, enrich(events = listOf(event(next = 9))).episodeEstimatedAt)
        assertEquals(0, enrich(events = listOf(event(next = 12))).episodeEstimatedAt)
        assertEquals(0, enrich(events = listOf(event(at = now + 30 * day))).episodeEstimatedAt)
    }
    @Test fun `due calendar episode requires source availability`() {
        assertEquals(now - day, enrich(card(10), listOf(event(at = now - day))).episodeEstimatedAt)
        assertEquals(0, enrich(card(7), listOf(event(at = now - day))).episodeEstimatedAt)
    }
    @Test fun `titles use exact season identity and alternate name not franchise`() {
        val alternate = card().copy(title = "Другое русское название", firstAiredAt = 0)
        assertTrue(enrich(alternate).episodeEstimatedAt > 0)
        val season = card().copy(title = "Сериал 2", status = "Series 2", firstAiredAt = 0)
        assertEquals(0, enrich(season).episodeEstimatedAt)
        assertEquals(0, enrich(card().copy(year = 2001, firstAiredAt = 0)).episodeEstimatedAt)
    }
    @Test fun `multiple providers merge once and keep highest proven episode`() {
        val out = observeEpisodeReleases(emptyList(), listOf(enrich(), enrich().copy(id = "alias")), now)
        assertEquals(1, out.size)
        assertEquals(2, out.single().sourceCounts.size)
        val later = observeEpisodeReleases(out, listOf(card(10).copy(id = "lagging")), now).single()
        assertEquals(9, later.latestEpisode)
    }
    @Test fun `exact evidence upgrades estimate without losing count`() {
        val baseline = observeEpisodeReleases(emptyList(), listOf(enrich()), now)
        val exact = observeEpisodeReleases(baseline, listOf(card().copy(episodeReleasedAt = now - day / 2)), now).single()
        assertFalse(exact.estimated)
        assertEquals(now - day / 2, exact.latestAt)
    }
    @Test fun `calendar is parsed and fetched in one cached batch`() = runBlocking {
        var calls = 0
        val raw = """[{"next_episode":10,"next_episode_at":"2026-09-08T18:00:00Z","anime":{"name":"Series","russian":"Сериал","status":"ongoing","aired_on":"2026-07-07"}}]"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(raw.toResponseBody()).build()
        }.build()
        val calendar = RecentEpisodeCalendar(HttpClient(client))
        repeat(50) { assertEquals(1, calendar.get(now).size) }
        assertEquals(1, calls)
        assertEquals(now - day, enrich(events = calendar.get(now)).episodeEstimatedAt)
    }
}

package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import java.nio.file.Files
import kotlin.test.*

class ReleaseTrackingTest {
    private val now = 1_788_364_800_000L
    private val day = 86_400_000L
    private fun anime(id: String = "ax:1", count: Int = 10, status: Int = 2, at: Long = 0) =
        Anime(id, "Пример сериала", "poster", episodesAvailable = count, episodesTotal = 12,
            airingStatus = status, year = 2026, episodeReleasedAt = at)
    private fun baseline(card: Anime = anime()) = observeEpisodeReleases(emptyList(), listOf(card), now - day)

    @Test fun `first sighting without an exact date is only a baseline`() {
        assertEquals(0, baseline().single().latestAt)
    }
    @Test fun `ongoing episode today has a dated event`() {
        val out = observeEpisodeReleases(baseline(), listOf(anime(count = 11)), now).single()
        assertEquals(11, out.latestEpisode)
        assertEquals(now, out.latestAt)
    }
    @Test fun `unchanged ongoing is not a release`() {
        assertEquals(0, observeEpisodeReleases(baseline(), listOf(anime()), now).single().latestAt)
    }
    @Test fun `old completed title does not become fresh on discovery`() {
        assertEquals(0, baseline(anime(status = 1, count = 12)).single().latestAt)
    }
    @Test fun `completed title can gain a real extra episode`() {
        val out = observeEpisodeReleases(baseline(anime(status = 1, count = 12)),
            listOf(anime(status = 1, count = 13)), now).single()
        assertEquals(13, out.latestEpisode)
        assertEquals(now, out.latestAt)
    }
    @Test fun `poster description rating and dub metadata cannot create an event`() {
        val before = baseline()
        val changes = listOf(anime().copy(poster = "new"), anime().copy(description = "new"),
            anime().copy(rating = 4.9), anime().copy(status = "New dub"))
        for (change in changes) assertEquals(0, observeEpisodeReleases(before, listOf(change), now).single().latestAt)
    }
    @Test fun `announced total is never an available episode count`() {
        assertTrue(observeEpisodeReleases(emptyList(), listOf(anime(count = 0).copy(episodesTotal = 24)), now).isEmpty())
    }
    @Test fun `metadata change never renews a release date`() {
        val fresh = observeEpisodeReleases(baseline(), listOf(anime(count = 11)), now)
        assertEquals(now, observeEpisodeReleases(fresh, listOf(anime(count = 11).copy(poster = "new")), now + day).single().latestAt)
    }
    @Test fun `new provider establishes baseline and lagging provider cannot repeat it`() {
        val first = observeEpisodeReleases(baseline(), listOf(anime(id = "alias", count = 12)), now)
        assertEquals(1, first.size)
        assertEquals(0, first.single().latestAt)
        val catchup = observeEpisodeReleases(first, listOf(anime(count = 11), anime(id = "alias", count = 12)), now + day)
        assertEquals(0, catchup.single().latestAt)
        assertEquals(12, catchup.single().highWater)
        assertEquals("alias", catchup.single().anime.id)
        assertEquals(2, catchup.single().sourceCounts.size)
    }
    @Test fun `source count falling then recovering is not new`() {
        val low = observeEpisodeReleases(baseline(), listOf(anime(count = 8)), now)
        assertEquals(10, low.single().highWater)
        assertEquals(0, observeEpisodeReleases(low, listOf(anime()), now + day).single().latestAt)
    }
    @Test fun `several new episodes record highest available number`() {
        val out = observeEpisodeReleases(baseline(), listOf(anime(count = 11), anime(count = 13)), now).single()
        assertEquals(13, out.latestEpisode)
    }
    @Test fun `exact date wins over time first observed`() {
        val out = observeEpisodeReleases(baseline(), listOf(anime(count = 11, at = now - day / 2)), now).single()
        assertEquals(now - day / 2, out.latestAt)
    }
    @Test fun `a future timestamp never announces an episode early`() {
        assertEquals(0, observeEpisodeReleases(emptyList(), listOf(anime(at = now + day)), now).single().latestAt)
    }
    @Test fun `different seasons and remakes do not merge`() {
        val out = observeEpisodeReleases(baseline(), listOf(
            anime(id = "ax:2").copy(title = "Пример сериала 2"),
            anime(id = "ax:3").copy(year = 2000)), now)
        assertEquals(3, out.size)
    }
    @Test fun `cache survives restart sorts by real release date and expires after fourteen days`() {
        val file = Files.createTempDirectory("aniblaze-releases-test").resolve("events.json").toFile()
        val store = EpisodeReleaseTracker(file)
        store.observe(listOf(anime(at = now - day), anime(id = "ax:2", at = now).copy(title = "Другой сериал"),
            anime(id = "ax:3", at = now - 15 * day).copy(title = "Старый сериал")), now)
        val restarted = EpisodeReleaseTracker(file)
        assertEquals(listOf("ax:2", "ax:1"), restarted.recent(now).map { it.id })
        restarted.observe(listOf(anime(at = now - day)), now)
        assertEquals(2, restarted.recent(now).size)
        assertTrue(restarted.recent(now + 15 * day).isEmpty())
    }
    @Test fun `corrupt cache starts without invented events`() {
        val file = Files.createTempDirectory("aniblaze-corrupt-test").resolve("events.json").toFile()
        file.writeText("not json")
        val store = EpisodeReleaseTracker(file)
        store.observe(listOf(anime()), now)
        assertTrue(store.recent(now).isEmpty())
    }
    @Test fun `history favorite and catalog baseline alone never draw stopped on episode one`() {
        val card = anime().toPersisted()
        val state = PersistedState(history = listOf(card), favorites = listOf(card), episodeCounts = mapOf(card.id to 12),
            playerPrefs = mapOf(card.id to PlayerPref(lastSegment = 1)))
        assertNull(buildWatchIndex(state)[card.id])
        val zero = state.copy(progress = listOf(ProgressEntry(card, 1, 0, 1_400_000, now)))
        assertNull(buildWatchIndex(zero)[card.id])
        val real = state.copy(progress = listOf(ProgressEntry(card, 1, 40_000, 1_400_000, now)))
        assertEquals(1, buildWatchIndex(real)[card.id]?.episode)
    }
}

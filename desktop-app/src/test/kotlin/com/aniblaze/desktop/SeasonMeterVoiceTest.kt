package com.aniblaze.desktop

import com.aniblaze.aggregator.model.*
import com.aniblaze.desktop.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class SeasonMeterVoiceTest {
    private fun anime(id: String, year: Int) = Anime(id, "Сезон $id", "poster-$id", year = year, airingStatus = 1, episodesTotal = 2)
    private val a = anime("a", 2020)
    private val b = anime("b", 2021)
    private val c = anime("c", 2022)

    @Test fun menuUsesProgressNotOpenedTitle() {
        val state = PersistedState(watched = setOf("a#1", "a#2"),
            progress = listOf(ProgressEntry(b.toPersisted(), 1, 20000, 100000, 50)))
        val rows = seasonMenu(c, listOf(c, b, a), state)
        assertEquals(listOf("a", "b", "c"), rows.map { it.anime.id })
        assertEquals(listOf("Просмотрено", "Сейчас смотрите", "Следующий"), rows.map { it.status })
        assertEquals("1. Сезон 1", rows.first().label)
    }
    @Test fun menuRecognizesLegacyWatchedEpisodesWithoutTimestamps() {
        val rows = seasonMenu(
            current = c,
            seasons = listOf(c, b, a),
            state = PersistedState(watched = setOf("b#1")),
        )
        assertEquals(listOf("", "Сейчас смотрите", "Следующий"), rows.map { it.status })
    }
    @Test fun multiSeasonStartsFromTheResumeEpisodeInsteadOfSeasonOne() {
        val segments = listOf(
            Segment("show#1", "show", 1, "С1 · Серия 1"),
            Segment("show#2", "show", 2, "С1 · Серия 2"),
            Segment("show#3", "show", 3, "С2 · Серия 1"),
        )
        assertEquals(2, initialSeasonForEpisodes(segments, segments.last()))
        assertEquals(1, initialSeasonForEpisodes(segments, segments.first()))
    }
    @Test fun menuDeduplicatesExactCopiesButPreservesSpecials() {
        val copy = a.copy(id = "alias")
        val special = a.copy(id = "ova", contentType = "OVA")
        val rows = seasonMenu(a, listOf(a, copy, special, b, a), PersistedState())
        assertEquals(3, rows.size)
        assertEquals(1, rows.count { it.status == "Следующий" })
        assertTrue(rows.any { it.label.contains("OVA") })
    }
    @Test fun replayIsCurrentAndOngoingIsNotCompleted() {
        val ongoing = b.copy(airingStatus = 2)
        val state = PersistedState(watched = setOf("a#1", "a#2", "b#1", "b#2"),
            watchedAt = mapOf("a#2" to 10, "b#2" to 20),
            progress = listOf(ProgressEntry(a.toPersisted(), 1, 20000, 100000, 30)))
        val rows = seasonMenu(ongoing, listOf(a, ongoing), state)
        assertEquals("Сейчас смотрите", rows.first().status)
        assertNotEquals("Просмотрено", rows.last().status)
    }
    @Test fun finishedFranchiseHasNoNonexistentNext() {
        val rows = seasonMenu(a, listOf(a, b), PersistedState(completedTitles = setOf("a", "b")))
        assertTrue(rows.all { it.status == "Просмотрено" })
    }
    @Test fun personalRatingsOverrideCommunityInAllCategories() {
        val state = PersistedState(ratings = listOf(RatedTitle(a.toPersisted().copy(malScore = 4.0, malVotes = 500), 5),
            RatedTitle(b.toPersisted().copy(malScore = 9.0, malVotes = 500), 1), RatedTitle(c.toPersisted(), 3)))
        val stats = buildStats(state)
        assertEquals(listOf("a"), stats.bestTop.map { it.id })
        assertEquals(listOf("b"), stats.trashTop.map { it.id })
        assertEquals(listOf("c"), stats.normalTop.map { it.id })
    }
    @Test fun meterIgnoresUnwatchedFavoritesAndUnknownCommunityScores() {
        val stats = buildStats(PersistedState(favorites = listOf(a.toPersisted().copy(malScore = 9.0, malVotes = 500)),
            history = listOf(b.toPersisted()), watched = setOf("b#1")))
        assertEquals(0, stats.trashJudged)
        assertNull(TrashAnime.category(0, 0.0, 0, 1000))
        assertEquals(TrashAnime.Category.BEST, TrashAnime.category(0, 8.3, 0, 1000))
        assertEquals(TrashAnime.Category.NORMAL, TrashAnime.category(0, 7.0, 0, 1000))
    }
    @Test fun distributionAlwaysIncludesEveryNonzeroCategory() {
        val distribution = categoryDistribution(41, 40, 15)
        assertEquals(3, distribution.size)
        assertEquals(1f, distribution.sumOf { it.first.toDouble() }.toFloat(), 0.0001f)
        assertEquals(15f / 96f, distribution.last().first, 0.0001f)
    }
    private val voices = listOf(Translation(4, "AnimeVost", views = 10000), Translation(3, "AniLibria", views = 9000),
        Translation(2, "Студийная Банда", views = 2), Translation(1, "Дубляж", views = 1))
    private fun stream(voice: Int) = ContentResult("local:$voice", "720p", source = "fixture", translations = voices, translationId = voice)
    @Test fun voiceAliasesAndPopularityDoNotChangePriority() {
        assertEquals(listOf(1, 2, 3), prioritizedVoices(voices).map { it.id })
        for (name in listOf("Studio Band", "StudioBand", "Студийная Банда")) {
            assertEquals(listOf(2, 3), prioritizedVoices(listOf(voices[1], Translation(2, name))).map { it.id })
        }
        assertTrue(prioritizedVoices(listOf(Translation(1, "Дубляж", isSub = true))).isEmpty())
    }
    @Test fun unavailablePriorityFallsThroughAndExceptionsKeepOriginal() = runBlocking {
        val calls = mutableListOf<Int>()
        val selected = applyVoicePriority(stream(4), true, null) { calls += it; if (it == 1) null else stream(it) }
        assertEquals(2, selected?.translationId)
        assertEquals(listOf(1, 2), calls)
        assertEquals(4, applyVoicePriority(stream(4), true, null) { throw IllegalStateException("fixture") }?.translationId)
        assertEquals(4, applyVoicePriority(stream(4), true, null) { stream(it).copy(source = "different") }?.translationId)
    }
    @Test fun disabledOrManualChoiceNeverRequestsAnotherDub() = runBlocking {
        for ((enabled, preference) in listOf(false to null, true to PlayerPref(voice = 4))) {
            assertEquals(4, applyVoicePriority(stream(4), enabled, preference) { error("Must not resolve") }?.translationId)
        }
        assertEquals(1, applyVoicePriority(stream(4), true, PlayerPref(voice = 4, voiceManual = false)) { stream(it) }?.translationId)
    }
    @Test fun priorityAndManualChoiceSurviveReloadAndAutomaticFallback() {
        val file = Files.createTempDirectory("aniblaze-voice-").resolve("state.json").toFile()
        val settings = AppSettings(file)
        settings.setVoicePriority(true)
        settings.savePlayerVoice("title", 4)
        settings.savePlayerVoice("title", 1, manual = false)
        settings.savePlayerVoice("other", 2, manual = false)
        assertTrue(settings.flush())
        val reloaded = AppSettings(file).state.value
        assertTrue(reloaded.voicePriority)
        assertEquals(4, reloaded.playerPrefs["title"]?.voice)
        assertEquals(false, reloaded.playerPrefs["other"]?.voiceManual)
        assertFalse(Json.decodeFromString<PersistedState>("{}").voicePriority)
        assertTrue(Json.decodeFromString<PlayerPref>("{\"voice\":4}").voiceManual)
    }
}

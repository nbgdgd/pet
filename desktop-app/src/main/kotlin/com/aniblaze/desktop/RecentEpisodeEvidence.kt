package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.OffsetDateTime
import java.time.LocalDate

internal data class CalendarEpisode(val names: Set<String>, val year: Int, val next: Int, val at: Long)

internal fun parseReleaseCalendar(raw: String): List<CalendarEpisode> {
    val entries = JSONArray(raw)
    return (0 until entries.length()).mapNotNull { i ->
        val item = entries.optJSONObject(i) ?: return@mapNotNull null
        val anime = item.optJSONObject("anime") ?: return@mapNotNull null
        if (anime.optString("status") != "ongoing") return@mapNotNull null
        val at = runCatching { OffsetDateTime.parse(item.optString("next_episode_at")).toInstant().toEpochMilli() }
            .getOrNull() ?: return@mapNotNull null
        CalendarEpisode(listOf(anime.optString("russian"), anime.optString("name"))
            .filter { it.isNotBlank() && it != "null" }.map(::releaseTitleKey).toSet(),
            runCatching { LocalDate.parse(anime.optString("aired_on")).year }.getOrDefault(0),
            item.optInt("next_episode"), at)
    }
}

private const val WEEK_MS = 7L * 86_400_000

/** Actual playable count + near-term broadcast evidence, never updatedAt alone. */
internal fun withRecentEpisodeEvidence(card: Anime, calendar: List<CalendarEpisode>, now: Long): Anime {
    if (card.episodeReleasedAt > 0 || card.episodesAvailable <= 0 || card.airingStatus in listOf(1, 3)) return card
    val names = listOf(card.title, card.status).filter { it.isNotBlank() }.map(::releaseTitleKey).toSet()
    val match = calendar.filter { event -> event.names.any { it in names } &&
        (card.year == 0 || event.year == 0 || card.year == event.year) }.singleOrNull()
    val count = card.episodesAvailable
    val calendarAt = match?.takeIf { it.at in (now - WEEK_MS)..(now + WEEK_MS) }?.let {
        when (it.next - count) {
            0 -> it.at.takeIf { date -> date <= now }
            1 -> it.at - WEEK_MS
            else -> null // gaps / batch drops / a lagging provider are not weekly evidence
        }
    }
    if (match != null && calendarAt == null) return card // contradictory/hiatus calendar beats cadence guessing
    // Bounded cold-start fallback for this cour, when the calendar is unavailable.
    // Not for films/OVA, unknown status, long-running shows or stale episode counts.
    val weeklyAt = if (card.airingStatus == 2 && count in 2..30 && card.firstAiredAt > 0 &&
        card.firstAiredAt <= now && now - card.firstAiredAt <= 210L * 86_400_000 &&
        !Regex("(?i)movie|film|ova|ona|фильм|спешл").containsMatchIn(card.contentType))
        card.firstAiredAt + (count - 1) * WEEK_MS else null
    val date = (calendarAt ?: weeklyAt)?.takeIf { it in (now - NEW_EPISODE_WINDOW_MS)..now } ?: return card
    return card.copy(episodeEstimatedAt = date)
}

/** One batch per refresh, independent of card count. Failures retain the last good calendar. */
internal class RecentEpisodeCalendar(private val http: HttpClient?) {
    private val lock = Mutex()
    private var checkedAt = 0L
    private var entries = emptyList<CalendarEpisode>()
    suspend fun get(now: Long = System.currentTimeMillis()): List<CalendarEpisode> = lock.withLock {
        if (http == null || now - checkedAt < 5 * 60_000) return@withLock entries
        checkedAt = now
        try {
            val raw = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                withContext(Dispatchers.IO) { http.getHtml("https://shikimori.one/api/calendar") }
            }
            if (raw != null) entries = parseReleaseCalendar(raw)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { com.aniblaze.desktop.player.PlayerDiagnostics.failure("episodes.calendar", error) }
        entries
    }
}

package com.aniblaze.aggregator.source

import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Airing information for a title: per-episode air dates, the release status, and
 * when the next episode is due.
 *
 * None of the playback sources carry any of it — Anixart's episode objects have an
 * `addedDate` that is always 0, and AniLibria only covers its own small catalog.
 * AniList returns all three in one query and is keyed by MAL id; Shikimori ids ARE
 * MAL ids, so one title search there (it handles Russian names) gives us the key.
 *
 * Cached per title, misses included, so the screen never re-queries on recompose.
 */
@Singleton
class EpisodeAirDates @Inject constructor(
    private val http: HttpClient,
) {

    private val malIdCache = ConcurrentHashMap<String, Int>()
    private val cache = ConcurrentHashMap<Int, TitleSchedule>()

    /** AniList release status, mapped to what the UI needs to say. */
    enum class Status { AIRING, FINISHED, UPCOMING, CANCELLED, HIATUS, UNKNOWN }

    data class TitleSchedule(
        /** Episode number → air time (epoch seconds). */
        val dates: Map<Int, Long> = emptyMap(),
        val status: Status = Status.UNKNOWN,
        /** Total episodes announced (0 = unknown). */
        val totalEpisodes: Int = 0,
        /** Next episode number, 0 = none scheduled. */
        val nextEpisode: Int = 0,
        /** When the next episode airs (epoch seconds), 0 = unknown. */
        val nextAiringAt: Long = 0,
    ) {
        companion object { val EMPTY = TitleSchedule() }
    }

    /**
     * [title] is the display (usually Russian) name; [altTitle] the original/romaji
     * one, used as a second lookup key when Shikimori's Russian naming differs from
     * the catalog's — without it those titles get no dates and no schedule at all.
     */
    suspend fun forTitle(title: String, altTitle: String = ""): TitleSchedule = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext TitleSchedule.EMPTY
        val malId = malId(title)
            ?: altTitle.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }?.let { malId(it) }
            ?: return@withContext TitleSchedule.EMPTY
        cache[malId]?.let { return@withContext it }
        val schedule = fetch(malId)
        cache[malId] = schedule
        schedule
    }

    /** Shikimori id == MAL id, so its search doubles as a MAL lookup. */
    private suspend fun malId(title: String): Int? {
        malIdCache[title]?.let { return it }
        val query = java.net.URLEncoder.encode(title.take(80), "UTF-8")
        val body = http.getHtml("$SHIKIMORI_API/animes?search=$query&limit=1") ?: return null
        val id = runCatching {
            JSONArray(body).optJSONObject(0)?.optInt("id", 0)?.takeIf { it > 0 }
        }.getOrNull() ?: return null
        malIdCache[title] = id
        return id
    }

    private suspend fun fetch(malId: Int): TitleSchedule {
        val body = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("id", malId))
            .toString()
        val resp = http.postJson(ANILIST_API, body) ?: return TitleSchedule.EMPTY
        return runCatching {
            val media = JSONObject(resp).optJSONObject("data")?.optJSONObject("Media")
                ?: return TitleSchedule.EMPTY
            val dates = buildMap {
                val nodes = media.optJSONObject("airingSchedule")?.optJSONArray("nodes")
                for (i in 0 until (nodes?.length() ?: 0)) {
                    val node = nodes?.optJSONObject(i) ?: continue
                    val episode = node.optInt("episode", 0).takeIf { it > 0 } ?: continue
                    val airingAt = node.optLong("airingAt", 0L).takeIf { it > 0 } ?: continue
                    // First entry per number wins: AniList repeats a rescheduled
                    // broadcast with the later slot.
                    putIfAbsent(episode, airingAt)
                }
            }
            val next = media.optJSONObject("nextAiringEpisode")
            TitleSchedule(
                dates = dates,
                status = when (media.optString("status")) {
                    "RELEASING" -> Status.AIRING
                    "FINISHED" -> Status.FINISHED
                    "NOT_YET_RELEASED" -> Status.UPCOMING
                    "CANCELLED" -> Status.CANCELLED
                    "HIATUS" -> Status.HIATUS
                    else -> Status.UNKNOWN
                },
                totalEpisodes = media.optInt("episodes", 0),
                nextEpisode = next?.optInt("episode", 0) ?: 0,
                nextAiringAt = next?.optLong("airingAt", 0L) ?: 0L,
            )
        }.onFailure { Timber.w(it, "[AirDates] parse failed for mal=%d", malId) }
            .getOrDefault(TitleSchedule.EMPTY)
    }

    private companion object {
        const val ANILIST_API = "https://graphql.anilist.co"
        const val SHIKIMORI_API = "https://shikimori.one/api"
        const val QUERY =
            "query(\$id:Int){Media(idMal:\$id,type:ANIME){status episodes " +
                "nextAiringEpisode{episode airingAt} " +
                "airingSchedule(perPage:100){nodes{episode airingAt}}}}"
    }
}

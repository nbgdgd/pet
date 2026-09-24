package com.aniblaze.aggregator.source

import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Airing information for a title: per-episode air dates, the release status, and
 * when the next episode is due.
 *
 * None of the playback sources carry any of it. Anixart's episode objects have an
 * `addedDate` that is always 0, AnimeVost's playlist has no dates at all, and only
 * AniLiberty's small catalog has a usable timestamp.
 *
 * Primary source is SHIKIMORI. It was AniList, whose one GraphQL query returned the
 * whole airing schedule — until AniList disabled its public API outright ("The
 * AniList API has been temporarily disabled due to severe stability issues", HTTP
 * 403 on every request). That silently emptied every schedule in the app: no next
 * episode, no "проект завершён", no dates in the episode list.
 *
 * Shikimori answers `/api/animes/{id}` with `status`, `episodes`, `episodes_aired`,
 * `aired_on` and — crucially — `next_episode_at`, and we already call it to resolve
 * the MAL id, so the host is warm. Jikan/MyAnimeList is the fallback: its
 * `/anime/{id}` works even though its `/episodes` endpoint answers 504. AniList is
 * kept last so the app heals itself the day it comes back.
 *
 * Per-episode dates are DERIVED (weekly cadence anchored on `next_episode_at`)
 * rather than listed — only AniList ever published them one by one, and for a
 * weekly broadcast the derivation is exact.
 *
 * Cached per title; a miss is cached too, so the screen never re-queries on a
 * recomposition.
 */
class EpisodeAirDates(
    private val http: HttpClient,
    private val malIds: AniskipTimings,
) {

    private val cache = java.util.concurrent.ConcurrentHashMap<Int, TitleSchedule>()
    /** AniList is dead as of this writing; stop paying a round trip per title for it. */
    private val anilistFailures = java.util.concurrent.atomic.AtomicInteger(0)

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
        /**
         * Премьера ТОЧНЫМ временем (epoch s) — только когда источник назвал время
         * первой серии (AniList `airingSchedule`). 0 — точного времени нет, и
         * обратный отсчёт до часа показывать нельзя.
         */
        val premiereAt: Long = 0,
        /** Премьера датой без времени (Shikimori `aired_on`, AniList `startDate`). */
        val premiereOn: LocalDate? = null,
        /** Когда известны лишь сезон и год: «Зима 2027». Пусто — не знаем и этого. */
        val premiereSeason: String = "",
        /** Трейлер именно этого тайтла: id ролика YouTube и превью. Пусто — нет. */
        val trailerYoutubeId: String = "",
        val trailerThumbnail: String = "",
    ) {
        companion object { val EMPTY = TitleSchedule() }

        val trailerUrl: String get() = if (trailerYoutubeId.isBlank()) "" else "https://www.youtube.com/watch?v=$trailerYoutubeId"
    }

    /**
     * [title] is the display (usually Russian) name; [altTitle] the original/romaji
     * one, used as a second lookup key when Shikimori's Russian naming differs from
     * the catalog's — without it those titles get no dates and no schedule at all.
     */
    suspend fun forTitle(
        title: String,
        altTitle: String = "",
        /** Точный MAL id карточки, если источник его знает: поиск по названию тогда не нужен. */
        malIdHint: Int = 0,
        /** Сбросить кэш: таймер до премьеры дошёл до нуля, статус надо перечитать. */
        refresh: Boolean = false,
    ): TitleSchedule = withContext(Dispatchers.IO) {
        if (title.isBlank() && malIdHint <= 0) return@withContext TitleSchedule.EMPTY
        // episode=2 — сигнал «это сериал, а не фильм»: без него Shikimori отдаёт
        // первым спинофф-фильм (см. AniskipTimings.malId), и расписание бралось от
        // чужой записи. Точное число серий здесь не важно, важен сам факт сериала.
        val malId = malIdHint.takeIf { it > 0 }
            ?: malIds.malId(title, altTitle, episode = 2)
            ?: return@withContext TitleSchedule.EMPTY
        if (refresh) cache.remove(malId)
        cache[malId]?.let { return@withContext it }
        // AniList первым: только у него настоящее расписание ПО СЕРИЯМ и честный
        // «следующий эпизод». Shikimori же считает серии, а не даты, и ошибается в обе
        // стороны: «Реинкарнацию безработного III» (MAL 59193) он 19.09.2026 отдал как
        // released / 14 из 14, хотя 13-я выходила 20.09, — и страница писала
        // «Завершён, все серии вышли». Отвалится AniList (403, как бывало) — счётчик
        // отказов переключит на Shikimori/Jikan, как раньше.
        val resolved = fromAniList(malId)?.takeIf { it.status != Status.UNKNOWN }
            ?: fromShikimori(malId)
            ?: fromJikan(malId)
            ?: TitleSchedule.EMPTY
        // Трейлер у AniList приходит тем же запросом; когда его нет (или AniList
        // молчит) — спрашиваем видео у Shikimori, но только для того же MAL id.
        val schedule = if (resolved.trailerYoutubeId.isBlank() && resolved.status == Status.UPCOMING) {
            shikimoriTrailer(malId)?.let { (id, thumb) -> resolved.copy(trailerYoutubeId = id, trailerThumbnail = thumb) } ?: resolved
        } else {
            resolved
        }
        com.aniblaze.desktop.player.PlayerDiagnostics.log(
            "airDates.resolved",
            "mal=$malId; status=${schedule.status}; next=${schedule.nextEpisode}; at=${schedule.nextAiringAt}; dates=${schedule.dates.size}",
        )
        cache[malId] = schedule
        schedule
    }

    // --- Shikimori (primary) ---

    private suspend fun fromShikimori(malId: Int): TitleSchedule? {
        val body = http.getHtml("$SHIKIMORI_API/animes/$malId") ?: return null
        return runCatching {
            val o = JSONObject(body)
            val status = when (o.optString("status")) {
                "ongoing" -> Status.AIRING
                "released" -> Status.FINISHED
                "anons" -> Status.UPCOMING
                else -> Status.UNKNOWN
            }
            if (status == Status.UNKNOWN) return null
            val total = o.optInt("episodes", 0)
            val aired = o.optInt("episodes_aired", 0)
            // "2026-08-05T18:00:00.000+03:00" — уже со смещением, берём как есть.
            val nextAt = o.optString("next_episode_at").takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() } ?: 0L
            val firstAired = o.optString("aired_on").takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val releasedOn = o.optString("released_on").takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val nextEpisode = if (nextAt > 0) (aired + 1).coerceAtLeast(1) else 0
            // «Завершено» с датой окончания сегодня или в будущем — противоречие: сезон
            // не может кончиться завтра. Shikimori ставит released заранее; считаем
            // такой тайтл выходящим, а не закрытым (см. verifiedStatus).
            val verified = verifiedStatus(status, releasedOn, LocalDate.now(BROADCAST_ZONE))
            TitleSchedule(
                // У анонса серий ещё нет — и выдуманных дат по неделям быть не должно.
                dates = if (verified == Status.UPCOMING) emptyMap() else deriveDates(
                    total = total,
                    aired = aired,
                    nextEpisode = nextEpisode,
                    nextAt = nextAt,
                    firstAired = firstAired,
                    releasedOn = releasedOn,
                ),
                status = verified,
                totalEpisodes = total,
                nextEpisode = nextEpisode,
                nextAiringAt = nextAt,
                // Shikimori знает премьеру только датой; точного времени у него нет.
                premiereOn = firstAired,
                // Точное время у Shikimori бывает лишь у next_episode_at, и для анонса
                // это и есть первая серия.
                premiereAt = if (verified == Status.UPCOMING) nextAt else 0L,
            )
        }.onFailure { Timber.w(it, "[AirDates] shikimori parse failed for mal=%d", malId) }.getOrNull()
    }

    // --- Jikan / MyAnimeList (fallback) ---

    private suspend fun fromJikan(malId: Int): TitleSchedule? {
        val body = http.getHtml("$JIKAN_API/anime/$malId") ?: return null
        return runCatching {
            val o = JSONObject(body).optJSONObject("data") ?: return null
            val status = when (o.optString("status")) {
                "Currently Airing" -> Status.AIRING
                "Finished Airing" -> Status.FINISHED
                "Not yet aired" -> Status.UPCOMING
                else -> Status.UNKNOWN
            }
            if (status == Status.UNKNOWN) return null
            val total = o.optInt("episodes", 0)
            val firstAired = o.optJSONObject("aired")?.optString("from")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
            // У Jikan нет ни номера вышедшей серии, ни точного времени следующей —
            // только слот вещания ("Thursdays at 00:00 (JST)"). Из первой даты и
            // недельного шага этого хватает, чтобы посчитать обе величины.
            val aired = if (status == Status.AIRING && firstAired != null) {
                val weeks = Duration.between(
                    firstAired.atStartOfDay(BROADCAST_ZONE).toInstant(),
                    java.time.Instant.now(),
                ).toDays() / 7
                (weeks + 1).toInt().coerceIn(0, if (total > 0) total else Int.MAX_VALUE)
            } else {
                total
            }
            val nextAt = if (status == Status.AIRING && firstAired != null) {
                firstAired.plusWeeks(aired.toLong()).atStartOfDay(BROADCAST_ZONE).toEpochSecond()
            } else {
                0L
            }
            TitleSchedule(
                dates = deriveDates(
                    total = total,
                    aired = aired,
                    nextEpisode = if (nextAt > 0) aired + 1 else 0,
                    nextAt = nextAt,
                    firstAired = firstAired,
                    releasedOn = null,
                ),
                status = status,
                totalEpisodes = total,
                nextEpisode = if (nextAt > 0) aired + 1 else 0,
                nextAiringAt = nextAt,
                premiereOn = firstAired,
            )
        }.onFailure { Timber.w(it, "[AirDates] jikan parse failed for mal=%d", malId) }.getOrNull()
    }

    // --- AniList (первый, пока отвечает; после ANILIST_GIVE_UP_AFTER отказов — молчит) ---

    private suspend fun fromAniList(malId: Int): TitleSchedule? {
        if (anilistFailures.get() >= ANILIST_GIVE_UP_AFTER) return null
        val body = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("id", malId))
            .toString()
        val resp = http.postJson(ANILIST_API, body) ?: run {
            anilistFailures.incrementAndGet()
            return null
        }
        return runCatching {
            val media = JSONObject(resp).optJSONObject("data")?.optJSONObject("Media") ?: return null
            anilistFailures.set(0)
            parseAniListMedia(media)
        }.onFailure { Timber.w(it, "[AirDates] anilist parse failed for mal=%d", malId) }.getOrNull()
    }

    /** Трейлер с Shikimori: `/animes/{id}/videos`, первое видео вида `pv` (иначе любое YouTube). */
    private suspend fun shikimoriTrailer(malId: Int): Pair<String, String>? {
        val body = http.getHtml("$SHIKIMORI_API/animes/$malId/videos") ?: return null
        return runCatching { parseShikimoriTrailer(body) }.getOrNull()
    }

    companion object {
        private const val SHIKIMORI_API = "https://shikimori.one/api"
        private const val JIKAN_API = "https://api.jikan.moe/v4"
        private const val ANILIST_API = "https://graphql.anilist.co"
        private const val ANILIST_GIVE_UP_AFTER = 3

        // perPage caps at 50 per page; 100 covers every airing series we care about.
        private const val QUERY =
            "query(\$id:Int){Media(idMal:\$id,type:ANIME){status episodes " +
                "nextAiringEpisode{episode airingAt} " +
                "startDate{year month day} season seasonYear " +
                "trailer{id site thumbnail} " +
                "airingSchedule(perPage:100){nodes{episode airingAt}}}}"

        /** Разбор ответа AniList — чистая функция ради теста. */
        fun parseAniListMedia(media: JSONObject): TitleSchedule {
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
            val status = when (media.optString("status")) {
                "RELEASING" -> Status.AIRING
                "FINISHED" -> Status.FINISHED
                "NOT_YET_RELEASED" -> Status.UPCOMING
                "CANCELLED" -> Status.CANCELLED
                "HIATUS" -> Status.HIATUS
                else -> Status.UNKNOWN
            }
            // Дата премьеры: полная дата — только когда есть все три части; одного
            // года/месяца недостаточно для «числа», но достаточно для «сезон + год».
            val start = media.optJSONObject("startDate")
            val premiereOn = start?.let {
                val y = it.optInt("year", 0); val m = it.optInt("month", 0); val d = it.optInt("day", 0)
                if (y > 0 && m > 0 && d > 0) runCatching { LocalDate.of(y, m, d) }.getOrNull() else null
            }
            val seasonYear = media.optInt("seasonYear", 0).takeIf { it > 0 } ?: start?.optInt("year", 0) ?: 0
            val seasonName = when (media.optString("season")) {
                "WINTER" -> "Зима"
                "SPRING" -> "Весна"
                "SUMMER" -> "Лето"
                "FALL" -> "Осень"
                else -> ""
            }
            val premiereSeason = when {
                seasonName.isNotBlank() && seasonYear > 0 -> "$seasonName $seasonYear"
                seasonYear > 0 -> seasonYear.toString()
                else -> ""
            }
            val trailer = media.optJSONObject("trailer")
            val youtube = trailer?.takeIf { it.optString("site").equals("youtube", ignoreCase = true) }
            return TitleSchedule(
                dates = dates,
                status = status,
                totalEpisodes = media.optInt("episodes", 0),
                nextEpisode = next?.optInt("episode", 0) ?: 0,
                nextAiringAt = next?.optLong("airingAt", 0L) ?: 0L,
                // Точное время премьеры — только из расписания первой серии.
                premiereAt = if (status == Status.UPCOMING) (dates[1] ?: next?.takeIf { it.optInt("episode", 0) == 1 }?.optLong("airingAt", 0L) ?: 0L) else 0L,
                premiereOn = premiereOn,
                premiereSeason = premiereSeason,
                trailerYoutubeId = youtube?.optString("id").orEmpty(),
                trailerThumbnail = youtube?.optString("thumbnail").orEmpty(),
            )
        }

        /** Видео Shikimori: [{kind:"pv", url:"https://youtube.com/watch?v=…", image_url:"…"}]. */
        fun parseShikimoriTrailer(body: String): Pair<String, String>? {
            val arr = org.json.JSONArray(body)
            var fallback: Pair<String, String>? = null
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val id = youtubeId(v.optString("url").ifBlank { v.optString("player_url") }) ?: continue
                val thumb = v.optString("image_url").let { if (it.startsWith("//")) "https:$it" else it }
                val pair = id to thumb
                if (v.optString("kind") == "pv") return pair
                if (fallback == null) fallback = pair
            }
            return fallback
        }

        /** id ролика из любой формы ссылки YouTube; null — это не YouTube. */
        fun youtubeId(url: String): String? {
            val m = Regex("""(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|embed/|v/))([A-Za-z0-9_-]{6,})""").find(url)
            return m?.groupValues?.get(1)
        }
    }
}

internal val BROADCAST_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

/** «released», но дата окончания не в прошлом — значит, ещё выходит. */
internal fun verifiedStatus(
    status: EpisodeAirDates.Status,
    releasedOn: LocalDate?,
    today: LocalDate,
): EpisodeAirDates.Status =
    if (status == EpisodeAirDates.Status.FINISHED && releasedOn != null && !releasedOn.isBefore(today)) {
        EpisodeAirDates.Status.AIRING
    } else {
        status
    }
private const val WEEK_SECONDS = 7L * 24L * 60L * 60L

/**
 * Per-episode air times, derived rather than listed — only AniList ever published
 * them one by one, and it is offline.
 *
 * Three cases, in order of how much the source actually knows:
 *  - the whole run dropped at once (`released_on == aired_on`) — one date for all,
 *    not a fictional weekly ladder;
 *  - the run is over and both ends are known — interpolate between them, so both
 *    ends are exact and mid-season breaks spread out. A plain weekly step from the
 *    premiere lands a full episode off on anything that paused for New Year (checked
 *    against «Синяя тюрьма»: 24 серии, 2022-10-09 → 2023-03-26, шаг промахивался);
 *  - still airing — anchor on `next_episode_at` (an exact timestamp) and step by a
 *    week in both directions.
 */
internal fun deriveDates(
    total: Int,
    aired: Int,
    nextEpisode: Int,
    nextAt: Long,
    firstAired: LocalDate?,
    releasedOn: LocalDate?,
): Map<Int, Long> {
    val last = maxOf(total, aired, nextEpisode)
    if (last <= 0) return emptyMap()
    if (firstAired != null && releasedOn != null && firstAired == releasedOn) {
        val at = firstAired.atStartOfDay(BROADCAST_ZONE).toEpochSecond()
        return (1..last).associateWith { at }
    }
    if (firstAired != null && releasedOn != null && last > 1 && releasedOn.isAfter(firstAired)) {
        val start = firstAired.atStartOfDay(BROADCAST_ZONE).toEpochSecond()
        val end = releasedOn.atStartOfDay(BROADCAST_ZONE).toEpochSecond()
        val spanWeeks = (end - start).toDouble() / WEEK_SECONDS
        return (1..last).associateWith { episode ->
            // Округляем до ЦЕЛЫХ недель, а не берём голую интерполяцию: аниме
            // выходит в один и тот же день недели, и «вторник» посреди воскресного
            // сериала выдал бы выдумку за факт. Финал прибит к известной дате.
            if (episode == last) {
                end
            } else {
                start + Math.round(spanWeeks * (episode - 1) / (last - 1)) * WEEK_SECONDS
            }
        }
    }
    val anchorEpisode: Int
    val anchorAt: Long
    when {
        nextAt > 0 && nextEpisode > 0 -> { anchorEpisode = nextEpisode; anchorAt = nextAt }
        firstAired != null -> { anchorEpisode = 1; anchorAt = firstAired.atStartOfDay(BROADCAST_ZONE).toEpochSecond() }
        else -> return emptyMap()
    }
    return (1..last).associateWith { episode ->
        anchorAt + (episode - anchorEpisode).toLong() * WEEK_SECONDS
    }
}

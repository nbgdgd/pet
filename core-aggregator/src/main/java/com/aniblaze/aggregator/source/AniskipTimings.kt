package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.OpeningRange
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
 * Exact opening/ending bounds for the player's skip buttons.
 *
 * The mobile player used to jump a blind +85 seconds from wherever playback was,
 * shown for the first two minutes regardless of the title — it skipped the wrong
 * thing about as often as the right one. AniSkip is the community timing database
 * every major player uses: keyed by MyAnimeList id, and Shikimori's anime ids ARE
 * MAL ids, so one title search there (it handles Russian names) gives us the key.
 *
 * Cached per (malId, episode) including misses — the buttons' visibility is polled
 * while playing and must never re-hit the network.
 */
@Singleton
class AniskipTimings @Inject constructor(
    private val http: HttpClient,
) {

    private val malIdCache = ConcurrentHashMap<String, Int>()
    private val timingCache = ConcurrentHashMap<String, SkipTimings>()
    /** Per-season fallback intervals, see [seasonRange]. Key: "malId#op" / "malId#ed". */
    private val seasonCache = ConcurrentHashMap<String, Optional>()

    /** ConcurrentHashMap can't hold nulls, and "probed, found nothing" must be cached. */
    private class Optional(val range: OpeningRange?)

    data class SkipTimings(val opening: OpeningRange? = null, val ending: OpeningRange? = null) {
        companion object { val EMPTY = SkipTimings() }
    }

    /**
     * [title] is the display (usually Russian) name; [altTitle] the original/romaji
     * one, tried when Shikimori's Russian naming differs from the catalog's.
     */
    suspend fun timings(title: String, episode: Int, altTitle: String = ""): SkipTimings = withContext(Dispatchers.IO) {
        if (title.isBlank() || episode <= 0) return@withContext SkipTimings.EMPTY
        val malId = malId(title, altTitle, episode) ?: return@withContext SkipTimings.EMPTY
        val key = "$malId#$episode"
        timingCache[key]?.let { return@withContext it }
        // Два НЕЗАВИСИМЫХ запроса вместо одного комбинированного: форма
        // `types=op&types=ed` у AniSkip нестабильна — на одном и том же тайтле
        // отвечает то 200, то 400, и тогда пропадал ЗАОДНО и опенинг.
        val opening = resolve(malId, episode, "op")
        val ending = resolve(malId, episode, "ed")
        val fetched = SkipTimings(opening.range, ending.range)
        // Кэшируем, только если хоть один запрос дал определённый ответ — иначе
        // разовый сбой сети заморозил бы «таймингов нет» до перезапуска.
        if (opening.definitive || ending.definitive) timingCache[key] = fetched
        fetched
    }

    /**
     * The episode's own entry, or the season's if AniSkip has never seen this episode.
     *
     * Покрытие AniSkip ДЫРЯВОЕ: у «Блю Лок против юношеской сборной Японии»
     * (mal 54865) заполнена ровно 1-я серия из 14, на остальных сервер отвечает
     * «found:false» — и кнопки пропуска не появлялось вообще. Опенинг внутри сезона
     * один и тот же, поэтому интервал соседней серии — не догадка, а тот же ролик.
     */
    private suspend fun resolve(malId: Int, episode: Int, type: String): Fetched {
        val exact = fetchOne(malId, episode, type)
        if (exact.range != null) return exact
        val season = seasonRange(malId, type, episode) ?: return exact
        return Fetched(season.copy(approximate = true), definitive = exact.definitive)
    }

    private suspend fun seasonRange(malId: Int, type: String, skip: Int): OpeningRange? {
        val key = "$malId#$type"
        seasonCache[key]?.let { return it.range }
        // Первая серия заполнена чаще всего; дальше — ближайшие соседи. Результат
        // (в т.ч. пустой) кэшируется на тайтл, так что следующая серия бесплатна.
        val probes = listOf(1, 2, skip - 1, skip + 1, 3)
            .distinct()
            .filter { it >= 1 && it != skip }
            .take(4)
        var found: OpeningRange? = null
        for (probe in probes) {
            val result = fetchOne(malId, probe, type)
            if (result.range != null) { found = result.range; break }
        }
        seasonCache[key] = Optional(found)
        return found
    }

    /**
     * Shikimori id == MAL id, so its search doubles as a MAL lookup.
     *
     * Кандидат ВЫБИРАЕТСЯ, а не берётся первый попавшийся: на «Синяя Тюрьма»
     * Shikimori первым отдаёт ФИЛЬМ «Blue Lock: Episode Nagi» (id 60076), у которого
     * в AniSkip ничего нет, тогда как сам сериал (id 49596, 4-й в выдаче) покрыт
     * полностью.
     */
    suspend fun malId(title: String, altTitle: String = "", episode: Int = 0): Int? {
        val cacheKey = "$title|$altTitle|${if (episode > 1) "s" else "1"}"
        malIdCache[cacheKey]?.let { return it }
        val candidates = LinkedHashMap<Int, JSONObject>()
        for (q in listOfNotNull(
            title.takeIf { it.isNotBlank() },
            altTitle.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
        )) {
            val query = java.net.URLEncoder.encode(q.take(80), "UTF-8")
            val body = http.getHtml("$SHIKIMORI_API/animes?search=$query&limit=8") ?: continue
            runCatching {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optInt("id", 0)
                    if (id > 0) candidates.putIfAbsent(id, o)
                }
            }
        }
        if (candidates.isEmpty()) return null
        val best = candidates.values.maxByOrNull { score(it, title, altTitle, episode) } ?: return null
        val id = best.optInt("id", 0).takeIf { it > 0 } ?: return null
        malIdCache[cacheKey] = id
        return id
    }

    /**
     * Оценка MyAnimeList и распределение голосов по тайтлу.
     *
     * Живёт здесь, а не в отдельном классе, потому что вся дорогая часть — поиск
     * id по названию — уже сделана и закэширована в [malId]. Id Shikimori И ЕСТЬ id
     * MAL, а `/animes/{id}` отдаёт и среднюю оценку, и `rates_scores_stats` —
     * сколько человек поставили каждый балл от 1 до 10.
     *
     * @return средняя, всего голосов, из них 4 и ниже. null — если тайтл не нашёлся.
     */
    suspend fun communityScore(title: String, altTitle: String = ""): Triple<Double, Int, Int>? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            scoreCache[title]?.let { return@withContext it.value }
            val id = malId(title, altTitle) ?: run {
                scoreCache[title] = ScoreMiss(null)
                return@withContext null
            }
            val body = http.getHtml("$SHIKIMORI_API/animes/$id")
            val result = runCatching {
                val o = JSONObject(body!!)
                val score = o.optString("score").toDoubleOrNull() ?: o.optDouble("score", 0.0)
                val stats = o.optJSONArray("rates_scores_stats")
                var total = 0
                var low = 0
                if (stats != null) {
                    for (i in 0 until stats.length()) {
                        val entry = stats.optJSONObject(i) ?: continue
                        val value = entry.optInt("value")
                        total += value
                        // «Низкой» считается оценка 4 и ниже: на десятибалльной шкале
                        // это уже «не понравилось», а не «средне».
                        if ((entry.optString("name").toIntOrNull() ?: 99) <= 4) low += value
                    }
                }
                Triple(score, total, low)
            }.getOrNull()
            scoreCache[title] = ScoreMiss(result)
            Timber.d(
                "[Shikimori] score title=%s mal=%d score=%s votes=%s",
                title.take(40), id, result?.first, result?.second,
            )
            result
        }

    /** ConcurrentHashMap не хранит null, а «искали и не нашли» кэшировать надо. */
    private class ScoreMiss(val value: Triple<Double, Int, Int>?)

    private val scoreCache = ConcurrentHashMap<String, ScoreMiss>()

    /** Насколько запись Shikimori похожа на то, что мы смотрим. */
    private fun score(o: JSONObject, title: String, altTitle: String, episode: Int): Int {
        var s = 0
        val name = norm(o.optString("name"))
        val russian = norm(o.optString("russian"))
        val wantRu = norm(title)
        val wantAlt = norm(altTitle)
        if (wantAlt.isNotBlank() && name == wantAlt) s += 100
        if (wantRu.isNotBlank() && russian == wantRu) s += 100
        if (wantAlt.isNotBlank() && name.isNotBlank() && (name.startsWith(wantAlt) || wantAlt.startsWith(name))) s += 25
        if (wantRu.isNotBlank() && russian.isNotBlank() && (russian.startsWith(wantRu) || wantRu.startsWith(russian))) s += 25
        // Серий должно ХВАТАТЬ: у фильма-спиноффа их 1–4, у сериала — десятки.
        val episodes = maxOf(o.optInt("episodes", 0), o.optInt("episodes_aired", 0))
        if (episode > 0 && episodes > 0) {
            if (episodes >= episode) s += 40 else s -= 60
        }
        if (episode > 1) {
            when (o.optString("kind")) {
                "tv", "ona" -> s += 20
                "movie", "special", "music" -> s -= 30
            }
        }
        return s
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** [definitive] = сервер ответил по существу (в т.ч. «нет данных»), а не отвалился. */
    private class Fetched(val range: OpeningRange?, val definitive: Boolean)

    private suspend fun fetchOne(malId: Int, episode: Int, type: String): Fetched {
        val response = http.getText("$ANISKIP_API/skip-times/$malId/$episode?types=$type&episodeLength=0")
            ?: return Fetched(null, definitive = false)
        // 404 у AniSkip — это ОТВЕТ («такой серии в базе нет»), а не сбой: тело
        // содержит `{"found":false}`. Раньше его глотал getHtml, и отрицательный
        // результат не кэшировался, поэтому пробы повторялись бесконечно.
        if (response.code == 404) return Fetched(null, definitive = true)
        val body = response.body?.takeIf { response.isSuccessful } ?: return Fetched(null, definitive = false)
        return runCatching {
            val root = JSONObject(body)
            if (!root.optBoolean("found")) return Fetched(null, definitive = true)
            val results = root.optJSONArray("results") ?: return Fetched(null, definitive = true)
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                if (!item.optString("skipType").equals(type, ignoreCase = true)) continue
                val interval = item.optJSONObject("interval") ?: continue
                val start = interval.optDouble("startTime", Double.NaN)
                val end = interval.optDouble("endTime", Double.NaN)
                if (start.isNaN() || end.isNaN()) continue
                val range = OpeningRange((start * 1000).toLong(), (end * 1000).toLong())
                if (range.isValid) return Fetched(range, definitive = true)
            }
            Fetched(null, definitive = true)
        }.onFailure { Timber.w(it, "[AniSkip] parse failed for mal=%d ep=%d type=%s", malId, episode, type) }
            .getOrDefault(Fetched(null, definitive = false))
    }

    private companion object {
        const val ANISKIP_API = "https://api.aniskip.com/v2"
        const val SHIKIMORI_API = "https://shikimori.one/api"
    }
}

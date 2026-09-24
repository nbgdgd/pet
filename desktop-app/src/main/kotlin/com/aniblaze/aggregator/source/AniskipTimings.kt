package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Opening ("пропустить опенинг") timings for titles AniLiberty doesn't cover.
 *
 * AniLiberty ships exact opening bounds, but its catalog is small — most playback
 * resolves through Anixart/Kodik, whose responses carry no timings at all, so the
 * skip button only appeared for a handful of titles.
 *
 * AniSkip is the community timing database every major player uses. It's keyed by
 * MyAnimeList id, and Shikimori's anime ids ARE MAL ids, so a title search on
 * Shikimori gives us the key. Results are cached per (malId, episode) — the button's
 * visibility is polled while playing and must never re-hit the network.
 */
class AniskipTimings(private val http: HttpClient, private val now: () -> Long = System::currentTimeMillis) {

    private val malIdCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val timingCache = java.util.concurrent.ConcurrentHashMap<String, SkipTimings>()
    private val rangeCache = java.util.concurrent.ConcurrentHashMap<String, Fetched>()
    private val transientUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Per-season fallback intervals, see [seasonRange]. Key: "malId#op" / "malId#ed". */
    private val seasonCache = java.util.concurrent.ConcurrentHashMap<String, Optional>()

    /** ConcurrentHashMap can't hold nulls, and "probed, found nothing" must be cached. */
    private class Optional(val range: OpeningRange?)

    /** Opening and ending bounds for one episode; either may be absent. */
    data class SkipTimings(val opening: OpeningRange? = null, val ending: OpeningRange? = null,
        val retryable: Boolean = false,
        /** Рекап («в прошлой серии») — у AniSkip тип `recap`; есть далеко не везде. */
        val recap: OpeningRange? = null) {
        companion object { val EMPTY = SkipTimings() }
    }

    /**
     * Cached per (malId, episode) including misses: the buttons' visibility is polled
     * while playing and must never re-hit the network.
     */
    suspend fun timings(title: String, episode: Int, altTitle: String = ""): SkipTimings = withContext(Dispatchers.IO) {
        if (title.isBlank() || episode <= 0) return@withContext SkipTimings.EMPTY
        // RU title first; when Shikimori's RU naming diverges from Anixart's, retry
        // with the original (romaji) title — that one always matches MAL.
        val malId = malId(title, altTitle, episode)
            ?: run {
                com.aniblaze.desktop.player.PlayerDiagnostics.log(
                    "aniskip.malId.miss", "title=${title.take(40)}; alt=${altTitle.take(40)}",
                )
                return@withContext SkipTimings.EMPTY
            }
        com.aniblaze.desktop.player.PlayerDiagnostics.log("aniskip.malId", "id=$malId; title=${title.take(40)}")
        val key = "$malId#$episode"
        timingCache[key]?.let { return@withContext it }
        // Два НЕЗАВИСИМЫХ запроса вместо одного комбинированного: форма
        // `types=op&types=ed` у AniSkip нестабильна — на одном и том же тайтле
        // отвечает то 200, то 400, и тогда пропадал ЗАОДНО и опенинг. Одиночный
        // `types=op` надёжен, а отсутствие эндинга (честный 404) больше не может
        // утащить опенинг за собой.
        suspend fun part(type: String): Fetched {
            val partKey = "$key#$type"
            rangeCache[partKey]?.let { return it }
            if (now() < (transientUntil[partKey] ?: 0L)) return Fetched(null, false)
            val fetched = resolve(malId, episode, type)
            if (fetched.definitive) {
                rangeCache[partKey] = fetched
                transientUntil.remove(partKey)
            } else transientUntil[partKey] = now() + 30_000
            return fetched
        }
        val opening = part("op")
        val ending = part("ed")
        // Рекап — третьим запросом и БЕЗ сезонного запасного варианта: пересказ прошлой
        // серии в каждой серии свой, и чужой интервал здесь был бы выдумкой.
        val recap = part("recap")
        val fetched = SkipTimings(opening.range, ending.range, !opening.definitive || !ending.definitive, recap = recap.range)
        // Кэшируем, только если хоть один запрос дал определённый ответ. Иначе
        // разовый сбой сети заморозил бы «таймингов нет» до перезапуска.
        if (opening.definitive && ending.definitive) timingCache[key] = fetched
        fetched
    }

    suspend fun opening(title: String, episode: Int, altTitle: String = ""): OpeningRange? =
        timings(title, episode, altTitle).opening

    /**
     * The episode's own entry, or the season's if AniSkip has never seen this episode.
     *
     * Покрытие AniSkip ДЫРЯВОЕ, и это была главная причина «у большинства аниме нет
     * кнопки»: у «Блю Лок против юношеской сборной Японии» (mal 54865) заполнена
     * ровно 1-я серия из 14 — на всех остальных сервер отвечает «found:false», и
     * кнопка не появлялась ни разу за вечер. Опенинг внутри сезона один и тот же,
     * поэтому чужая серия — не догадка, а тот же самый ролик.
     */
    private suspend fun resolve(malId: Int, episode: Int, type: String): Fetched {
        val exact = fetchOne(malId, episode, type)
        if (exact.range != null || type == "recap") return exact
        val season = seasonRange(malId, type, episode) ?: return exact
        com.aniblaze.desktop.player.PlayerDiagnostics.log(
            "aniskip.seasonFallback", "mal=$malId; ep=$episode; type=$type; ${season.startMs}-${season.endMs}",
        )
        // approximate: интервал взят у соседней серии, поэтому кнопку показываем
        // с более широким окном — холодное открытие сдвигает опенинг на секунды.
        return Fetched(season.copy(approximate = true), definitive = exact.definitive)
    }

    private suspend fun seasonRange(malId: Int, type: String, skip: Int): OpeningRange? {
        val key = "$malId#$type"
        seasonCache[key]?.let { return it.range }
        // Опрашиваются ВСЕ пробы, а не до первой удачи: выбор делается из того, что
        // нашлось (см. [pickSeasonRange]). Результат, в том числе пустой, кэшируется
        // на тайтл, поэтому следующая серия не стоит ни одного запроса.
        val responses = seasonProbeOrder(skip, SEASON_PROBES).map { probe -> fetchOne(malId, probe, type) }
        val found = responses.mapNotNull { it.range }
        val picked = pickSeasonRange(found)
        if (picked != null || responses.all { it.definitive }) seasonCache[key] = Optional(picked)
        return picked
    }

    /**
     * Shikimori id == MAL id, so its search doubles as a MAL lookup. Shared with
     * [EpisodeAirDates] so a title is resolved (and cached) once for both.
     *
     * Кандидат ВЫБИРАЕТСЯ, а не берётся первый попавшийся. Слепое `limit=1` было
     * причиной промахов: на «Синяя Тюрьма» Shikimori первым отдаёт ФИЛЬМ «Blue Lock:
     * Episode Nagi — Additional Time!» (id 60076), у которого в AniSkip ничего нет,
     * тогда как сам сериал (id 49596, 4-й в выдаче) покрыт полностью. Тот же промах
     * портил даты серий и статус «завершён».
     *
     * [episode] и [altTitle] — дополнительные сигналы: у записи должно хватать серий,
     * а оригинальное (ромадзи) имя обычно совпадает точно.
     */
    suspend fun malId(title: String, altTitle: String = "", episode: Int = 0): Int? {
        val cacheKey = "$title|$altTitle|${if (episode > 1) "s" else "1"}"
        malIdCache[cacheKey]?.let { return it }
        // Ищем по обоим названиям: русское Shikimori понимает, но ромадзи точнее.
        val candidates = LinkedHashMap<Int, JSONObject>()
        for (q in listOfNotNull(title.takeIf { it.isNotBlank() }, altTitle.takeIf { it.isNotBlank() && !it.equals(title, true) })) {
            val body = http.getHtml("$SHIKIMORI_API/animes?search=${java.net.URLEncoder.encode(q.take(80), "UTF-8")}&limit=8")
                ?: continue
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
        // СНАЧАЛА отсев непохожих, и только потом выбор лучшего.
        //
        // Здесь стоял голый maxByOrNull, и он возвращал верхнего кандидата ВСЕГДА —
        // даже когда ни один не имеет отношения к запросу. Поиск Shikimori нечёткий и
        // на любую строку что-нибудь да отдаёт: проверено тестом, на бессмысленное
        // «Ъфыва несуществующее аниме 12345» приходила настоящая карточка с оценкой
        // 6.38 и 1382 голосами. То есть каждый тайтл, которого на Shikimori нет под
        // нашим названием, получал ЧУЖУЮ оценку — и она шла в «Шлакометр» как своя.
        //
        // Тем же путём ходят тайминги опенинга, так что мимо цели улетал и пропуск.
        val plausible = candidates.values.filter { resembles(it, title, altTitle) }
        if (plausible.isEmpty()) {
            com.aniblaze.desktop.player.PlayerDiagnostics.log(
                "shikimori.noMatch",
                "title=${title.take(40)}; alt=${altTitle.take(40)}; candidates=${candidates.size}",
            )
            return null
        }
        val best = plausible.maxByOrNull { score(it, title, altTitle, episode) } ?: return null
        val id = best.optInt("id", 0).takeIf { it > 0 } ?: return null
        malIdCache[cacheKey] = id
        return id
    }

    /**
     * Похож ли найденный тайтл на то, что искали.
     *
     * Лучше НЕ ВЫНЕСТИ вердикт, чем вынести чужой: пустая ячейка честна, а
     * подставленная оценка постороннего аниме врёт и в «Шлакометре», и в пропуске
     * опенинга. Достаточно точного совпадения, вхождения одного названия в другое
     * или половины значимых слов запроса — этого хватает на «Клинок, рассекающий
     * демонов: Деревня кузнецов» против «Клинок, рассекающий демонов» и отсекает
     * случайную выдачу на мусор.
     */
    private fun resembles(o: JSONObject, title: String, altTitle: String): Boolean {
        val names = listOf(norm(o.optString("name")), norm(o.optString("russian"))).filter { it.isNotBlank() }
        val wants = listOf(norm(title), norm(altTitle)).filter { it.isNotBlank() }
        if (names.isEmpty() || wants.isEmpty()) return false
        for (want in wants) {
            val wantTokens = want.split(' ').filter { it.length >= MATCH_MIN_TOKEN }.toSet()
            for (name in names) {
                if (name == want || name.startsWith(want) || want.startsWith(name)) return true
                if (wantTokens.isEmpty()) continue
                val nameTokens = name.split(' ').filter { it.length >= MATCH_MIN_TOKEN }.toSet()
                if (nameTokens.isEmpty()) continue
                // Половина ЗАПРОШЕННЫХ слов, а не половина меньшего из двух списков.
                // Со «меньшим» одно случайное общее слово вроде «аниме» проходило,
                // когда у найденного тайтла название из двух слов, — на этом и
                // ловился мусорный запрос в тесте.
                val common = wantTokens.count { it in nameTokens }
                if (common > 0 && common * 2 >= wantTokens.size) return true
            }
        }
        return false
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
            com.aniblaze.desktop.player.PlayerDiagnostics.log(
                "shikimori.score",
                "title=${title.take(40)}; mal=$id; score=${result?.first}; votes=${result?.second}",
            )
            result
        }

    /** ConcurrentHashMap не хранит null, а «искали и не нашли» кэшировать надо. */
    private class ScoreMiss(val value: Triple<Double, Int, Int>?)

    private val scoreCache = java.util.concurrent.ConcurrentHashMap<String, ScoreMiss>()

    /** Насколько запись Shikimori похожа на то, что мы смотрим. */
    private fun score(o: JSONObject, title: String, altTitle: String, episode: Int): Int {
        var s = 0
        val name = norm(o.optString("name"))
        val russian = norm(o.optString("russian"))
        val wantRu = norm(title)
        val wantAlt = norm(altTitle)
        // Точное совпадение имени — самый сильный сигнал (ромадзи совпадает буквально).
        if (wantAlt.isNotBlank() && name == wantAlt) s += 100
        if (wantRu.isNotBlank() && russian == wantRu) s += 100
        if (wantAlt.isNotBlank() && name.isNotBlank() && (name.startsWith(wantAlt) || wantAlt.startsWith(name))) s += 25
        if (wantRu.isNotBlank() && russian.isNotBlank() && (russian.startsWith(wantRu) || wantRu.startsWith(russian))) s += 25
        // Серий должно ХВАТАТЬ: у фильма-спиноффа их 1–4, у сериала — десятки.
        val episodes = maxOf(o.optInt("episodes", 0), o.optInt("episodes_aired", 0))
        if (episode > 0 && episodes > 0) {
            if (episodes >= episode) s += 40 else s -= 60
        }
        // Многосерийное смотрим — значит это сериал, а не фильм/спешл.
        if (episode > 1) {
            when (o.optString("kind")) {
                "tv", "ona" -> s += 20
                "movie", "special", "music" -> s -= 30
            }
        }
        return s
    }

    /** Короче этого слово ничего не различает («ты», «на», «the»). */
    private val MATCH_MIN_TOKEN = 3

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** [definitive] = сервер ответил по существу (в т.ч. «нет данных»), а не отвалился. */
    private class Fetched(val range: OpeningRange?, val definitive: Boolean)

    private suspend fun fetchOne(malId: Int, episode: Int, type: String): Fetched {
        val response = http.getText("$ANISKIP_API/skip-times/$malId/$episode?types=$type&episodeLength=0")
            ?: run {
                com.aniblaze.desktop.player.PlayerDiagnostics.log(
                    "aniskip.fetch.noBody", "mal=$malId; ep=$episode; type=$type",
                )
                return Fetched(null, definitive = false)
            }
        // 404 у AniSkip — это ОТВЕТ («такой серии в базе нет»), а не сбой: тело
        // содержит `{"found":false}`. Раньше его глотал getHtml, и отрицательный
        // результат не кэшировался, поэтому пробы повторялись бесконечно.
        if (response.code == 404) return Fetched(null, definitive = true)
        val body = response.body?.takeIf { response.isSuccessful } ?: run {
            com.aniblaze.desktop.player.PlayerDiagnostics.log(
                "aniskip.fetch.badStatus", "mal=$malId; ep=$episode; type=$type; code=${response.code}",
            )
            return Fetched(null, definitive = false)
        }
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

/**
 * Сколько серий опрашивается в поисках донора.
 *
 * Восемь, а не четыре. ЗАМЕРЕНО живым запросом по «Чёрной кошке и классу ведьм»
 * (mal 62171): из двенадцати серий заполнены ТРИ — первая, вторая и восьмая. На
 * двенадцатой серии ближние соседи (10, 11, 13, 14) пусты все до одного, и с
 * коротким списком проб единственной находкой оказывалась первая серия — то есть
 * ровно тот интервал, от которого мы и уходим. До восьмой серии список обязан
 * дотянуться.
 *
 * Восемь запросов — это один раз на тайтл: результат, включая пустой, кэшируется в
 * seasonCache, и следующие серии не стоят ничего.
 */
internal const val SEASON_PROBES = 8

/**
 * У кого спрашивать интервал для серии, которой нет в базе, и в каком порядке.
 *
 * СОСЕДИ ВПЕРЁД, ПЕРВАЯ СЕРИЯ — ПОСЛЕДНЕЙ. Раньше порядок был обратный, и это была
 * прямая причина того, что «Пропустить опенинг» промахивался.
 *
 * ЗАМЕРЕНО живым запросом к AniSkip по «Чёрной кошке и классу ведьм» (mal 62171):
 *
 *     серия 1: 2.1 – 92.1        ← начинает опенингом сразу
 *     серия 2: 42.5 – 131.5
 *     серия 8: 29.3 – 119.2
 *     серии 3–7, 9–12: данных нет
 *
 * Первая серия почти всегда идёт без холодного открытия — опенинг там стоит в самом
 * начале. У всех остальных перед ним есть сцена, и она сдвигает ролик на полминуты.
 * Пока пробы начинались с первой серии, каждая незаполненная серия получала её
 * интервал, и кнопка уводила на 92-ю секунду, где опенинг только начинался.
 *
 * Первая серия из проб не убрана: у тайтлов, где заполнена ТОЛЬКО она, другого
 * донора не существует, и её кривой интервал всё равно лучше, чем ничего.
 */
internal fun seasonProbeOrder(episode: Int, limit: Int): List<Int> {
    // Место под первую серию РЕЗЕРВИРУЕТСЯ заранее. Без этого соседи занимали все
    // слоты, и у тайтла, где заполнена только первая серия, донора не оставалось
    // вовсе — кнопка пропала бы совсем, а это хуже неточной.
    val wantsFirst = episode != 1 && limit > 0
    val neighbourSlots = if (wantsFirst) limit - 1 else limit
    val order = ArrayList<Int>(limit)
    var step = 1
    // Сначала ближайшие соседи в обе стороны: у них та же структура серии.
    while (order.size < neighbourSlots && step <= MAX_NEIGHBOUR_STEP) {
        for (candidate in intArrayOf(episode - step, episode + step)) {
            if (order.size >= neighbourSlots) break
            if (candidate >= 1 && candidate != episode && candidate != 1) order += candidate
        }
        step++
    }
    // И только потом — первая серия, как заведомо худший, но иногда единственный донор.
    if (wantsFirst) order += 1
    return order.take(limit)
}

/**
 * Какой из найденных интервалов занять.
 *
 * СЕРЕДИННЫЙ ПО СТАРТУ, а не первый попавшийся. Медиана устойчива к выбросу, а
 * выброс здесь ровно один и предсказуемый — первая серия. На замеренном наборе
 * (2.1, 29.3, 42.5) первый попавшийся дал бы 2.1, медиана даёт 29.3.
 *
 * Из чётного числа доноров берётся ПОЗДНИЙ: занять слишком ранний интервал хуже, чем
 * слишком поздний. Ранний уводит в середину опенинга, и он продолжает играть — то
 * есть кнопка просто не сработала. Поздний в худшем случае перепрыгнет несколько
 * секунд серии, и это заметно меньшее зло.
 */
internal fun pickSeasonRange(found: List<OpeningRange>): OpeningRange? {
    if (found.isEmpty()) return null
    val sorted = found.sortedBy { it.startMs }
    return sorted[sorted.size / 2]
}

/**
 * Насколько далеко от серии ищется сосед-донор.
 *
 * Восемь: у замеренного тайтла до ближайшей заполненной серии от двенадцатой ровно
 * четыре шага, и запас нужен на случай, когда дыра ещё шире.
 */
private const val MAX_NEIGHBOUR_STEP = 8

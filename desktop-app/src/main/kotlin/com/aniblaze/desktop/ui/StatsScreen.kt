package com.aniblaze.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.desktop.player.PlayerDiagnostics
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * «Статистика» — что накопилось за всё время просмотра.
 *
 * Считается из того, что и так хранится: отметки просмотренных серий, точки
 * возобновления, история и накопленное время. Ничего не выдумывается — если жанры
 * у старых записей истории не сохранялись, экран один раз дозаполняет их у
 * источника (см. [AppSettings.enrichHistoryMeta]) и больше к сети не ходит.
 */
/**
 * Что уже проиграно за этот запуск.
 *
 * Экран пересоздаётся при каждом уходе на другую вкладку и возврате, поэтому
 * состояние входа обязано жить СНАРУЖИ композиции — иначе анимации набора и роста
 * заново отыгрываются при каждом заходе, хотя цифры те же самые.
 */
private object StatsSession {
    var introPlayed = false
}

/** Сколько тайтлов дозаполнять за один заход, чтобы не отбирать сеть у просмотра. */
private const val ENRICH_BATCH = 12

/** Пауза между запросами внутри порции. */
private const val ENRICH_GAP_MS = 400L

/**
 * Записи, которым не хватает данных для статистики.
 *
 * Кино (`tmdb…`) сюда не попадает: у него нет ни жанров Anixart, ни оценки MAL, и
 * гонять за ними сеть означало бы вечно «дозаполнять» то, чего не существует.
 */
private fun needsMeta(anime: com.aniblaze.desktop.PersistedAnime): Boolean =
    !anime.id.startsWith("tmdb") && (anime.genres.isBlank() || anime.malVotes == 0)

internal fun statsMetadataBatch(state: com.aniblaze.desktop.PersistedState, attempted: Set<String>) =
    (state.history + state.favorites).distinctBy { it.id }
        .filter { needsMeta(it) && it.id !in attempted }.take(ENRICH_BATCH)

@Composable
fun StatsScreen(
    settings: AppSettings,
    repository: DesktopRepository,
    playbackActive: Boolean = false,
    onOpenTitle: (Anime) -> Unit,
) {
    val state by settings.state.collectAsState()
    // Первый заход показывает вход; дальше цифры просто стоят на месте.
    val intro = remember { !StatsSession.introPlayed }
    LaunchedEffect(Unit) { StatsSession.introPlayed = true }

    // Дозаполнение жанров у записей, сделанных до появления этого экрана.
    //
    // Раньше это были сорок запросов подряд, без пауз, сразу при открытии — и
    // они отбирали сеть у играющего потока: он захлёбывался, срабатывал сторож
    // зависания и перезапускал воспроизведение. Теперь порция мельче, между
    // запросами есть пауза, а пока что-то играет, дозаполнение не начинается
    // вовсе: статистика подождёт, просмотр — нет.
    // Ключ — playbackActive, и это ГЛАВНОЕ в этом эффекте.
    //
    // Здесь стоял LaunchedEffect(Unit): он срабатывал ОДИН раз за заход на экран, и
    // если в этот момент что-то играло — просто выходил и не возвращался никогда.
    // А playbackActive это «есть вкладка плеера», а не «идёт звук», так что у
    // владельца он был поднят практически всегда. ЗАМЕРЕНО по журналу: две записи
    // `stats.enrich.postponed | reason=playbackActive` и НИ ОДНОЙ `enrich.start`, а
    // в сохранённом состоянии оценка MAL заполнена у 12 тайтлов из 46. Отсюда и
    // «Шлакометр не работает»: судить было не по чему, а карточка при нуле судимых
    // просто не рисовалась — молчаливый отказ.
    //
    // Теперь смена playbackActive перезапускает эффект: закрыл плеер — дозаполнение
    // само пошло. И оно идёт ПОРЦИЯМИ ДО КОНЦА, а не одной пачкой из двенадцати.
    var enrichPass by remember { mutableStateOf(0) }
    var enrichFailed by remember { mutableStateOf(false) }
    val attemptedMeta = remember { mutableSetOf<String>() }
    // Сколько записей ещё без оценок — это же число показывается в карточке.
    val pendingMeta = remember(state) { (state.history + state.favorites).distinctBy { it.id }.count(::needsMeta) }
    val metadataTargets = (state.history + state.favorites).distinctBy { it.id }.filter(::needsMeta).map { it.id }
    LaunchedEffect(playbackActive, enrichPass, metadataTargets) {
        if (playbackActive) {
            PlayerDiagnostics.log("stats.enrich.postponed", "reason=playbackActive")
            return@LaunchedEffect
        }
        while (true) {
            val missing = statsMetadataBatch(settings.state.value, attemptedMeta)
            if (missing.isEmpty()) {
                enrichFailed = (settings.state.value.history + settings.state.value.favorites).any(::needsMeta)
                PlayerDiagnostics.log("stats.enrich.complete", "nothingLeft")
                return@LaunchedEffect
            }
            PlayerDiagnostics.log("stats.enrich.start", "titles=${missing.size}")
            val meta = mutableMapOf<String, Anime>()
            var failures = 0
            for (item in missing) {
                val details = try {
                    repository.fullDetails(item.toAnimeLite())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    PlayerDiagnostics.failure("stats.enrich.item", error)
                    null
                }
                attemptedMeta += item.id
                if (details != null) meta[item.id] = details else failures++
                kotlinx.coroutines.delay(ENRICH_GAP_MS)
            }
            PlayerDiagnostics.log("stats.enrich.done", "filled=${meta.size}; failed=$failures")
            settings.enrichHistoryMeta(meta)
            // Ни одного успеха за целую порцию — источник недоступен. Крутить дальше
            // бессмысленно: остановимся и дадим кнопку «Повторить», чтобы это была
            // видимая ошибка, а не бесконечная тихая долбёжка сети.
            if (meta.isEmpty()) {
                enrichFailed = true
                PlayerDiagnostics.log("stats.enrich.stalled", "batch failed entirely")
            }
        }
    }

    // Refresh date-dependent streaks even when the screen stays open overnight.
    var statsDay by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(60_000); statsDay = LocalDate.now() }
    }
    // Metadata, episode counts and values can change without changing list sizes.
    val stats = remember(state, statsDay) { buildStats(state, statsDay) }

    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop(intro)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column(Modifier.padding(top = 20.dp)) {
                    Text("Статистика", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (stats.titles == 0 && stats.hours == 0) {
                            "Пока пусто — посмотри пару серий, и здесь появятся цифры."
                        } else {
                            "Всё, что накопилось за ${stats.daysActive} ${plural(stats.daysActive, "день", "дня", "дней")} у экрана."
                        },
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                    CountCard("Тайтлов", stats.titles, "есть просмотренная серия", Modifier.weight(1f), 0, intro)
                    CountCard("Досмотрено", stats.finishedTitles, "тайтлов до конца", Modifier.weight(1f), 90, intro)
                    CountCard("Серий", stats.episodes, "просмотрено", Modifier.weight(1f), 180, intro)
                    CountCard("Часов", stats.hours, "у экрана", Modifier.weight(1f), 270, intro)
                }
            }

            if (stats.streak > 0 || stats.longestDayMinutes > 0) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                        CountCard("Дней подряд", stats.streak, "серия не рвётся", Modifier.weight(1f), 0, intro)
                        CountCard("Рекорд за день", stats.longestDayMinutes, "минут", Modifier.weight(1f), 90, intro)
                        CountCard("В среднем", stats.averageMinutesPerDay, "минут в день", Modifier.weight(1f), 180, intro)
                        CountCard("Рекорд отметок", stats.busiestDayEpisodes, "серий за день", Modifier.weight(1f), 270, intro)
                    }
                }
            }

            if (stats.genres.isNotEmpty()) {
                item {
                    StatsCard("Любимые жанры") {
                        stats.genres.forEachIndexed { index, (name, count) ->
                            BarRow(
                                label = name,
                                value = count,
                                fraction = count.toFloat() / stats.genres.first().second,
                                delayMs = index * 70,
                                animate = intro,
                                suffix = plural(count, "тайтл", "тайтла", "тайтлов"),
                            )
                        }
                    }
                }
            }

            // Карточка есть ВСЕГДА, даже когда судить пока не по чему.
            //
            // Раньше при нуле судимых её просто не рисовали, и отказ выглядел как
            // «функции нет». Теперь видно, что происходит: сколько тайтлов ещё без
            // оценок, почему они не набираются и что нажать.
            item {
                StatsCard("Шлакометр") {
                    when {
                        stats.trashJudged > 0 -> TrashMeter(stats)
                        else -> TrashMeterPending(
                            pending = pendingMeta,
                            playbackActive = playbackActive,
                            failed = enrichFailed,
                            onRetry = { enrichFailed = false; attemptedMeta.clear(); enrichPass++ },
                        )
                    }
                }
            }

            if (stats.hourly.any { it > 0 }) {
                item {
                    StatsCard("Когда смотришь") {
                        HourHistogram(stats.hourly, intro)
                        Text(
                            "Пик в ${stats.peakHour}:00 — ${stats.hourly[stats.peakHour]} мин. Учёт по часам — с этой версии.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }

            if (stats.topTitles.isNotEmpty()) {
                item {
                    StatsCard("Больше всего серий") {
                        val strip = androidx.compose.foundation.lazy.rememberLazyListState()
                        LazyRow(
                            state = strip,
                            modifier = Modifier.dragScroll(strip),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(stats.topTitles) { (anime, count) ->
                                TopTitleCard(anime, count) { onOpenTitle(anime.toAnimeLite()) }
                            }
                        }
                    }
                }
            }

            if (stats.studios.isNotEmpty()) {
                item {
                    StatsCard("Студии") {
                        stats.studios.forEachIndexed { index, (name, count) ->
                            BarRow(
                                label = name,
                                value = count,
                                fraction = count.toFloat() / stats.studios.first().second,
                                delayMs = index * 70,
                                animate = intro,
                                suffix = plural(count, "тайтл", "тайтла", "тайтлов"),
                            )
                        }
                    }
                }
            }

            if (stats.decades.isNotEmpty()) {
                item {
                    StatsCard("По годам выпуска") {
                        stats.decades.forEachIndexed { index, (year, count) ->
                            BarRow(
                                label = year,
                                value = count,
                                fraction = count.toFloat() / stats.decades.maxOf { it.second },
                                delayMs = index * 60,
                                animate = intro,
                                suffix = plural(count, "тайтл", "тайтла", "тайтлов"),
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// --- расчёт ---

internal data class Stats(
    val titles: Int,
    val finishedTitles: Int,
    val episodes: Int,
    val hours: Int,
    val streak: Int,
    val daysActive: Int,
    val longestDayMinutes: Int,
    val averageMinutesPerDay: Int,
    val busiestDayEpisodes: Int,
    val peakHour: Int,
    val hourly: List<Int>,
    val genres: List<Pair<String, Int>>,
    val studios: List<Pair<String, Int>>,
    val decades: List<Pair<String, Int>>,
    val topTitles: List<Pair<com.aniblaze.desktop.PersistedAnime, Int>>,
    /** Сколько просмотренных тайтлов собрано из штампов исекая (см. [TrashAnime]). */
    val trashTitles: Int,
    /** Сколько тайтлов вообще участвовало в подсчёте (у остальных мало голосов). */
    val trashJudged: Int,
    val trashPercent: Int,
    /** Средняя оценка MAL просмотренного — сравнивается с медианой каталога. */
    val trashAverageScore: Double,
    val trashTop: List<com.aniblaze.desktop.PersistedAnime>,
    val bestTop: List<com.aniblaze.desktop.PersistedAnime> = emptyList(),
    val normalTop: List<com.aniblaze.desktop.PersistedAnime> = emptyList(),
    val bestCount: Int = 0,
    val normalCount: Int = 0,
    val personalRatings: Map<String, Int> = emptyMap(),
)

internal fun buildStats(state: com.aniblaze.desktop.PersistedState, today: LocalDate = LocalDate.now()): Stats {
    // "contentId#segment" → сколько серий у каждого тайтла.
    val perTitle = state.watched.filter { it.substringAfterLast('#', "").toIntOrNull()?.let { n -> n > 0 } == true }
        .groupingBy { it.substringBeforeLast('#') }.eachCount()
    val known = (state.history + state.favorites + state.progress.map { it.anime })
        .distinctBy { it.id }
        .associateBy { it.id }

    val episodes = perTitle.values.sum()
    // Do not present guessed episode lengths (including manual marks) as screen time.
    val hours = (state.watchedMs.coerceAtLeast(0L) / 3_600_000L).toInt()

    val dayTimes = state.watchedByDay.filter { (day, value) ->
        value > 0 && runCatching { LocalDate.parse(day) <= today }.getOrDefault(false)
    }
    val days = dayTimes.keys.map(LocalDate::parse).toSet()
    var streak = 0
    if (days.isNotEmpty()) {
            val last = days.max()
            var cursor = if (last == today || last == today.minusDays(1)) last else null
        while (cursor != null && days.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }
    }
    val longestDayMs = dayTimes.values.maxOrNull() ?: 0L
    val averageDayMs = if (dayTimes.isEmpty()) 0L else dayTimes.values.average().toLong()

    // Hourly totals are measured watch time, not timestamps of resume entries.
    val hourly = List(24) { hour -> ((state.watchedByHour[hour] ?: 0L).coerceAtLeast(0L) / 60_000).toInt() }
    val completionsByDay = state.watchedAt.filter { (key, at) -> key in state.watched && at > 0 }
        .values.map { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        .filter { it <= today }.groupingBy { it }.eachCount()

    fun topOf(select: (com.aniblaze.desktop.PersistedAnime) -> List<String>): List<Pair<String, Int>> =
        perTitle.keys.mapNotNull { known[it] }
            .flatMap(select)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .map { it.key to it.value }

    val genres = topOf { it.genres.split(',', '·', '/').map(String::trim) }
    val studios = topOf { listOf(it.studio) }.take(6)
    val decades = perTitle.keys.mapNotNull { known[it] }
        .map { it.year }
        .filter { it in 1960..2100 }
        .groupingBy { it.toString() }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .map { it.key to it.value }

    val topTitles = perTitle.entries
        .sortedByDescending { it.value }
        .mapNotNull { (id, count) -> known[id]?.let { it to count } }
        .take(12)

    // Судим ТОЛЬКО по тайтлам, у которых набралось достаточно голосов: у свежего
    // онгоинга с десятком оценок одна злая ставит 20% «низких» на ровном месте.
    val personalRatings = state.ratings.associate { it.anime.id to it.score }
    // A launch or a few minutes are not a viewed title. Only a completed episode,
    // an explicit whole-title mark, or a personal rating may feed the meter.
    val viewedIds = perTitle.keys + state.completedTitles
    val viewed = (known.values.filter { it.id in viewedIds } + state.ratings.map { it.anime }).distinctBy { it.id }
    fun category(a: com.aniblaze.desktop.PersistedAnime) = TrashAnime.category(personalRatings[a.id] ?: 0, a.malScore, a.malLowVotes, a.malVotes)
    val judged = viewed.filter { category(it) != null }
    val trash = judged.filter { category(it) == TrashAnime.Category.POOR }
    val best = judged.filter { category(it) == TrashAnime.Category.BEST }
    val normal = judged.filter { category(it) == TrashAnime.Category.NORMAL }
    val trashPercent = if (judged.isEmpty()) 0 else trash.size * 100 / judged.size
    val averageScore = judged.map { it.malScore }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() ?: 0.0

    return Stats(
        titles = perTitle.keys.size,
        // Count from episode marks + known totals, not from the capped card-history
        // list: a large library can legitimately contain more than 100 old titles.
        finishedTitles = com.aniblaze.desktop.buildWatchIndex(state).count { (id, watch) ->
            watch.finished && known[id]?.airingStatus !in setOf(2, 3)
        },
        episodes = episodes,
        hours = hours,
        streak = streak,
        daysActive = dayTimes.size,
        longestDayMinutes = (longestDayMs / 60_000L).toInt(),
        averageMinutesPerDay = (averageDayMs / 60_000L).toInt(),
        busiestDayEpisodes = completionsByDay.values.maxOrNull() ?: 0,
        peakHour = hourly.indices.maxByOrNull { hourly[it] } ?: 0,
        hourly = hourly,
        genres = genres,
        studios = studios,
        decades = decades,
        topTitles = topTitles,
        trashTitles = trash.size,
        trashJudged = judged.size,
        trashPercent = trashPercent,
        trashAverageScore = averageScore,
        trashTop = trash.sortedByDescending { TrashAnime.severity(it.malScore, it.malLowVotes, it.malVotes) }.take(8),
        bestTop = best.sortedWith(compareByDescending<com.aniblaze.desktop.PersistedAnime> { personalRatings[it.id] ?: 0 }.thenByDescending { it.malScore }).take(8),
        normalTop = normal.sortedByDescending { it.malScore }.take(8),
        bestCount = best.size, normalCount = normal.size, personalRatings = personalRatings,
    )
}

/**
 * Шкала «шлачности» с приговором и списком главных обвиняемых.
 *
 * Шкала намеренно не красная: это шутка про вкус, а не претензия. Заполнение растёт
 * от оранжевого к малиновому — чем дальше, тем «горячее».
 */
/**
 * Размер, который растёт вместе с настройкой «масштаб интерфейса».
 *
 * Настройка множит `Density.fontScale`, поэтому сам собой масштабируется только
 * текст (sp), а размеры в dp остаются прежними. Плитки и отступы, заданные в dp,
 * из-за этого не поспевали за подросшим текстом: название переносилось на три
 * строки, а строка «MAL 6,27 · 22% низких» рвалась пополам. Здесь dp домножается
 * на тот же коэффициент — плитка растёт ровно настолько же, насколько буквы.
 */
@Composable
private fun scaled(base: Dp): Dp = base * androidx.compose.ui.platform.LocalDensity.current.fontScale

/**
 * Что показывает Шлакометр, пока судить не по чему.
 *
 * Вместо исчезнувшей карточки — прямая речь: сколько тайтлов ещё без оценок, почему
 * они сейчас не набираются и что с этим сделать. Оценки приходят не из каталога, а
 * из Shikimori отдельным обходом, и обход намеренно уступает дорогу воспроизведению.
 */
@Composable
private fun TrashMeterPending(
    pending: Int,
    playbackActive: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    val message = when {
        failed -> "Не удалось получить оценки — источник не ответил."
        pending == 0 -> "Пока нечего судить: у просмотренного нет оценок на MyAnimeList."
        playbackActive -> "Собираю оценки для $pending ${plural(pending, "тайтла", "тайтлов", "тайтлов")}. " +
            "Пока идёт просмотр, обход ждёт: сеть нужнее потоку. Закрой плеер — продолжится сам."
        else -> "Собираю оценки для $pending ${plural(pending, "тайтла", "тайтлов", "тайтлов")}…"
    }
    Text(message, color = TextSecondary, fontSize = 14.sp)
    if (failed || (pending > 0 && !playbackActive)) {
        Box(
            Modifier.padding(top = 10.dp).clip(Shapes.chip)
                .background(AccentOrange.copy(alpha = 0.18f))
                .clickable(onClick = onRetry)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("Повторить", color = AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TrashMeter(stats: Stats) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "${stats.trashPercent}",
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            color = AccentOrange,
        )
        Text(
            "%",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = AccentOrange,
            modifier = Modifier.padding(bottom = 5.dp),
        )
        Text(
            TrashAnime.verdict(stats.trashPercent),
            color = TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
    }
    Text(
        "${stats.trashTitles} из ${stats.trashJudged} " +
            plural(stats.trashJudged, "тайтла", "тайтлов", "тайтлов") +
            " — слабые. Ваша оценка важнее MAL: 4–5 — лучшие, 3 — нормальные, 1–2 — слабые. " +
            "Без вашей оценки: MAL от 8 — лучшие; слабые — ниже 6.38 или от 15% низких голосов; остальные — нормальные.",
        color = TextSecondary,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
    )
    if (stats.trashAverageScore > 0.0) {
        Text(
            "Средний рейтинг просмотренного на MAL: %.2f — %s (медиана %.2f).".format(
                stats.trashAverageScore,
                TrashAnime.tasteVerdict(stats.trashAverageScore),
                TrashAnime.CATALOG_MEDIAN,
            ),
            color = TextPrimary,
            fontSize = 17.sp,
            modifier = Modifier.padding(bottom = 14.dp),
        )
    }
    CategoryDistributionBar(stats.bestCount, stats.normalCount, stats.trashTitles)
    listOf(
        Triple("Лучшие · ${stats.bestCount}", stats.bestTop, Color(0xFF36C995)),
        Triple("Нормальные · ${stats.normalCount}", stats.normalTop, Color(0xFF6BA9F0)),
        Triple("Слабые · ${stats.trashTitles}", stats.trashTop, AccentOrange),
    ).forEach { (heading, titles, categoryColor) ->
        Text(
            heading,
            color = categoryColor,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        if (titles.isEmpty()) Text("Пока нет тайтлов в этой категории", color = TextSecondary, fontSize = 13.sp)
        val strip = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyRow(
            state = strip,
            modifier = Modifier.dragScroll(strip),
            horizontalArrangement = Arrangement.spacedBy(scaled(12.dp)),
        ) {
            items(titles, key = { it.id }) { anime ->
                Column(Modifier.width(scaled(168.dp))) {
                    AsyncImage(
                        model = posterUrl(anime.poster, width = 160),
                        contentDescription = anime.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(scaled(238.dp))
                            .clip(RoundedCornerShape(12.dp)).background(Surface2),
                    )
                    Text(
                        anime.title,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    // Голоса ПОКАЗЫВАЕМ: плашка без цифры выглядит как вкусовщина,
                    // а с цифрой видно, что это чужое мнение, а не моё суждение.
                    Text(
                        stats.personalRatings[anime.id]?.let { "Ваша оценка: $it/5" } ?: "MAL %.2f · %d%% низких".format(
                            anime.malScore,
                            TrashAnime.lowShare(anime.malLowVotes, anime.malVotes).roundToInt(),
                        ),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = categoryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

/** One physical bar. Drawing the sections from the measured width avoids Row weight
 * layout collapsing a non-zero trailing section at some desktop window sizes. */
@Composable
private fun CategoryDistributionBar(best: Int, normal: Int, poor: Int) {
    val segments = categoryDistribution(best, normal, poor)
    Canvas(
        Modifier.fillMaxWidth().height(scaled(18.dp)).clip(RoundedCornerShape(9.dp)).background(Surface3),
    ) {
        var left = 0f
        segments.forEachIndexed { index, (fraction, color) ->
            val width = if (index == segments.lastIndex) size.width - left else size.width * fraction
            if (width > 0f) drawRect(color, Offset(left, 0f), Size(width, size.height))
            left += width
        }
    }
}

/** Fractions are based on every categorised title, including poor titles. */
internal fun categoryDistribution(best: Int, normal: Int, poor: Int): List<Pair<Float, Color>> {
    val total = (best.coerceAtLeast(0) + normal.coerceAtLeast(0) + poor.coerceAtLeast(0)).coerceAtLeast(1)
    return listOf(
        best.coerceAtLeast(0).toFloat() / total to Color(0xFF36C995),
        normal.coerceAtLeast(0).toFloat() / total to Color(0xFF6BA9F0),
        poor.coerceAtLeast(0).toFloat() / total to AccentOrange,
    ).filter { it.first > 0f }
}

private fun com.aniblaze.desktop.PersistedAnime.toAnimeLite() = Anime(
    id = id, title = title, poster = poster, year = year, rating = rating,
    description = description, status = status, broadcast = broadcast,
    genres = genres, studio = studio,
)

private fun plural(value: Int, one: String, few: String, many: String): String {
    val mod100 = value % 100
    val mod10 = value % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

// --- оформление ---

/**
 * Сияние на фоне — ОДИН проход при открытии, дальше картинка стоит.
 *
 * Здесь был бесконечный цикл, и он перерисовывал весь экран каждый кадр всё время,
 * пока раздел открыт: снаружи это выглядит как «анимация не прекращается», а по
 * факту это ещё и постоянная нагрузка на видеокарту ради фона, который никто не
 * разглядывает.
 */
@Composable
private fun AuroraBackdrop(animate: Boolean = true) {
    val shift = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) shift.animateTo(1f, tween(2_400, easing = LinearEasing)) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF1A0E0A).copy(alpha = 0.9f),
                    Color(0xFF120C18).copy(alpha = 0.7f),
                    Color(0xFF0A0A0F),
                ),
                start = androidx.compose.ui.geometry.Offset(0f, 900f * shift.value),
                end = androidx.compose.ui.geometry.Offset(1400f * (1f - shift.value) + 400f, 1600f),
            ),
        ),
    )
}

/** Крупная цифра, которая набирается от нуля. */
@Composable
private fun CountCard(
    title: String,
    value: Int,
    caption: String,
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    animate: Boolean = true,
) {
    // Повторный заход в раздел показывает готовые цифры: начальное состояние сразу
    // «конечное», и переход просто не запускается.
    var start by remember(value) { mutableStateOf(!animate) }
    LaunchedEffect(value) { start = true }
    val transition = androidx.compose.animation.core.updateTransition(start, label = "count")
    val shown by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 900, delayMillis = delayMs) },
        label = "value",
    ) { if (it) value.toFloat() else 0f }
    val lift by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 500, delayMillis = delayMs) },
        label = "lift",
    ) { if (it) 0f else 18f }

    Column(
        modifier
            .padding(top = lift.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B1B22).copy(alpha = 0.95f), Color(0xFF121218).copy(alpha = 0.95f)),
                ),
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(title.uppercase(), color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            shown.roundToInt().toString(),
            color = AccentOrange,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(caption, color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable ColumnScopeAlias.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF15151B).copy(alpha = 0.95f))
            .padding(18.dp),
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/** Полоса, вырастающая слева направо с задержкой — ряды заполняются волной. */
@Composable
private fun BarRow(label: String, value: Int, fraction: Float, delayMs: Int, suffix: String, animate: Boolean = true) {
    var start by remember(label, value) { mutableStateOf(!animate) }
    LaunchedEffect(label, value) { start = true }
    val grown by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (start) fraction.coerceIn(0.02f, 1f) else 0f,
        animationSpec = tween(durationMillis = 700, delayMillis = delayMs),
        label = "bar",
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(150.dp),
        )
        Box(
            Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF23232C)),
        ) {
            Box(
                Modifier.fillMaxWidth(grown).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF6336), Color(0xFFFFA24D)))),
            )
        }
        Text(
            "$value $suffix",
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp).width(96.dp),
        )
    }
}

/** Сутки в 24 столбика — сразу видно, что смотришь ночью. */
@Composable
private fun HourHistogram(hourly: List<Int>, animate: Boolean = true) {
    val peak = (hourly.maxOrNull() ?: 1).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(110.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        hourly.forEachIndexed { hour, count ->
            var start by remember(hourly) { mutableStateOf(!animate) }
            LaunchedEffect(hourly) { start = true }
            val height by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (start) count.toFloat() / peak else 0f,
                animationSpec = tween(durationMillis = 650, delayMillis = hour * 22),
                label = "hour",
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth()
                        .height((88.dp * height.coerceAtLeast(0.02f)))
                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                        .background(
                            if (count == peak) {
                                Brush.verticalGradient(listOf(Color(0xFFFFA24D), Color(0xFFFF6336)))
                            } else {
                                Brush.verticalGradient(listOf(Color(0xFF3A3A48), Color(0xFF23232C)))
                            },
                        ),
                )
                if (hour % 3 == 0) {
                    Text("$hour", color = TextSecondary, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun TopTitleCard(anime: com.aniblaze.desktop.PersistedAnime, count: Int, onClick: () -> Unit) {
    Column(
        Modifier.width(scaled(126.dp)).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                // ЧЕРЕЗ posterUrl, как везде. Здесь единственное место во всём
                // приложении, где постер тянулся напрямую с s.anixmirai.com — и
                // именно этот хост не резолвится (65 отказов DNS за сеанс против
                // нуля у прокси). Оттого в статистике постеры были пустыми, а в
                // сетке — на месте.
                model = posterUrl(anime.poster, width = 240),
                contentDescription = anime.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(scaled(178.dp)).clip(RoundedCornerShape(12.dp))
                    .background(Surface2),
            )
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentOrange)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text("$count", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            anime.title,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

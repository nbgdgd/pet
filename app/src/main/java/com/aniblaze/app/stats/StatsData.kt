package com.aniblaze.app.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Подсчёт статистики — ЧИСТЫЙ, без Android и без базы.
 *
 * Всё считается из того, что телефон и так хранит: строк таблицы `watch_progress`
 * (одна строка на серию: где остановился, сколько всего, когда трогали) и карточек
 * из `content`. Ничего не досчитывается по сети и ничего не выдумывается — чего в
 * базе нет, того нет и на экране.
 */

/** Одна строка `watch_progress`. Ключ таблицы — (contentId, segmentId), поэтому
 *  строк ровно столько, сколько серий человек открывал. */
internal data class WatchRow(
    val contentId: String,
    val segmentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val verifiedPlaybackMs: Long,
    val completed: Boolean,
)

/**
 * Метаданные тайтла из таблицы `content`.
 *
 * Поля MAL объявлены, но НИКТО их пока не заполняет: на телефоне нет источника
 * десятибалльной оценки (см. [TrashAnime]). Они здесь, чтобы «Шлакометр» заработал
 * без переделки экрана, как только оценки начнут сохраняться.
 */
internal data class TitleInfo(
    val id: String,
    val title: String,
    val poster: String,
    val genres: String,
    val studio: String,
    val year: Int,
    val malScore: Double = 0.0,
    val malVotes: Int = 0,
    val malLowVotes: Int = 0,
)

/** Тайтл и сколько его серий открыто. */
internal data class TitleCount(val title: TitleInfo, val episodes: Int)

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
    val topTitles: List<TitleCount>,
    /** Сколько просмотренных тайтлов собрано из штампов исекая (см. [TrashAnime]). */
    val trashTitles: Int,
    /** Сколько тайтлов вообще участвовало в подсчёте (у остальных мало голосов). */
    val trashJudged: Int,
    val trashPercent: Int,
    /** Средняя оценка MAL просмотренного — сравнивается с медианой каталога. */
    val trashAverageScore: Double,
    val trashTop: List<TitleInfo>,
) {
    companion object {
        val EMPTY = Stats(
            titles = 0, finishedTitles = 0, episodes = 0, hours = 0, streak = 0,
            daysActive = 0, longestDayMinutes = 0, averageMinutesPerDay = 0,
            busiestDayEpisodes = 0, peakHour = 0, hourly = List(24) { 0 },
            genres = emptyList(), studios = emptyList(), decades = emptyList(),
            topTitles = emptyList(), trashTitles = 0, trashJudged = 0, trashPercent = 0,
            trashAverageScore = 0.0, trashTop = emptyList(),
        )
    }
}

/**
 * @param rows строки `watch_progress`
 * @param titles карточки тайтлов, id → метаданные
 * @param episodeCounts сколько серий известно у тайтла (из `segment`); без записи
 *        «досмотрено до конца» посчитать нельзя, и тайтл в этот счёт не попадает
 */
internal fun buildStats(
    rows: List<WatchRow>,
    titles: Map<String, TitleInfo>,
    episodeCounts: Map<String, Int>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Stats {
    if (rows.isEmpty()) return Stats.EMPTY

    val completedRows = rows.filter { it.completed }
    val activityRows = rows.filter { it.verifiedPlaybackMs > 0L }
    val perTitle = completedRows.groupingBy { it.contentId }.eachCount()
    val episodes = completedRows.size

    // С v5 суммируется только проверенное движение медиачасов. Прыжок ползунком
    // больше не превращается в часы просмотра.
    val watchedMs = activityRows.sumOf { it.verifiedPlaybackMs }
    val hours = (watchedMs / 3_600_000L).toInt()

    // День берём из времени последнего касания серии. Серия, растянутая на два
    // вечера, целиком уедет во второй — другого времени в базе нет.
    val byDay = activityRows.groupBy { Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() }
    val days = byDay.keys.sorted()
    var streak = 0
    if (days.isNotEmpty()) {
        val known = days.toSet()
        // Серия «дней подряд» жива, только если последний день — сегодня или вчера:
        // иначе это уже прошлая серия, а не текущая.
        var cursor: LocalDate? =
            if (days.last() == today || days.last() == today.minusDays(1)) days.last() else null
        while (cursor != null && known.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }
    }
    val longestDayMs = byDay.values.maxOfOrNull { day -> day.sumOf { it.verifiedPlaybackMs } } ?: 0L
    val averageDayMs = if (byDay.isEmpty()) 0L else watchedMs / byDay.size

    // Час суток — из тех же отметок времени: единственное, что хранит КОГДА смотрели.
    val hourly = MutableList(24) { 0 }
    activityRows.forEach { row ->
        val hour = runCatching {
            Instant.ofEpochMilli(row.updatedAt).atZone(zone).hour
        }.getOrNull() ?: return@forEach
        hourly[hour] = hourly[hour] + 1
    }

    fun topOf(limit: Int, select: (TitleInfo) -> List<String>): List<Pair<String, Int>> =
        perTitle.keys.mapNotNull { titles[it] }
            .flatMap(select)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    val genres = topOf(8) { it.genres.split(',', '·', '/') }
    val studios = topOf(6) { listOf(it.studio) }
    val decades = perTitle.keys.mapNotNull { titles[it] }
        .map { it.year }
        .filter { it in 1960..2100 }
        .groupingBy { it.toString() }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .map { it.key to it.value }

    val topTitles = perTitle.entries
        .sortedByDescending { it.value }
        .mapNotNull { (id, count) -> titles[id]?.let { TitleCount(it, count) } }
        .take(12)

    // Судим ТОЛЬКО по тайтлам, у которых набралось достаточно голосов: у свежего
    // онгоинга с десятком оценок одна злая ставит 20% «низких» на ровном месте.
    // На телефоне таких тайтлов сейчас ноль — оценки MAL не сохраняются, — и
    // список остаётся пустым, а экран показывает это словами.
    val judged = perTitle.keys.mapNotNull { titles[it] }.filter { TrashAnime.hasVerdict(it.malVotes) }
    val trash = judged.filter { TrashAnime.isTrash(it.malScore, it.malLowVotes, it.malVotes) }

    return Stats(
        titles = perTitle.size,
        // «Досмотрел» = завершённых серий не меньше, чем их известно у тайтла.
        finishedTitles = perTitle.count { (id, count) ->
            val total = episodeCounts[id] ?: 0
            total > 0 && count >= total
        },
        episodes = episodes,
        hours = hours,
        streak = streak,
        daysActive = byDay.size,
        longestDayMinutes = (longestDayMs / 60_000L).toInt(),
        averageMinutesPerDay = (averageDayMs / 60_000L).toInt(),
        // На ПК в эту цифру по недосмотру попадал максимум серий ОДНОГО тайтла.
        // Здесь у каждой строки есть свой день, поэтому считаем то, что написано
        // на плитке, — сколько серий пришлось на самый плотный день.
        busiestDayEpisodes = byDay.values.maxOfOrNull { it.size } ?: 0,
        peakHour = hourly.indices.maxByOrNull { hourly[it] } ?: 0,
        hourly = hourly,
        genres = genres,
        studios = studios,
        decades = decades,
        topTitles = topTitles,
        trashTitles = trash.size,
        trashJudged = judged.size,
        trashPercent = if (judged.isEmpty()) 0 else trash.size * 100 / judged.size,
        trashAverageScore = if (judged.isEmpty()) 0.0 else judged.map { it.malScore }.average(),
        trashTop = trash
            .sortedByDescending { TrashAnime.severity(it.malScore, it.malLowVotes, it.malVotes) }
            .take(8),
    )
}

/** Русское склонение по числу: 1 тайтл, 2 тайтла, 5 тайтлов. */
internal fun plural(value: Int, one: String, few: String, many: String): String {
    val mod100 = value % 100
    val mod10 = value % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

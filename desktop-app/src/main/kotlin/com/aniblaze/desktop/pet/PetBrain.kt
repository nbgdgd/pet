package com.aniblaze.desktop.pet

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.source.EpisodeAirDates
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Что питомец знает про тайтл: честные числа из уже существующих данных AniBlaze
 * (история просмотра, расписание, отметки серий). Ничего не выдумывает: неизвестное
 * остаётся null и превращается в «Дата пока неизвестна», а не в правдоподобное число.
 */
data class PetTitleStats(
    val title: String,
    /** Сколько ВЫШЕДШИХ серий ещё не просмотрено (null = серии неизвестны). */
    val unwatchedAired: Int?,
    /** Сколько серий до конца сезона, если общее число известно. */
    val unwatchedTotal: Int?,
    val airedEpisodes: Int,
    val totalEpisodes: Int?,
    /** Следующая серия: номер и время выхода (epoch ms), если расписание знает. */
    val nextEpisode: Int?,
    val nextAiringAt: Long?,
    /** Реально отсмотренное время именно этого тайтла, мс. 0 = истории нет. */
    val watchedMs: Long,
    val seasonFinished: Boolean,
    val seasonCompleted: Boolean = false,
)

/** Настроение — оно же выбор анимации. */
enum class PetMood {
    GREETING,
    CELEBRATING,
    IDLE,
    HAPPY,
    SAD,
    SLEEPY,
    /** Ждёт: догнали релиз, буферизация. */
    WAITING,
    /** Пауза — садится и ждёт, это видно сразу. */
    PAUSED,
    /** Удивился (перемотки назад) — оглядывается на тебя. */
    SURPRISED,
    /** Ускорение / перемотки вперёд — короткий прыжок. */
    EXCITED,
    /** Прислушивается к новой озвучке. */
    LISTEN,
    /** Поёрзал в покое — без текста. */
    FIDGET,
}

data class PetSay(val text: String, val mood: PetMood)

/**
 * Расчёт статистики тайтла. Все входы — уже готовые данные приложения:
 * [watchedEpisodes] — отметки просмотренных серий, [airedEpisodes] — сколько серий
 * реально доступно, [schedule] — расписание AniList/TMDB, [watchedMs] — измеренное
 * время воспроизведения (без пауз, буферизации и перемоток).
 */
fun petTitleStats(
    anime: Anime,
    watchedEpisodes: Int,
    airedEpisodes: Int,
    schedule: EpisodeAirDates.TitleSchedule,
    watchedMs: Long,
    seasonCompleted: Boolean = false,
): PetTitleStats {
    val total = (schedule.totalEpisodes.takeIf { it > 0 } ?: anime.episodesTotal.takeIf { it > 0 })
    val aired = maxOf(airedEpisodes, anime.episodesAvailable, watchedEpisodes)
    val next = schedule.nextEpisode.takeIf { it > 0 }
    val nextAt = schedule.nextAiringAt.takeIf { it > 0 }?.times(1000L)
    val finished = schedule.status == EpisodeAirDates.Status.FINISHED ||
        (schedule.status != EpisodeAirDates.Status.AIRING && anime.airingStatus == 1)
    return PetTitleStats(
        title = anime.title,
        unwatchedAired = if (aired > 0) (aired - watchedEpisodes).coerceAtLeast(0) else null,
        unwatchedTotal = total?.let { (it - watchedEpisodes).coerceAtLeast(0) },
        airedEpisodes = aired,
        totalEpisodes = total,
        nextEpisode = next,
        nextAiringAt = nextAt,
        watchedMs = watchedMs,
        seasonFinished = finished,
        seasonCompleted = seasonCompleted,
    )
}

/** «2 ч 15 мин», «34 мин», null — истории нет и выдумывать нечего. */
fun petWatchTimeLabel(watchedMs: Long): String? {
    if (watchedMs < 60_000L) return null
    val minutes = watchedMs / 60_000L
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0 && rest > 0 -> "$hours ч $rest мин"
        hours > 0 -> "$hours ч"
        else -> "$minutes мин"
    }
}

/**
 * Когда выйдет следующая серия — дата и обратный отсчёт в зоне пользователя.
 * null — расписание не знает; звать это «скоро» нельзя.
 */
fun petNextEpisodeLabel(stats: PetTitleStats, now: Long = System.currentTimeMillis()): String? {
    val at = stats.nextAiringAt ?: return null
    if (at <= now) return null // An expired forecast is not evidence of a release.
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, date.toLocalDate())
    val hours = ChronoUnit.HOURS.between(Instant.ofEpochMilli(now), Instant.ofEpochMilli(at))
    val when_ = when {
        days == 0L && hours >= 1 -> "сегодня через $hours ч"
        days == 0L -> "сегодня"
        days == 1L -> "завтра"
        days in 2..6 -> "через $days дн."
        else -> "${date.dayOfMonth} ${RU_MONTHS[date.monthValue - 1]}"
    }
    val time = "%02d:%02d".format(date.hour, date.minute)
    val number = stats.nextEpisode?.let { "Серия $it" } ?: "Следующая серия"
    return "$number — $when_ в $time"
}

private val RU_MONTHS = listOf(
    "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
)

/** Событие, на которое питомец реагирует. */
enum class PetEvent {
    /** Открыли тайтл, который уже смотрели. */
    RETURN,
    /** Открыли тайтл, который уже досмотрели, — пересмотр. */
    REWATCH,
    /** Началась серия. */
    EPISODE_START,
    /** Досмотрели серию. */
    EPISODE_DONE,
    /** Досмотрены все вышедшие, но сезон ещё идёт. */
    CAUGHT_UP,
    /** Сезон завершён и досмотрен. */
    SEASON_DONE,
    /** Вышла новая серия, которой раньше не было. */
    NEW_EPISODE,
    /** Долгая пауза. */
    IDLE_LONG,
    /** Поёрзал в покое — короткая реплика под движение. */
    FIDGET,
    /** Короткая пауза, про которую нечего рассказать цифрами. */
    PAUSE,
}

/**
 * Локальные шаблоны фраз — без сети и ИИ. Выбор псевдослучайный, но детерминированный
 * по [seed]: вызывающий крутит seed сам, и одна и та же фраза не повторяется подряд
 * (см. [PetPhrasePicker]).
 */
object PetPhrases {
    val EPISODE_START = listOf(
        "Поехали!",
        "Смотрим дальше.",
        "Устроился. Начинаем.",
        "Я рядом, смотри спокойно.",
        "Устраивайся поудобнее.",
        "Место занял. Погнали.",
    )
    val REWATCH = listOf(
        "Ты уже смотрел это раньше",
        "Пересмотр? Устраиваюсь поудобнее",
        "Знакомое. Смотрим ещё раз?",
    )
    val RETURN = listOf(
        "С возвращением! Продолжаем?",
        "О, снова ты. Я ждал.",
        "Опять вместе. Где мы остановились?",
    )
    val EPISODE_DONE = listOf(
        "Серия позади. Ещё одну?",
        "Серия досмотрена.",
        "Готово. Дальше?",
        "Можно немного отдохнуть.",
        "Продолжим или сделаем перерыв?",
    )
    val CAUGHT_UP = listOf(
        "Пока всё. Ждём новую серию!",
        "Догнали релиз. Теперь ждём.",
        "Свежих серий нет. Ждём выхода.",
    )
    val SEASON_DONE = listOf(
        "Мы досмотрели сезон. Что посмотрим дальше?",
        "Сезон закрыт. Ищем новое?",
        "Финал позади. Куда дальше?",
        "И это всё? Я не готов прощаться.",
    )
    val NEW_EPISODE = listOf(
        "Вышла новая серия!",
        "Свежая серия готова!",
        "Есть новая серия — бежим смотреть!",
    )
    val IDLE_LONG = listOf(
        "Я подремлю, пока ты думаешь.",
        "Отдыхаем? Я тоже.",
        "Сплю вполглаза. Буди, когда продолжим.",
    )
    val NEUTRAL = listOf(
        "Что посмотрим?",
        "Я рядом.",
        "Выбирай — я подожду.",
    )
    val FIDGET = listOf(
        "Поёрзаю немного.",
        "Всё тихо. Смотрю.",
        "Я тут, если что.",
        "Разминаю лапы.",
    )
    val PAUSE = listOf(
        "Пауза. Я подожду.",
        "Сижу, жду.",
        "Не спеши, я никуда не денусь.",
    )

    fun of(event: PetEvent?): List<String> = when (event) {
        PetEvent.EPISODE_START -> EPISODE_START
        PetEvent.RETURN -> RETURN
        PetEvent.REWATCH -> REWATCH
        PetEvent.EPISODE_DONE -> EPISODE_DONE
        PetEvent.CAUGHT_UP -> CAUGHT_UP
        PetEvent.SEASON_DONE -> SEASON_DONE
        PetEvent.NEW_EPISODE -> NEW_EPISODE
        PetEvent.IDLE_LONG -> IDLE_LONG
        PetEvent.FIDGET -> FIDGET
        PetEvent.PAUSE -> PAUSE
        null -> NEUTRAL
    }

    fun moodOf(event: PetEvent?): PetMood = when (event) {
        PetEvent.NEW_EPISODE, PetEvent.EPISODE_DONE, PetEvent.RETURN, PetEvent.REWATCH -> PetMood.HAPPY
        PetEvent.EPISODE_START -> PetMood.IDLE
        PetEvent.CAUGHT_UP -> PetMood.WAITING
        PetEvent.SEASON_DONE -> PetMood.CELEBRATING
        PetEvent.IDLE_LONG -> PetMood.SLEEPY
        PetEvent.FIDGET -> PetMood.FIDGET
        PetEvent.PAUSE -> PetMood.PAUSED
        null -> PetMood.IDLE
    }
}

/** Выбор фразы без повторов подряд. */
class PetPhrasePicker {
    private val recent = ArrayDeque<String>()

    fun pick(event: PetEvent?, random: java.util.Random = shared): String = pickFrom(PetPhrases.of(event), random)

    fun pickFrom(phrases: List<String>, random: java.util.Random = shared): String {
        val options = phrases.filter { it.isNotBlank() }.distinct()
        require(options.isNotEmpty()) { "A phrase pool must not be empty" }
        var pool = options.filter { it !in recent }
        while (pool.isEmpty() && recent.isNotEmpty()) {
            recent.removeFirst()
            pool = options.filter { it !in recent }
        }
        val chosen = pool[random.nextInt(pool.size)]
        recent.addLast(chosen)
        while (recent.size > 12) recent.removeFirst()
        return chosen
    }

    private companion object { val shared = java.util.Random() }
}

/** Анимация под настроение. */
fun petActionFor(mood: PetMood): PetAction = when (mood) {
    PetMood.GREETING -> PetAction.WAVE
    PetMood.CELEBRATING -> PetAction.CELEBRATE
    PetMood.HAPPY -> PetAction.HAPPY
    PetMood.SAD -> PetAction.SAD
    PetMood.SLEEPY -> PetAction.SLEEP
    PetMood.WAITING -> PetAction.TIRED
    PetMood.PAUSED -> PetAction.SIT
    PetMood.SURPRISED, PetMood.LISTEN, PetMood.FIDGET -> PetAction.LOOK_AROUND
    PetMood.EXCITED -> PetAction.JUMP
    PetMood.IDLE -> PetAction.IDLE
}

/**
 * Что сказать про тайтл прямо сейчас: сначала событие, потом — цифры.
 * Возвращает строки для карточки: первая — реакция, дальше факты (что известно).
 */
fun petStatLines(stats: PetTitleStats, now: Long = System.currentTimeMillis(), daysSinceLastWatch: Int = -1): List<String> = buildList {
    if (daysSinceLastWatch >= 1) add("В прошлый раз ты остановился здесь $daysSinceLastWatch ${PetDirector.dayWord(daysSinceLastWatch)} назад")
    val unwatched = stats.unwatchedAired
    when {
        stats.seasonCompleted -> add("Сезон досмотрен")
        unwatched != null && unwatched > 0 -> {
            val tail = stats.unwatchedTotal?.takeIf { it != unwatched }?.let { ", до конца сезона $it" }.orEmpty()
            add("Не просмотрено вышедших: $unwatched$tail")
        }
        unwatched == 0 -> add("Все вышедшие серии просмотрены")
    }
    if (!stats.seasonFinished) add(petNextEpisodeLabel(stats, now) ?: "Дата следующей серии пока неизвестна")
    petWatchTimeLabel(stats.watchedMs)?.let { add("Ты смотрел это $it") }
}

/**
 * Что сказать по ходу серии. Возвращает null, когда событие незачем озвучивать.
 *
 * Номер серии сам по себе не доказывает завершение сезона. Флаги завершения
 * передаются только после проверки настоящих отметок просмотра и статуса релиза.
 */
fun petPlaybackEvent(
    episode: Int,
    episodesAvailable: Int,
    episodesTotal: Int,
    finished: Boolean,
    seasonCompleted: Boolean = false,
    caughtUp: Boolean = false,
): PetEvent = when {
    !finished -> PetEvent.EPISODE_START
    seasonCompleted -> PetEvent.SEASON_DONE
    caughtUp -> PetEvent.CAUGHT_UP
    else -> PetEvent.EPISODE_DONE
}

/** Сколько держать реплику на экране, мс. */
const val PET_SAY_MS = 7_000L

/** После какой ручной паузы питомец засыпает, мс. */
const val PET_SLEEP_AFTER_MS = PetDirector.SLEEP_AFTER_MS

/** Only a real tracked release in favorites may produce a release message. */
internal fun petFreshRelease(state: com.aniblaze.desktop.PersistedState, now: Long): com.aniblaze.desktop.PersistedAnime? =
    state.favorites.firstOrNull { anime ->
        val episode = state.newEpisodes[anime.id] ?: 0
        val at = state.newEpisodeAt[anime.id] ?: 0L
        episode > 0 && at > 0 && now - at in 0..com.aniblaze.desktop.NEW_EPISODE_WINDOW_MS &&
            anime.id !in state.releaseMuted &&
            "new:${anime.id}:$episode" !in state.petAnnouncedEvents
    }

internal fun petMainWatchedCount(state: com.aniblaze.desktop.PersistedState, id: String, total: Int): Int =
    state.watched.asSequence().filter { it.substringBeforeLast('#') == id }
        .mapNotNull { it.substringAfterLast('#').toIntOrNull() }
        .filter { it > 0 && (total <= 0 || it <= total) }.distinct().count()

/** Reuses the statistics ledger; opening cards never creates viewing time. */
internal fun petDiary(state: com.aniblaze.desktop.PersistedState, today: java.time.LocalDate): List<String> = buildList {
    val todayMs = (state.watchedByDay[today.toString()] ?: 0L).coerceAtLeast(0)
    add("Сегодня: " + when {
        todayMs == 0L -> "ещё не смотрели"
        todayMs < 60_000L -> "меньше минуты просмотра"
        else -> petWatchTimeLabel(todayMs) + " просмотра"
    })
    // Недельный итог: последние семь дней включая сегодня, и сколько серий досмотрено
    // за них (по времени отметок watchedAt). Нули не показываем — «0 ч» не новость.
    val weekStart = today.minusDays(6)
    val weekMs = state.watchedByDay.entries.sumOf { (date, ms) ->
        val day = runCatching { java.time.LocalDate.parse(date) }.getOrNull()
        if (day != null && day >= weekStart && day <= today) ms.coerceAtLeast(0) else 0L
    }
    if (weekMs >= 60_000L) {
        val zone = java.time.ZoneId.systemDefault()
        val fromMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val episodes = state.watchedAt.values.count { it in fromMs until toMs }
        add("За неделю: " + petWatchTimeLabel(weekMs) + (if (episodes > 0) ", серий: $episodes" else ""))
    }
    val activeDays = state.watchedByDay.count { (date, ms) ->
        ms > 0 && runCatching { java.time.LocalDate.parse(date) <= today }.getOrDefault(false)
    }
    if (activeDays > 0) add("Дней с просмотром: $activeDays")
}

internal fun petIdleMood(hour: Int, idleMs: Long): PetMood =
    if (hour in 0..6 && idleMs >= 2 * 60_000L) PetMood.SLEEPY else PetMood.IDLE

/** Reuse the existing personalities, without changing their own dialogue. */
internal object DrizzPhrases {
    val greetings: List<String> get() = PetDef.ALL.map { it.personality.greeting } + PetPhrases.NEUTRAL
    val pauses: List<String> get() = PetDef.ALL.map { it.personality.pause } + PetPhrases.PAUSE
    val titles: List<String> get() = PetDef.ALL.map {
        it.personality.titleReaction.replace("устроилась", "устроился")
    }
}

internal fun petGreeting(pet: PetDef, hour: Int, phrase: String = pet.personality.greeting): String = when (hour) {
    in 5..10 -> "Доброе утро! $phrase"
    in 0..4 -> "Доброй ночи. $phrase"
    else -> phrase
}

internal fun petRatingReaction(score: Int): PetSay? = when (score) {
    5 -> PetSay("Пять из пяти! Нашлось что-то особенное", PetMood.CELEBRATING)
    4 -> PetSay("Хорошо провели время", PetMood.HAPPY)
    3 -> PetSay("Нормально, но есть куда лучше", PetMood.IDLE)
    1, 2 -> PetSay("Не зашло. Выберем что-нибудь другое", PetMood.LISTEN)
    else -> null
}

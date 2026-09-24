package com.aniblaze.desktop.pet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.desktop.toPersisted
import com.aniblaze.desktop.RecommendationCompletion
import com.aniblaze.desktop.recommendationCompletion
import com.aniblaze.aggregator.source.EpisodeAirDates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Питомец в обычном интерфейсе: собирает состояние из УЖЕ существующих данных —
 * истории, отметок серий, расписания и рекомендаций. Своей системы рекомендаций и
 * своих тайтлов не выдумывает: если рекомендаций нет, карточки не будет.
 *
 * Сеть трогается только при смене тайтла (расписание + рекомендации), по одному
 * запросу; пока идёт воспроизведение — не трогается вовсе.
 */
@Composable
fun PetHost(
    settings: AppSettings,
    repository: DesktopRepository,
    /** Тайтл, о котором говорить: открытая карточка или последний из истории. */
    focus: Anime?,
    playing: Boolean,
    onOpenTitle: (Anime) -> Unit,
    onResume: ((Anime, Int) -> Unit)?,
    modifier: Modifier = Modifier,
    windowActive: Boolean = true,
    focusIsDetail: Boolean = false,
) {
    val state by settings.state.collectAsState()
    if (!state.petEnabled) return
    val pet = remember(state.petCharacter) { PetDef.of(state.petCharacter) }
    val memory = settings.petUiMemory
    val reducedMotion = remember { com.aniblaze.desktop.player.systemPrefersReducedMotion() }
    val picker = memory.phrases

    var schedule by remember(focus?.id) { mutableStateOf(memory.titles[focus?.id]?.schedule ?: EpisodeAirDates.TitleSchedule.EMPTY) }
    var pool by remember(focus?.id) { mutableStateOf(memory.titles[focus?.id]?.recommendations.orEmpty()) }
    var recIndex by remember(focus?.id) { mutableStateOf(0) }

    // Один проход на тайтл: расписание и рекомендации. Во время воспроизведения
    // ничего не грузим — канал нужен потоку.
    LaunchedEffect(focus?.id, playing, windowActive) {
        val anime = focus
        if (anime == null || playing || !windowActive) return@LaunchedEffect
        if (memory.titles[anime.id]?.let { System.currentTimeMillis() - it.at in 0..900_000L } == true) return@LaunchedEffect
        schedule = try {
            repository.titleSchedule(anime)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule.EMPTY
        }
        pool = try {
            // Сначала — следующий сезон той же франшизы (если он есть и не досмотрен),
            // потом похожее из готовых рекомендаций AniBlaze.
            val franchise = repository.seasons(anime)
            val nextSeason = com.aniblaze.desktop.ui.seasonMenu(anime, franchise, settings.state.value)
                .dropWhile { it.anime.id != anime.id }.drop(1)
                .firstOrNull { it.status != "Просмотрено" }?.anime
            val similar = repository.similarTitles(anime, franchise).filter { candidate ->
                candidate.id != anime.id && settings.watchOf(candidate.id)?.finished != true && candidate.id != nextSeason?.id
            }
            listOfNotNull(nextSeason) + similar
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        memory.titles[anime.id] = PetUiMemory.CachedTitle(schedule, pool, System.currentTimeMillis())
        while (memory.titles.size > 16) memory.titles.remove(memory.titles.keys.first())
    }

    val watch = focus?.let { settings.watchOf(it.id, state) }
    val completion = focus?.let {
        val card = it.toPersisted().let { saved -> saved.copy(
            airingStatus = when (schedule.status) {
                EpisodeAirDates.Status.FINISHED -> 1
                EpisodeAirDates.Status.AIRING -> 2
                else -> saved.airingStatus
            },
            episodesTotal = schedule.totalEpisodes.takeIf { n -> n > 0 } ?: saved.episodesTotal,
        ) }
        recommendationCompletion(state, card)
    }
    val stats = focus?.let { anime -> petTitleStats(anime,
        petMainWatchedCount(state, anime.id, schedule.totalEpisodes.takeIf { it > 0 } ?: anime.episodesTotal),
        maxOf(state.episodeCounts[anime.id] ?: 0, anime.episodesAvailable), schedule,
        state.watchedMsByTitle[anime.id] ?: 0L,
        seasonCompleted = completion == RecommendationCompletion.COMPLETED) }
    val event = when {
        completion == RecommendationCompletion.COMPLETED -> PetEvent.REWATCH
        completion == RecommendationCompletion.CAUGHT_UP -> PetEvent.CAUGHT_UP
        (watch?.completedCount ?: 0) > 0 -> PetEvent.RETURN
        else -> null
    }
    var say by remember { mutableStateOf<String?>(null) }
    var mood by remember { mutableStateOf(PetMood.IDLE) }
    var today by remember { mutableStateOf(java.time.LocalDate.now()) }
    val currentState by rememberUpdatedState(state)
    val currentFocus by rememberUpdatedState(focus)
    val currentDetail by rememberUpdatedState(focusIsDetail)
    val currentEvent by rememberUpdatedState(event)
    LaunchedEffect(pet.id, playing, windowActive, state.petSpeechEnabled) {
        say = null
        mood = PetMood.IDLE
        if (!windowActive) return@LaunchedEffect
        var shownAt = 0L
        if (memory.lastMotionAt == 0L) memory.lastMotionAt = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val localNow = java.time.LocalDateTime.now()
            today = localNow.toLocalDate()
            val idleMood = if (playing) PetMood.IDLE else petIdleMood(localNow.hour, now - memory.lastInteractionAt)
            if (now - shownAt >= PET_SAY_MS) say = null
            if (now - shownAt >= pet.clip(petActionFor(mood)).durations.sum()) mood = idleMood
            // Real release evidence comes from the existing tracker, not from a predicted air date.
            if (!playing && say == null) {
                val snapshot = currentState
                val focusKey = currentFocus?.id?.let { if (pet.id == "drizz" && currentDetail) "detail:$it" else it }
                val fresh = petFreshRelease(snapshot, now)
                val text = when {
                    !memory.greeted -> {
                        memory.greeted = true
                        mood = PetMood.GREETING
                        petGreeting(pet, localNow.hour,
                            if (pet.id == "drizz") picker.pickFrom(DrizzPhrases.greetings) else pet.personality.greeting)
                    }
                    // Новую серию в избранном объявляет окно PetReleasePopup (оно есть и в
                    // плеере); здесь только радуемся, когда оно уже показано.
                    fresh == null && snapshot.petAnnouncedEvents.any { it.startsWith("new:") } && memory.lastReleaseCheer != snapshot.petAnnouncedEvents.size -> {
                        memory.lastReleaseCheer = snapshot.petAnnouncedEvents.size
                        mood = PetMood.GREETING
                        null
                    }
                    focusKey != memory.lastFocus -> {
                        memory.lastFocus = focusKey
                        memory.lastInteractionAt = now
                        if (currentFocus != null && now - memory.lastSpeechAt >=
                            (if (pet.id == "drizz") drizzActionSpeechInterval(snapshot.drizzActivity)
                             else snapshot.petChatterMinutes * 60_000L * pet.personality.speechMultiplier)) {
                            mood = PetMood.LISTEN
                            if (pet.id == "drizz") {
                                if (currentDetail) "«${currentFocus?.title}». ${picker.pickFrom(DrizzPhrases.titles)}" else null
                            }
                            else if (currentEvent == null) pet.personality.titleReaction else picker.pick(currentEvent)
                        } else null
                    }
                    else -> null
                }
                if (text != null) {
                    shownAt = now
                    if (snapshot.petSpeechEnabled) { say = text; memory.lastSpeechAt = now }
                } else if (idleMood == PetMood.IDLE && now - memory.lastMotionAt >=
                    if (pet.id == "drizz") drizzMotionInterval(1, snapshot.drizzActivity) else pet.personality.fidgetMinutes * 60_000L) {
                    memory.lastMotionAt = now
                    shownAt = now
                    mood = PetMood.FIDGET
                }
            }
            delay(1_000)
        }
    }
    val eligiblePool = remember(pool, state.ratings, focus?.id, pet.id) {
        val disliked = state.ratings.filter { it.score in 1..2 }.map { it.anime.id }.toSet()
        // Do not recommend similar titles on the strength of a disliked seed.
        if (pet.id == "drizz" && focus?.id in disliked) emptyList() else pool.filterNot { it.id in disliked }
    }
    val recommendation = eligiblePool.getOrNull(recIndex % eligiblePool.size.coerceAtLeast(1))?.let { candidate ->
        val reason = focus?.title?.let { title ->
            when {
                completion == RecommendationCompletion.COMPLETED -> "После «$title» предлагаю попробовать:"
                else -> "Похожие на «$title»:"
            }
        } ?: "Может понравиться:"
        PetRecommendation(candidate, reason)
    }
    // Почему карточка про ЭТОТ тайтл. Без подписи на «Главной» это выглядело как
    // случайное название: питомец берёт последнее из истории, но не говорил об этом.
    val focusHint = focus?.let { anime ->
        val watch = settings.watchOf(anime.id)
        when {
            focusIsDetail -> "Открытый тайтл"
            completion == RecommendationCompletion.COMPLETED -> "Ты это досмотрел"
            completion == RecommendationCompletion.CAUGHT_UP -> "Просмотрены все доступные серии"
            watch != null -> "Продолжаем с серии ${watch.episode}"
            else -> "Выбранный тайтл"
        }
    }
    val resumeAction = focus?.let { anime ->
        val watch = settings.watchOf(anime.id) ?: return@let null
        if (watch.finished || onResume == null) return@let null
        "Продолжить · Серия ${watch.episode}" to { onResume(anime, watch.episode) }
    }

    PetDesktopPosition(state.petDesktopX, state.petDesktopY, settings::setPetDesktopPosition, modifier) { positioned, drag, endDrag ->
    PetCorner(
        pet = pet,
        mood = mood,
        scale = state.petScale,
        say = say,
        stats = (if (pet.id == "drizz" && focus != null) petRatingLines(settings.ratingOf(focus.id), focus.rating, focus.ratingMax) else emptyList()) +
            stats?.let { petStatLines(it, daysSinceLastWatch = focus?.let { a -> settings.daysSinceLastWatch(a.id) } ?: -1) }.orEmpty(),
        statsTitle = focus?.title,
        statsSubtitle = focusHint,
        diary = remember(state.watchedByDay, today) { petDiary(state, today) },
        onInteract = {
            memory.lastInteractionAt = System.currentTimeMillis()
            memory.lastMotionAt = memory.lastInteractionAt
            mood = PetMood.IDLE
        },
        resume = resumeAction,
        recommendation = recommendation,
        onAnotherRecommendation = if (eligiblePool.size > 1) ({ recIndex++ }) else null,
        onOpenRecommendation = onOpenTitle,
        onHide = { settings.setPetEnabled(false) },
        animate = windowActive && !reducedMotion,
        modifier = positioned,
        onDrag = drag,
        onDragEnd = endDrag,
    )
    }
}

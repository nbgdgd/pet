package com.aniblaze.desktop.pet

/**
 * Что питомец говорит и как выглядит ПО ХОДУ серии.
 *
 * Правило одно: болтать редко. Обычные наблюдения («смотришь уже 40 минут») идут не
 * чаще [chatterCooldownMs] и только когда на экране ничего не происходит; важное
 * (конец серии, сезон, буферизация, смена источника) проходит вне очереди. Многое —
 * вовсе без текста, одной анимацией: удивление на перемотках, прыжок на ускорении,
 * редкое «поёрзать» в долгом покое.
 *
 * Класс чистый: ни Compose, ни таймеров — состояние снаружи, решение внутри. Поэтому
 * его поведение проверяется тестом целиком.
 */
class PetDirector(
    /** Кулдаун необязательных реплик, мс. Настраивается (см. AppSettings.petChatterMinutes). */
    var chatterCooldownMs: Long = CHATTER_COOLDOWN_MS,
) {
    /** Когда последний раз выдавали НЕважную реплику. */
    // Молчали «всегда»: первая уместная реплика не ждёт кулдауна.
    private var lastChatterAt = Long.MIN_VALUE / 2
    /** Что уже сказано про эту серию, чтобы не повторяться на перемотках. */
    private var saidFor = HashSet<String>()
    private var episodeKey = ""
    private val picker = PetPhrasePicker()
    /** Редкое «поёрзать» в покое: когда было последнее и до какого момента идёт. */
    private var lastFidgetAt = Long.MIN_VALUE / 2
    /** Озвучка, с которой серия началась: реагируем только на её СМЕНУ. */
    private var initialVoice: String? = null
    private val saidByEpisode = LinkedHashMap<String, HashSet<String>>()
    private var active: Decision? = null
    private var activeUntil = 0L
    private var speechUntil = 0L
    private var previousMode = ""
    private var previousDrizz: Scene? = null
    private var observedKey = ""
    private var bufferSince: Long? = null
    private var noticeableBuffer = false
    private var lastActionAt = Long.MIN_VALUE / 2
    private var lastActionSpeechAt = Long.MIN_VALUE / 2
    private var lastRestAt = 0L
    private var fidgetNumber = 0
    private var activePriority = 0

    /**
     * Снимок происходящего. Всё — уже существующие данные плеера и настроек;
     * чего нет — ноль/пусто, и питомец про это не говорит.
     */
    data class Scene(
        val episode: Int,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val buffering: Boolean,
        /** Сколько серий доступно и сколько всего в сезоне (0 = неизвестно). */
        val episodesAvailable: Int,
        val episodesTotal: Int,
        /** Сколько серий этого тайтла посмотрели подряд в этот заход. */
        val streak: Int,
        /** Сколько идёт этот заход (все серии подряд), мс. */
        val sessionMs: Long,
        /** Пауза длится столько, мс (0 — играет). */
        val pausedForMs: Long,
        /** С какой позиции продолжили после паузы/возврата, мс; 0 — не продолжали. */
        val resumedFromMs: Long = 0L,
        val now: Long = System.currentTimeMillis(),
        // --- память и контекст тайтла ---
        val titleName: String = "",
        /** Сколько реально отсмотрено по тайтлу за всё время, мс (0 — истории нет). */
        val titleWatchedMs: Long = 0L,
        /** Сколько серий тайтла отмечено просмотренными (для карточки на паузе). */
        val watchedEpisodes: Int = 0,
        /** Дней с прошлого просмотра тайтла; -1 — не смотрели / неизвестно. */
        val daysSinceLastWatch: Int = -1,
        /** Тайтл уже досмотрен целиком — это пересмотр. */
        val rewatch: Boolean = false,
        /** С какой позиции серия открылась (продолжение прошлого просмотра), мс. */
        val openedAtMs: Long = 0L,
        // --- действия пользователя ---
        val speed: Float = 1f,
        /** Перемоток назад / вперёд за последнюю минуту. */
        val rewinds: Int = 0,
        val forwards: Int = 0,
        /** Текущая озвучка; смена — повод прислушаться. */
        val voiceName: String = "",
        /** Источник сменился по ходу серии (упал и переключили). */
        val sourceSwitched: Boolean = false,
        /** Качество снижено автоматически из-за соединения. */
        val qualityForced: Boolean = false,
        /** Час суток по местному времени, 0..23; -1 — не знаем. */
        val hourOfDay: Int = -1,
        /** Жанры тайтла строкой каталога; пусто — источник не сказал. */
        val genres: String = "",
        /** Оценка каталога и её шкала (0 — оценки нет). */
        val rating: Double = 0.0,
        val ratingMax: Double = 0.0,
        /** Полноэкранный режим — сам по себе повод для короткой реакции. */
        val fullscreen: Boolean = false,
        /** Жанры последних просмотренных тайтлов (свежие первыми), по строке жанров на тайтл. */
        val recentGenres: List<String> = emptyList(),
        val mediaKey: String = episode.toString(),
        val completion: PetEvent? = null,
        val completionEpisode: Int = episode,
        val hasNext: Boolean = false,
        val endingConfirmed: Boolean = false,
        val userPaused: Boolean = !playing && !buffering,
        val ended: Boolean = false,
        val error: Boolean = false,
        val episodeWatchedMs: Long = 0L,
        val speechEnabled: Boolean = true,
        val quietWatching: Boolean = false,
        val character: String = "claude",
        val muted: Boolean = false,
        val sourceName: String = "",
        val qualityName: String = "",
        /** Включённые субтитры (имя дорожки); пусто — выключены. */
        val subtitlesName: String = "",
        val seekSerial: Long = 0,
        val seekBackwards: Boolean = false,
        val activity: String = "normal",
    ) {
        val leftMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
        val nearEnd: Boolean get() = durationMs > 0 && leftMs in 1..END_WINDOW_MS
        val lastAired: Boolean get() = !hasNext && episodesAvailable > 0
    }

    data class Decision(val say: String?, val mood: PetMood, val ask: PetAsk? = null, val holdMs: Long = 0)

    /** Uses the same reaction timer as playback; no second animation controller. */
    fun reactToRating(score: Int, character: String, now: Long): Decision? {
        val reaction = petRatingReaction(score) ?: return null
        val result = Decision(reaction.text, reaction.mood)
        active = result
        activePriority = 2
        activeUntil = now + PetDef.of(character).clip(petActionFor(reaction.mood)).durations.sum().coerceIn(1_000L, 15_000L)
        speechUntil = now + PET_SAY_MS
        lastChatterAt = now
        return result
    }

    fun decide(scene: Scene): Decision {
        val drizz = scene.character == "drizz"
        val key = "${scene.character}:${scene.mediaKey}"
        if (observedKey != key) {
            observedKey = key
            previousDrizz = null
            bufferSince = null
            noticeableBuffer = false
            lastRestAt = scene.now
            fidgetNumber = 0
        }
        val previous = previousDrizz
        if (drizz) previousDrizz = scene
        if (drizz && scene.buffering && !scene.error && scene.completion == null && !scene.userPaused) {
            val since = bufferSince ?: scene.now.also { bufferSince = it }
            // A short buffering pulse is not a manual pause or a new reaction.
            if (scene.now - since < 2_000L) return Decision(null, PetMood.IDLE)
            noticeableBuffer = true
        } else bufferSince = null
        val mode = when {
            scene.error -> "error"
            scene.buffering -> "buffer"
            scene.ended -> "ended"
            scene.userPaused -> if (scene.pausedForMs >= SLEEP_AFTER_MS) "sleep" else "pause"
            scene.playing -> if (scene.quietWatching) "quiet" else "play"
            else -> "loading"
        }
        if (key != episodeKey) {
            episodeKey = key
            saidFor = saidByEpisode.getOrPut(episodeKey) { HashSet() }
            while (saidByEpisode.size > 64) saidByEpisode.remove(saidByEpisode.keys.first())
            initialVoice = null
            active = null
            speechUntil = 0
            lastFidgetAt = scene.now
        }
        if (mode != previousMode) {
            active = null
            speechUntil = 0
            if (mode == "pause") saidFor.remove("pausecard")
            if (mode == "sleep") saidFor.remove("sleep")
            if (previousMode == "pause" || previousMode == "sleep") saidFor.remove("resume")
            previousMode = mode
        }
        val base = when (mode) {
            "error", "buffer", "loading" -> PetMood.WAITING
            "sleep" -> PetMood.SLEEPY
            "pause" -> PetMood.PAUSED
            else -> if (scene.sessionMs >= TIRED_SESSION_MS || (!drizz && scene.streak >= TIRED_STREAK)) PetMood.WAITING else PetMood.IDLE
        }
        val action = if (drizz && (scene.playing || scene.userPaused) && !scene.buffering && !scene.error && !scene.ended &&
            (!scene.userPaused || previous?.userPaused == true)) {
            val recovered = noticeableBuffer
            noticeableBuffer = false
            val changed = when {
                recovered -> PetMood.HAPPY
                previous == null -> null
                previous.userPaused && scene.playing -> PetMood.GREETING
                scene.seekSerial != previous.seekSerial -> if (scene.seekBackwards) PetMood.SURPRISED else PetMood.EXCITED
                scene.voiceName != previous.voiceName && previous.voiceName.isNotBlank() -> PetMood.LISTEN
                scene.sourceName != previous.sourceName && previous.sourceName.isNotBlank() -> PetMood.LISTEN
                scene.qualityName != previous.qualityName && previous.qualityName.isNotBlank() -> PetMood.LISTEN
                scene.muted != previous.muted -> if (scene.muted) PetMood.LISTEN else PetMood.HAPPY
                scene.subtitlesName != previous.subtitlesName -> PetMood.LISTEN
                scene.speed != previous.speed -> if (scene.speed >= 1.5f) PetMood.EXCITED else PetMood.LISTEN
                else -> null
            }
            if (changed != null && (recovered || previous?.userPaused == true || scene.now - lastActionAt >= 3_000L)) {
                lastActionAt = scene.now
                val words = if (scene.speechEnabled && scene.now - lastActionSpeechAt >= drizzActionSpeechInterval(scene.activity)) when {
                    recovered -> words("Поток восстановлен. Продолжаем!", "Снова играет. Смотрим дальше", "Загрузка позади. Я рядом")
                    previous?.userPaused == true && scene.playing -> picker.pickFrom(PetPhrases.RETURN + "Продолжаем. Я смотрю с тобой")
                    previous == null -> null
                    scene.seekSerial != previous.seekSerial -> if (scene.seekBackwards)
                        words("Вернулись назад. Посмотрим ещё раз", "Ещё разок?", "Отмотали назад. Я смотрю внимательно")
                        else words("Перемотали вперёд", "Перематываем вперёд", "Мы уже дальше. Смотрю с тобой")
                    scene.voiceName != previous.voiceName -> words("Теперь слушаем: ${scene.voiceName}", "Слушаю: ${scene.voiceName}", "Новая озвучка: ${scene.voiceName}")
                    scene.sourceName != previous.sourceName -> words("Источник сменился. Проверяем поток", "Переключили источник. Ждём видео", "Другой источник. Посмотрим, как пойдёт")
                    scene.qualityName != previous.qualityName -> words("Выбрано качество: ${scene.qualityName}", "Теперь качество: ${scene.qualityName}")
                    scene.muted != previous.muted -> if (scene.muted)
                        words("Звук выключен. Посижу тихо", "Звук выключен. Смотрим без звука", "Звук выключен. Я рядом")
                        else words("Звук снова включён", "Снова слышно. Прислушаюсь", "Вернули звук. Продолжаем")
                    scene.subtitlesName != previous.subtitlesName -> if (scene.subtitlesName.isBlank())
                        words("Субтитры убрали. Слушаем", "Без субтитров. Смотрим глазами")
                        else words("Субтитры включены. Читаем вместе", "Субтитры: ${scene.subtitlesName}. Я тоже читаю")
                    scene.speed != previous.speed -> {
                        val speed = scene.speed.toString().removeSuffix(".0").replace('.', ',') + "×"
                        when {
                            scene.speed < 1f -> words("Смотрим на скорости $speed", "$speed — помедленнее? Мне так даже спокойнее")
                            scene.speed > 1f -> words("Смотрим на скорости $speed", "$speed — смотрим быстрее")
                            else -> words("Смотрим на скорости $speed", "$speed — обычный темп. Устраиваюсь")
                        }
                    }
                    else -> null
                } else null
                Decision(words, if (scene.userPaused) base else changed)
            } else null
        } else null
        val preempt = action != null && activePriority <= 2
        if (scene.completion == null && active != null && scene.now < activeUntil && !preempt) {
            return active!!.copy(say = null, ask = null)
        }
        if (!drizz && scene.completion == null && scene.now < speechUntil) return Decision(null, base)
        val result = if (drizz && scene.error) Decision(once("error", scene) { "Видео пока не загрузилось" }, PetMood.WAITING)
            else if (action != null && scene.completion == null) action else choose(scene)
        if (action != null && result === action && result.say != null) {
            lastActionSpeechAt = scene.now
            lastChatterAt = scene.now
        }
        val transient = result.mood in setOf(PetMood.HAPPY, PetMood.GREETING, PetMood.CELEBRATING,
            PetMood.SURPRISED, PetMood.EXCITED, PetMood.LISTEN, PetMood.FIDGET)
        if (transient || result.holdMs > 0) {
            active = result
            activePriority = if (scene.completion != null) 3 else if (action != null) 2 else 0
            activeUntil = scene.now + (result.holdMs.takeIf { it > 0 }
                ?: PetDef.of(scene.character).clip(petActionFor(result.mood)).durations.sum().coerceIn(1_000L, 15_000L))
        } else active = null
        if (result.say != null) speechUntil = scene.now + PET_SAY_MS
        return if (scene.speechEnabled) result else result.copy(say = null, ask = null)
    }

    private fun choose(scene: Scene): Decision {
        if (initialVoice == null && scene.voiceName.isNotBlank()) initialVoice = scene.voiceName
        // Часы «поёрзать» стартуют с первого взгляда, а не с начала эпохи.
        if (lastFidgetAt == Long.MIN_VALUE / 2) lastFidgetAt = scene.now
        // Карточка паузы показывается на каждую паузу заново.
        if (scene.playing) saidFor.remove("pausecard")

        // 1. Важное — вне очереди и без кулдауна.
        if (scene.completion != null) {
            val seasonDone = scene.completion == PetEvent.SEASON_DONE
            val text = once("done:${scene.completionEpisode}", scene) { endingText(scene) }
            return Decision(text, if (text != null) PetPhrases.moodOf(scene.completion) else PetMood.IDLE,
                ask = if (text != null && seasonDone && scene.completionEpisode == scene.episode) PetAsk.RATE_SEASON else null)
        }
        if (scene.error) return Decision(once("error", scene) { "Видео пока не загрузилось" }, PetMood.WAITING)
        if (scene.buffering) return Decision(once("buffer", scene) { "Видео загружается…" }, PetMood.WAITING)
        if (scene.ended) return Decision(null, PetMood.IDLE)
        if (scene.durationMs > 0 && scene.positionMs >= scene.durationMs) return Decision(null, PetMood.IDLE)
        if (!scene.playing && !scene.userPaused) return Decision(null, PetMood.WAITING)
        if (scene.sourceSwitched && scene.character != "drizz") {
            once("source", scene) { "Переключил источник, продолжаем" }?.let { return Decision(it, PetMood.HAPPY) }
        }
        if (scene.userPaused && scene.pausedForMs >= SLEEP_AFTER_MS) {
            return Decision(once("sleep", scene) { picker.pick(PetEvent.IDLE_LONG) }, PetMood.SLEEPY)
        }
        if (scene.userPaused) {
            val card = if (scene.character == "drizz" || scene.pausedForMs >= PAUSE_CARD_AFTER_MS) once("pausecard", scene) {
                if (scene.character == "drizz") picker.pickFrom(DrizzPhrases.pauses) else pauseCard(scene).ifBlank { PetDef.of(scene.character).personality.pause }
            } else null
            return Decision(card, PetMood.PAUSED)
        }
        if (scene.resumedFromMs > 0) {
            once("resume", scene) { "Продолжаем с ${clock(scene.resumedFromMs)}" }?.let { return Decision(it, PetMood.GREETING) }
        }

        // 2. Начало серии — приветствие с памятью: пересмотр, долгий перерыв,
        // продолжение недосмотренной серии — или просто «поехали».
        if (scene.playing && scene.positionMs in 1..(GREETING_WINDOW_MS + scene.openedAtMs)) {
            once("greeting", scene) { greetingText(scene) }?.let { return Decision(it, PetMood.GREETING) }
        }

        // 3. Реакции на действия — редкие, чаще без текста.
        if (scene.character == "drizz") {
            if (scene.endingConfirmed && scene.hasNext) return Decision(null, PetMood.PAUSED, PetAsk.SKIP_ENDING)
            val tired = scene.sessionMs >= TIRED_SESSION_MS
            if (!scene.quietWatching && scene.now >= speechUntil && scene.now - lastChatterAt >= chatterCooldownMs) {
                chatter(scene)?.let { return Decision(it, if (tired) PetMood.WAITING else PetMood.IDLE) }
            }
            // Existing sit/look clips, not a fabricated stretching animation.
            if (scene.now - lastRestAt >= 10 * 60_000L) {
                lastRestAt = scene.now
                lastFidgetAt = scene.now
                return Decision(null, PetMood.PAUSED, holdMs = 5_000L)
            }
            if (!tired && scene.now - lastFidgetAt >= drizzMotionInterval(fidgetNumber, scene.activity)) {
                lastFidgetAt = scene.now
                fidgetNumber++
                return Decision(null, PetMood.FIDGET)
            }
            return Decision(null, if (tired) PetMood.WAITING else PetMood.IDLE)
        }
        if (scene.quietWatching && scene.playing) {
            return if (scene.endingConfirmed && scene.hasNext) Decision(null, PetMood.PAUSED, PetAsk.SKIP_ENDING)
                else Decision(null, PetMood.IDLE)
        }
        if (scene.rewinds >= REWIND_BURST) {
            once("rewind", scene) { "Ещё разок?" }?.let { return Decision(it, PetMood.SURPRISED) }
        }
        // Реагируем на перемотку, но без таймингов не называем её пропуском OP/ED.
        if (scene.forwards >= 1 && scene.positionMs in 1..OPENING_MS) {
            once("opening", scene) { "Перемотали вперёд" }?.let { return Decision(it, PetMood.EXCITED) }
        }
        if (scene.forwards >= 1 && scene.durationMs > 0 && scene.leftMs in 1..CREDITS_MS) {
            once("credits", scene) { "Перемотали ближе к концу" }?.let { return Decision(it, PetMood.EXCITED) }
        }
        if (scene.forwards >= FORWARD_BURST) {
            once("forward", scene) { "Перематываем вперёд" }?.let { return Decision(it, PetMood.EXCITED) }
        }
        if (scene.speed >= FAST_SPEED) {
            once("fast", scene) { "Смотрим быстрее" }?.let { return Decision(it, PetMood.EXCITED) }
        }
        if (scene.speed in 0.01f..SLOW_SPEED) {
            once("slow", scene) { "Помедленнее? Мне так даже спокойнее" }?.let { return Decision(it, PetMood.LISTEN) }
        }
        if (scene.voiceName.isNotBlank() && scene.voiceName != initialVoice) {
            once("voice:${scene.voiceName}", scene) { "Слушаю: ${scene.voiceName}" }?.let {
                return Decision(it, PetMood.LISTEN)
            }
        }
        if (scene.fullscreen) {
            once("fullscreen", scene) { "Во весь экран — вот так правильно" }?.let {
                return Decision(it, PetMood.EXCITED)
            }
        }
        if (scene.qualityForced) {
            once("quality", scene) { "Качество снижено из-за соединения" }?.let { return Decision(it, PetMood.WAITING) }
        }

        // Только настоящий диапазон ED и реально доступная следующая серия.
        if (scene.playing && scene.endingConfirmed && scene.hasNext) {
            return Decision(if (scene.nearEnd) nearEndText(scene) else null, PetMood.PAUSED, ask = PetAsk.SKIP_ENDING)
        }
        if (scene.nearEnd) return Decision(null, PetMood.IDLE)

        // 6. Редкие наблюдения: одно на кулдаун, и только если есть что сказать.
        // Кулдаун проверяется ДО подбора фразы: иначе «один раз на серию» сгорал бы
        // впустую — отметка ставилась, а реплика не показывалась.
        val tired = scene.streak >= TIRED_STREAK || scene.sessionMs >= TIRED_SESSION_MS
        if (scene.now - lastChatterAt >= chatterCooldownMs * PetDef.of(scene.character).personality.speechMultiplier) {
            chatter(scene)?.let { return Decision(it, if (tired) PetMood.WAITING else PetMood.IDLE) }
        }
        // 7. Ничего не происходит — иногда поёрзать, без текста.
        if (!tired && scene.now - lastFidgetAt >= PetDef.of(scene.character).personality.fidgetMinutes * 60_000L) {
            lastFidgetAt = scene.now
            return Decision(null, PetMood.FIDGET)
        }
        return Decision(null, if (tired) PetMood.WAITING else PetMood.IDLE)
    }

    /** «После этой серии останется N серий» — один раз, и только когда общее известно. */
    private fun nearEndText(scene: Scene): String? {
        if (scene.episodesTotal <= 0) return null
        val left = (scene.episodesTotal - scene.episode).coerceAtLeast(0)
        return if (left > 0) once("left", scene) { "После этой серии останется $left ${episodeWord(left)}" } else null
    }

    /** Приветствие в начале серии — с памятью о прошлых просмотрах. */
    private fun greetingText(scene: Scene): String = when {
        scene.character == "drizz" && scene.rewatch -> picker.pick(PetEvent.REWATCH)
        scene.character == "drizz" && scene.daysSinceLastWatch >= 2 -> picker.pick(PetEvent.RETURN)
        scene.character == "drizz" && scene.openedAtMs < 60_000L -> picker.pick(PetEvent.EPISODE_START)
        scene.rewatch && scene.titleName.isNotBlank() -> "Снова «${scene.titleName}»? Устраиваюсь поудобнее"
        scene.daysSinceLastWatch >= 2 && scene.titleName.isNotBlank() ->
            "Мы не смотрели «${scene.titleName}» ${scene.daysSinceLastWatch} ${dayWord(scene.daysSinceLastWatch)}. Продолжаем!"
        // Первый заход: истории нет вовсе — ни отметок, ни прошлого просмотра.
        scene.titleName.isNotBlank() && scene.watchedEpisodes == 0 && scene.daysSinceLastWatch < 0 &&
            scene.episode <= 1 -> "Первая серия «${scene.titleName}». Посмотрим, что это"
        scene.openedAtMs >= 60_000L && scene.durationMs > scene.openedAtMs ->
            "Осталось ${(scene.durationMs - scene.openedAtMs) / 60_000} мин с прошлого просмотра"
        else -> picker.pick(PetEvent.EPISODE_START)
    }

    /** Что сказать, когда серия доиграла: различаем конец серии, «догнали» и финал сезона. */
    private fun endingText(scene: Scene): String {
        val event = scene.completion ?: PetEvent.EPISODE_DONE
        if (scene.character == "drizz") return when (event) {
            PetEvent.SEASON_DONE -> picker.pickFrom(PetPhrases.SEASON_DONE + "Ещё один сезон в копилке")
            PetEvent.CAUGHT_UP -> picker.pick(PetEvent.CAUGHT_UP)
            PetEvent.EPISODE_DONE -> picker.pickFrom(
                (if (scene.hasNext) PetPhrases.EPISODE_DONE else listOf("Серия досмотрена.", "Можно немного отдохнуть.")) +
                    "Серия ${scene.completionEpisode} досмотрена")
            else -> picker.pick(event)
        }
        return when (event) {
            PetEvent.SEASON_DONE -> {
                val spent = petWatchTimeLabel(scene.titleWatchedMs)
                if (spent != null && scene.titleName.isNotBlank()) {
                    "Сезон досмотрен. Время за «${scene.titleName}»: $spent"
                } else {
                    "Ещё один сезон в копилке"
                }
            }
            PetEvent.CAUGHT_UP -> "А следующую нам пока не дали. Ждём новую серию!"
            PetEvent.EPISODE_DONE ->
                "Серия ${scene.completionEpisode} досмотрена"
            else -> picker.pick(event)
        }
    }

    /** «Просмотрено 7/12 · 2 ч 41 мин» — только из того, что известно. */
    private fun pauseCard(scene: Scene): String = buildList {
        // Пустую историю не показываем: «Просмотрено 0/12» — не информация.
        if (scene.watchedEpisodes > 0 && scene.episodesTotal > 0) add("Просмотрено ${scene.watchedEpisodes}/${scene.episodesTotal}")
        else if (scene.watchedEpisodes > 0) add("Просмотрено ${scene.watchedEpisodes}")
        petWatchTimeLabel(scene.titleWatchedMs)?.let { add(it) }
    }.joinToString(" · ")

    /** Наблюдение по ходу серии — самое уместное из возможных, иначе молчание. */
    private fun chatter(scene: Scene): String? {
        val marathon = petWatchTimeLabel(scene.sessionMs)
        return when {
            scene.streak >= TIRED_STREAK && !saidFor.contains("streak") ->
                once("streak", scene) { "Это уже ${scene.streak} серия подряд" }
            scene.sessionMs >= TIRED_SESSION_MS && marathon != null && !saidFor.contains("marathon") ->
                once("marathon", scene) { "Мы уже смотрим $marathon" }
            scene.durationMs > 0 && scene.leftMs in 1..LEFT_HINT_MS && !saidFor.contains("leftTime") ->
                once("leftTime", scene) { "Осталось ${scene.leftMs / 60_000} мин до конца серии" }
            scene.episodeWatchedMs >= WATCHING_HINT_MS && !saidFor.contains("watching") ->
                once("watching", scene) { "Смотришь уже ${scene.episodeWatchedMs / 60_000} мин" }
            scene.durationMs > 0 && scene.positionMs >= scene.durationMs / 2 && !saidFor.contains("half") ->
                once("half", scene) { "Половина серии позади" }
            scene.hourOfDay in NIGHT_HOURS && !saidFor.contains("night") ->
                once("night", scene) { "Уже ${scene.hourOfDay}:00. Ещё серия — и спать?" }
            ratingRemark(scene) != null && !saidFor.contains("rating") ->
                once("rating", scene) { ratingRemark(scene).orEmpty() }
            genreStreak(scene) != null && !saidFor.contains("genreStreak") ->
                once("genreStreak", scene) { genreStreak(scene).orEmpty() }
            genreRemark(scene) != null && !saidFor.contains("genre") ->
                once("genre", scene) { genreRemark(scene).orEmpty() }
            else -> null
        }
    }

    /**
     * Что сказать про оценку каталога. Шкалы у источников разные (5 и 10 баллов),
     * поэтому сравниваем долю, а показываем число как есть.
     */
    private fun ratingRemark(scene: Scene): String? {
        if (scene.rating <= 0.0 || scene.ratingMax <= 0.0) return null
        if (scene.character == "drizz") return petRatingLines(0, scene.rating, scene.ratingMax).firstOrNull()
        val share = scene.rating / scene.ratingMax
        val grade = "%.1f".format(scene.rating)
        return when {
            share >= 0.9 -> "У этого оценка $grade. Надеюсь, заслуженно"
            share >= 0.78 -> "Оценка $grade — обычно это хороший знак"
            share <= 0.5 -> "Оценка тут $grade. Смотрим всё равно, мы не гордые"
            else -> null
        }
    }

    /**
     * Память жанров: тот же заметный жанр у двух-трёх последних тайтлов подряд —
     * «третий исекай подряд». Считается по строкам жанров из истории, без сети.
     */
    private fun genreStreak(scene: Scene): String? {
        val streak = genreStreakOf(scene.genres, scene.recentGenres) ?: return null
        val (genre, count) = streak
        return "Уже ${countWord(count)} $genre подряд. Тема на этой неделе?"
    }

    private fun countWord(n: Int) = when (n) { 2 -> "второй"; 3 -> "третий"; 4 -> "четвёртый"; else -> "$n-й" }

    /** Короткая реплика про жанр — только про самые узнаваемые. */
    private fun genreRemark(scene: Scene): String? {
        val genres = scene.genres.lowercase()
        return when {
            genres.contains("исэкай") || genres.contains("исекай") -> "Опять другой мир. Ну ладно, я не против"
            genres.contains("ужас") -> "Если будет страшно, я прячусь за окно плеера"
            genres.contains("спорт") -> "Болею за наших"
            genres.contains("романтик") -> "Обещай не плакать. Я — не обещаю"
            genres.contains("детектив") -> "Детектив. Буду внимательнее"
            genres.contains("меха") -> "Роботы! Наконец-то"
            else -> null
        }
    }

    /** Реплика, которая звучит один раз на серию: перемотка её не повторяет. */
    private inline fun once(tag: String, scene: Scene, text: () -> String): String? {
        if (!saidFor.add(tag)) return null
        lastChatterAt = scene.now
        return text()
    }

    private fun words(vararg phrases: String): String = picker.pickFrom(phrases.toList())

    companion object {
        /** Умолчание: не чаще одной необязательной реплики в 5 минут. */
        const val CHATTER_COOLDOWN_MS = 5 * 60_000L

        /** Первые секунды серии, когда уместно поздороваться. */
        const val GREETING_WINDOW_MS = 90_000L

        /** Хвост серии, в котором питомец готовится к следующей. */
        const val END_WINDOW_MS = 90_000L

        /** За сколько до конца питомец предлагает пропустить эндинг. */
        const val ENDING_ASK_MS = 2 * 60_000L

        /** «Осталось N минут» — когда до конца меньше этого. */
        const val LEFT_HINT_MS = 10 * 60_000L

        /** «Смотришь уже N минут» — не раньше этого места серии. */
        const val WATCHING_HINT_MS = 40 * 60_000L

        /** С какой паузы засыпает. */
        const val SLEEP_AFTER_MS = 5 * 60_000L

        /** С какой паузы показывает карточку «просмотрено / время». */
        const val PAUSE_CARD_AFTER_MS = 3_000L

        /** Сколько серий подряд считается марафоном. */
        const val TIRED_STREAK = 4

        /** И сколько времени подряд. */
        const val TIRED_SESSION_MS = 2 * 60 * 60_000L

        /** Сколько перемоток назад / вперёд за минуту — повод для реакции. */
        const val REWIND_BURST = 3
        const val FORWARD_BURST = 3

        /** Скорость, с которой питомец «не успевает моргать». */
        const val FAST_SPEED = 1.75f

        /** И скорость, на которой он расслабляется. */
        const val SLOW_SPEED = 0.85f

        /** Начало серии (опенинг) и хвост (титры) — для реакции на перемотку вперёд. */
        const val OPENING_MS = 3 * 60_000L
        const val CREDITS_MS = 2 * 60_000L

        /** Часы, в которые уместно намекнуть на сон. */
        val NIGHT_HOURS = 0..4

        /** Поёрзать: не чаще раза в столько, и столько длится. */
        const val FIDGET_EVERY_MS = 4 * 60_000L
        const val FIDGET_MS = 5_000L

        fun clock(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        fun episodeWord(count: Int): String = plural(count, "серия", "серии", "серий")
        fun dayWord(count: Int): String = plural(count, "день", "дня", "дней")

        private fun plural(count: Int, one: String, few: String, many: String): String {
            val mod100 = count % 100
            val mod10 = count % 10
            return when {
                mod100 in 11..14 -> many
                mod10 == 1 -> one
                mod10 in 2..4 -> few
                else -> many
            }
        }
    }
}

/** Deterministic variation: no random recomposition or extra timers. */
internal fun drizzMotionInterval(number: Int, activity: String = "normal"): Long {
    val normal = 30_000L + (number.toLong() * 17_003L % 40_001L)
    return when (activity) { "calm" -> normal * 3; "active" -> normal / 2; else -> normal }
}

internal fun drizzActionSpeechInterval(activity: String): Long = when (activity) {
    "calm" -> 15_000L
    "active" -> 4_000L
    else -> 8_000L
}

/**
 * Жанр, повторяющийся у текущего тайтла и предыдущих подряд: («исекай», 3) — текущий
 * плюс два предыдущих. Смотрим только заметные жанры (не «сёнен»/«приключения»,
 * которые есть почти у всего), и минимум два подряд включая текущий.
 */
fun genreStreakOf(currentGenres: String, recentGenres: List<String>): Pair<String, Int>? {
    fun set(s: String) = s.lowercase().replace('ё', 'е').split(',', ';').map { it.trim() }.filter { it.isNotBlank() }.toSet()
    val current = set(currentGenres)
    if (current.isEmpty() || recentGenres.isEmpty()) return null
    for (genre in STREAK_GENRES) {
        if (genre !in current) continue
        var count = 1
        for (previous in recentGenres) {
            if (genre in set(previous)) count++ else break
        }
        if (count >= 2) return genre to count
    }
    return null
}

private val STREAK_GENRES = listOf("исекай", "исэкай", "романтика", "ужасы", "спорт", "детектив", "меха", "психологическое", "гарем", "повседневность")

/** О чём питомец просит поверх реплики. Пока просьба одна — оценить досмотренное. */
enum class PetAsk {
    /** Подтверждено завершение сезона: предлагает поставить оценку. */
    RATE_SEASON,
    /** Идут титры, следующая серия есть: «мотаем к следующей?» — реплика-кнопка. */
    SKIP_ENDING,
}

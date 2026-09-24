package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.model.TitleComment
import com.aniblaze.aggregator.source.commentIdentity
import java.util.concurrent.atomic.AtomicLong

/**
 * Симулятор живого чата: кто, когда и что напишет.
 *
 * Устроен по образцу [DanmakuSession], и не случайно — там уже выяснено дорогой
 * ценой, что состояние показа НЕЛЬЗЯ держать в композиции: любой пересбор экрана
 * или дрожание длины HLS пересоздавали его и давали качели «всё повторяется ↔
 * ничего нет». Здесь всё то же самое вынесено наружу:
 *
 *  • объект создаётся ОДИН раз на серию ([episodeKey]) и живёт, пока её не сменили;
 *  • часы — накопленное время просмотра [watchedMs], которое двигает только внешний
 *    тик при играющем видео: пауза просто не зовёт [accumulate], и чат замирает;
 *  • генератор случайных чисел ОДИН и НИКОГДА не отматывается назад. Отсюда
 *    бесплатно следует требование «при перемотке назад не повторяй ту же
 *    последовательность»: вернуться к прежнему состоянию просто нечему.
 *
 * ЧТО ЗДЕСЬ ПРАВДА, А ЧТО СИМУЛЯЦИЯ. Реакции привязаны к происходящему настолько,
 * насколько это вообще измеримо:
 *
 *  • опенинг и эндинг — ТОЧНО, по интервалам AniSkip;
 *  • драка, резкий поворот, тихая тёмная сцена — ПО КАДРУ, через [SceneEnergy]
 *    (движение, яркость, частота смены плана);
 *  • смешное и предкульминационный подъём — ИЗ КАДРА НЕ ИЗВЛЕКАЮТСЯ ничем, что есть
 *    у приложения, и раскладываются по серии детерминированной волной ([arcMood]).
 *
 * Тексты берутся из двух источников: безопасный для текущей серии срез настоящего
 * обсуждения и сгенерированные реплики персон из [ChatLines]. Будущие серии в чат
 * не входят; остальные скрываются только по метке источника или при явных признаках
 * раскрытия сюжета в самом тексте.
 */
internal class ChatEngine(
    val episodeKey: String,
    /** Сколько сырых комментариев легло в основу — по нему [ChatSessions] узнаёт дозагрузку. */
    sourceSize: Int,
    /**
     * Заявленное число зрителей в зале — и оно, и [intensity] ПЕРЕМЕННЫЕ.
     *
     * Обе были полями сессии, и живой журнал показал, во что это обходится: пока
     * ползунок «зрителей» тащат от 150 к 320, обе настройки меняются на каждом
     * пикселе, и чат пересоздавался ВОСЕМЬДЕСЯТ ПЯТЬ РАЗ ПОДРЯД — лента обнулялась
     * быстрее, чем успевала нарисоваться. Ни та, ни другая настройка не меняет ни
     * очередь, ни отбор: обе влияют только на темп и на подпись в шапке.
     */
    var viewers: Int,
    private var intensity: ChatIntensity,
    /**
     * «Всегда тихо»: чат идёт ровным темпом и НЕ разгоняется ни на драке, ни на
     * повороте — ни ускорения, ни всплесков. Настроение при этом по-прежнему
     * определяется и по-прежнему решает, ЧТО написать, — меняется только то, КАК
     * ЧАСТО. Кому чат нужен фоном, скачки мешают больше всего.
     */
    private var alwaysQuiet: Boolean,
    real: List<TitleComment>,
    seed: Long,
    speed: Float = 1f,
    /** Серия, которую зритель смотрит сейчас. Нужна только для защиты превью. */
    private val currentEpisode: Int = 0,
) {
    val id: Long = IDS.incrementAndGet()
    private val shuffleSeed = seed
    var sourceSize: Int = sourceSize
        private set

    private var speedMultiplier = normalizeChatSpeed(speed)

    fun retune(value: ChatIntensity, hall: Int, quiet: Boolean, speed: Float = speedMultiplier) {
        val nextSpeed = normalizeChatSpeed(speed)
        if (nextSpeed != speedMultiplier) {
            // Rescale only the remaining message delay. Keep queue, random state,
            // mood clock and already shown messages intact; do not wait for the old gap.
            val remaining = (dueAtMs - watchedMs).coerceAtLeast(0)
            dueAtMs = watchedMs + (remaining * speedMultiplier.toDouble() / nextSpeed).toLong()
            speedMultiplier = nextSpeed
        }
        intensity = value
        viewers = hall.coerceAtLeast(1)
        if (quiet && !alwaysQuiet) burstLeft = 0
        alwaysQuiet = quiet
        if (quiet || speedMultiplier < 1f) {
            dueAtMs = maxOf(dueAtMs, lastIssuedAtMs + lastBatchTexts.sumOf { chatReadingTimeMs(it, speedMultiplier) })
        }
    }

    private val random = java.util.Random(seed)

    /** Настоящие комментарии сезона; дозагрузка дописывает сюда без сброса ленты. */
    private val realQueue = ArrayList<TitleComment>()
    private val knownReal = HashSet<String>()
    private var realNext = 0
    private var realOnly = false

    init {
        mergeReal(real, sourceSize)
    }

    fun setRealOnly(enabled: Boolean) {
        realOnly = enabled
    }

    /** Очередь настоящих реплик держит порядок кандидатов (по голосам), а не тасуется. */
    var popularFirst: Boolean = false
    /** Очередь набрана строго по серии (см. ChatSessions.obtain). */
    var episodeOnly: Boolean = false

    /**
     * Пороги выделения, посчитанные по пулу ЭТОЙ сессии: «заметная» — верхняя
     * четверть по голосам, «топ» — верхние 5 %. Абсолютные числа не годятся:
     * у нишевого тайтла семь голосов — это много, у Наруто — ничто.
     */
    private var notableVotes = Int.MAX_VALUE
    private var topVotes = Int.MAX_VALUE

    private fun recomputeVoteTiers() {
        val votes = realQueue.map { it.votes }.filter { it > 0 }.sorted()
        if (votes.size < MIN_POOL_FOR_TIERS) { notableVotes = Int.MAX_VALUE; topVotes = Int.MAX_VALUE; return }
        notableVotes = maxOf(MIN_NOTABLE_VOTES, votes[(votes.size * 0.75).toInt().coerceAtMost(votes.size - 1)])
        topVotes = maxOf(notableVotes + 1, votes[(votes.size * 0.95).toInt().coerceAtMost(votes.size - 1)])
    }

    internal fun voteTierOf(comment: TitleComment): ChatVoteTier = when {
        comment.votes >= topVotes -> ChatVoteTier.TOP
        comment.votes >= notableVotes -> ChatVoteTier.NOTABLE
        else -> ChatVoteTier.NONE
    }

    /** Сливает догруженный сезонный срез, не повторяя и не переигрывая уже показанное. */
    fun mergeReal(candidates: List<TitleComment>, sourceCount: Int) {
        sourceSize = sourceCount
        val room = (CHAT_REAL_LIMIT - realQueue.size).coerceAtLeast(0)
        val additions = candidates.asSequence()
            .filter { commentIdentity(it) !in knownReal }
            .take(room)
            .onEach { knownReal += commentIdentity(it) }
            .toMutableList()
        // «Сначала популярные»: кандидаты уже по убыванию голосов — так и в очередь.
        // Тасуются БЛОКИ «запись + её ответы», а не записи: ветка не должна рваться.
        if (!popularFirst) {
            val blocks = ArrayList<MutableList<TitleComment>>()
            for (c in additions) {
                if (c.parentId != 0L && blocks.isNotEmpty()) blocks.last() += c else blocks += mutableListOf(c)
            }
            blocks.shuffle(java.util.Random(shuffleSeed xor sourceCount.toLong() xor 0x5DEECE66DL))
            additions.clear()
            blocks.forEach { additions += it }
        }
        realQueue += additions
        recomputeVoteTiers()
    }

    /** Часы просмотра: двигает только [accumulate]. */
    private var watchedMs = 0L

    /** Когда пора выпустить следующее сообщение (в часах [watchedMs]). */
    private var dueAtMs = (FIRST_MESSAGE_MS / speedMultiplier).toLong()

    /** Сколько сообщений подряд ещё идут «очередью» — всплеск на резком моменте. */
    private var burstLeft = 0

    /** Настроение, которое сейчас держится, и с какого момента часов. */
    private var mood: SceneMood = SceneMood.CALM
    private var moodSince = 0L

    /**
     * Настроение ИЗМЕРЕНО, а не угадано расписанием.
     *
     * Нужно интерфейсу: подпись «ВЕСЕЛО» над чатом — это утверждение о том, что
     * происходит на экране, и его нельзя показывать, когда оно взято из расписания.
     */
    private var moodMeasured = false

    /** Сколько подряд было спокойно — по этому опознаётся резкий поворот. */
    private var calmForMs = 0L

    /** Выдержки признаков: одиночный всплеск ничего не решает. */
    private var fightForMs = 0L
    private var darkForMs = 0L
    private var lastBurstAt = Long.MIN_VALUE / 2

    /**
     * Номер перемотки. Входит в расчёт волны настроений, поэтому после возврата
     * назад те же секунды дают ДРУГОЕ расписание, а не повтор прежнего.
     */
    private var seekEpoch = 0

    /** Ники последних показанных сообщений — из них выбирается адресат ответа. */
    private val recentNicks = ArrayDeque<String>()

    /** Тексты последних сообщений — защита от «чат пишет одно и то же». */
    private val recentTexts = ArrayDeque<String>()

    /** Последний новичок с вопросом: на него отвечают охотнее, чем на прочих. */
    private var pendingQuestion: String? = null

    private var issued = 0L
    private var lastIssuedAtMs = 0L
    private var lastBatchTexts: List<String> = emptyList()

    val moodNow: SceneMood get() = mood

    /** true — настроение измерено по кадру или по AniSkip, а не взято из расписания. */
    val moodProven: Boolean get() = moodMeasured
    val realUsable: Int get() = realQueue.size
    val realShown: Int get() = realNext
    val messagesIssued: Long get() = issued

    /**
     * Сколько зрителей показывать в шапке.
     *
     * Не константа: зал дышит — на драке подтягиваются, на разговорах отваливаются.
     * Считается от часов, а не от системного времени, поэтому на паузе не меняется.
     */
    fun viewersNow(): Int {
        val wave = kotlin.math.sin(watchedMs / 47_000.0) * 0.04 + kotlin.math.sin(watchedMs / 13_000.0) * 0.015
        val moodLift = when (mood) {
            SceneMood.FIGHT, SceneMood.TWIST -> 0.09
            SceneMood.HYPE -> 0.05
            SceneMood.OPENING -> 0.03
            SceneMood.SAD -> -0.03
            else -> 0.0
        }
        return (viewers * (1.0 + wave + moodLift)).toInt().coerceAtLeast(1)
    }

    /** Тик просмотра: зовётся только пока видео играет. */
    fun accumulate(deltaMs: Long) {
        watchedMs += deltaMs
    }

    /**
     * Перемотка. Часы просмотра НЕ трогаются (они считают просмотренное, а не
     * позицию), но расписание настроений сдвигается: иначе возврат на минуту назад
     * повторил бы ту же реакцию слово в слово.
     */
    fun onSeek() {
        seekEpoch++
        calmForMs = 0L
        moodSince = watchedMs
        burstLeft = 0
        // Небольшая пауза после прыжка: живой чат тоже секунду соображает.
        val seekPause = watchedMs + (AFTER_SEEK_MS / speedMultiplier).toLong()
        dueAtMs = if (issued > 0 && (alwaysQuiet || speedMultiplier < 1f)) maxOf(dueAtMs, seekPause) else seekPause
    }

    /**
     * Очередная порция сообщений. Пустой список — ещё не время.
     *
     * Вызывается на каждом тике цикла показа; вся стоимость — арифметика и, изредка,
     * выбор строки из набора.
     */
    fun poll(
        positionMs: Long,
        lengthMs: Long,
        opening: OpeningRange?,
        ending: OpeningRange?,
        energy: SceneSample,
        tickMs: Long,
    ): List<ChatMessage> {
        updateMood(positionMs, lengthMs, opening, ending, energy, tickMs)
        if (watchedMs < dueAtMs) return emptyList()
        // В строгом режиме отсутствие настоящих реплик означает тишину, а не право
        // незаметно подставить шаблонную. Новая порция кэша позже продолжит очередь.
        if (realOnly && realNext >= realQueue.size) {
            dueAtMs = watchedMs + gapMs()
            return emptyList()
        }

        val out = ArrayList<ChatMessage>(2)
        // На резком моменте сообщения идут пачкой, а не по одному в такт: именно так
        // выглядит настоящий чат, когда что-то произошло.
        val count = if (burstLeft > 0 && speedMultiplier >= 1f) 1 + random.nextInt(2) else 1
        repeat(count) {
            if (realOnly && realNext >= realQueue.size) return@repeat
            out += compose()
            if (burstLeft > 0) burstLeft--
        }
        val readingTime = out.sumOf { chatReadingTimeMs(it.text, speedMultiplier) }
        lastIssuedAtMs = watchedMs
        lastBatchTexts = out.map { it.text }
        // Quiet mode is intended for reading; slow mode must not replace long
        // paragraphs before they can be read, even if the random gap is short.
        dueAtMs = watchedMs + maxOf(gapMs(), if (alwaysQuiet || speedMultiplier < 1f) readingTime else 0L)
        issued += out.size
        return out
    }

    // ---- настроение ----

    private fun updateMood(
        positionMs: Long,
        lengthMs: Long,
        opening: OpeningRange?,
        ending: OpeningRange?,
        energy: SceneSample,
        tickMs: Long,
    ) {
        // Выдержки признаков копятся ЗДЕСЬ, а решение принимает чистая функция.
        fightForMs = if (fightNow(energy, mood)) fightForMs + tickMs else 0L
        darkForMs = if (darkNow(energy, mood)) darkForMs + tickMs else 0L
        val measured = measuredMood(positionMs, opening, ending, energy, calmForMs, fightForMs, darkForMs)
        val next = measured ?: arcMood(positionMs, lengthMs, episodeKey, seekEpoch, energy)
        if (measured == null) calmForMs += tickMs else calmForMs = 0L
        if (next == mood) return
        // Выдержка: без неё настроение дёргалось бы на каждой склейке, и чат
        // выглядел бы припадочным. Резкие моменты выдержку игнорируют — они на то и
        // резкие.
        val sudden = next == SceneMood.TWIST || next == SceneMood.FIGHT
        if (!sudden && watchedMs - moodSince < MOOD_HOLD_MS) return
        mood = next
        moodMeasured = measured != null
        moodSince = watchedMs
        // Всплеск — только на «всегда живом» чате и только если предыдущий уже
        // отгремел: без этой отсечки череда входов в экшен копила очередь.
        if (sudden && !alwaysQuiet && watchedMs - lastBurstAt >= BURST_COOLDOWN_MS) {
            lastBurstAt = watchedMs
            burstLeft = BURST_MIN + random.nextInt(BURST_SPREAD)
            // Всплеск начинается сразу, а не по расписанию спокойной сцены.
            if (speedMultiplier >= 1f) dueAtMs = kotlin.math.min(dueAtMs, watchedMs + (REACTION_LAG_MS / speedMultiplier).toLong())
        }
    }

    // ---- темп ----

    private fun gapMs(): Long {
        // «Всегда тихо» снимает множитель настроения целиком: темп остаётся тем, что
        // задан плотностью, и на резких моментах ничего не меняется.
        val tempo = if (alwaysQuiet) 1f else mood.tempo
        val perMinute = (intensity.basePerMinute * tempo * viewerFactor())
            .coerceIn(MIN_PER_MINUTE, MAX_PER_MINUTE)
        val base = (60_000f / perMinute).toLong()
        val burst = if (burstLeft > 0) BURST_DIVISOR else 1
        val jitter = 0.55 + random.nextDouble() * 0.9
        return ((base / burst) * jitter / speedMultiplier).toLong().coerceAtLeast(MIN_GAP_MS)
    }

    /**
     * Как число зрителей влияет на темп.
     *
     * Логарифм, а не пропорция: в чате на тысячу человек сообщения идут НЕ в десять
     * раз чаще, чем в чате на сотню, — читаемая лента упирается в потолок задолго до
     * этого. Логарифм даёт заметную, но не абсурдную разницу.
     */
    private fun viewerFactor(): Float {
        val v = viewers.coerceAtLeast(1)
        return (kotlin.math.ln(1.0 + v) / kotlin.math.ln(1.0 + REFERENCE_VIEWERS)).toFloat()
    }

    // ---- сообщение ----

    private fun compose(): ChatMessage {
        // Ответ идёт сразу за своей записью: выдуманный зал ветку не разрывает.
        val next = realQueue.getOrNull(realNext)
        val continuesThread = next != null && next.parentId != 0L
        val useReal = next != null && (realOnly || continuesThread || random.nextFloat() < REAL_SHARE)
        return if (useReal) realMessage() else generatedMessage()
    }

    /** Настоящий комментарий обсуждения — с настоящим ником и аватаркой. */
    private fun realMessage(): ChatMessage {
        val source = realQueue[realNext++]
        val nick = source.author.ifBlank { "зритель" }
        remember(nick, source.message)
        return ChatMessage(
            id = ++messageIds,
            nick = nick,
            color = CHAT_NICK_COLORS[(nick.hashCode().mod(CHAT_NICK_COLORS.size))],
            badge = null,
            avatar = source.avatar,
            real = true,
            source = source.source,
            votes = source.votes,
            parentKey = if (source.parentId != 0L) threadKey(source.source, source.parentId) else "",
            threadKey = threadKey(source.source, source.id),
            voteTier = voteTierOf(source),
            replyTo = source.replyTo.ifBlank { null },
            text = source.message,
            mood = mood,
            potentialSpoiler = isPotentialChatSpoiler(source, currentEpisode),
        )
    }

    private fun generatedMessage(): ChatMessage {
        val viewerSeed = random.nextInt(viewers.coerceAtLeast(1)).toLong() + seekEpoch * 7919L
        val persona = personaOf(viewerSeed)
        val nick = ChatNicks.of(viewerSeed)

        // Ответ кому-то: чат оживает именно от того, что люди друг друга слышат.
        val answersQuestion = pendingQuestion != null && pendingQuestion != nick &&
            random.nextFloat() < ANSWER_SHARE
        val target = when {
            answersQuestion -> pendingQuestion
            recentNicks.size >= 2 && random.nextFloat() < REPLY_SHARE ->
                recentNicks.filter { it != nick }.randomOrNull(random)
            else -> null
        }
        if (answersQuestion) pendingQuestion = null

        // Ответные реплики идут через ТУ ЖЕ защиту от повторов, что и обычные.
        // Первая версия брала их напрямую, и тест поймал ровно это: набор ответов
        // короче прочих, и «тоже так подумал» всплывало дважды за двадцать сообщений.
        val text = when {
            // Ответ новичку берётся из ДВУХ наборов сразу: специального и общего.
            // Специальный набор узкий по смыслу, и в одиночку он давал повтор через
            // полтора десятка сообщений — а общее «плюсую» новичку подходит не хуже.
            answersQuestion -> fresh(ChatLines.toNewbie, ChatLines.replies)
            target != null -> fresh(emptyList(), ChatLines.replies)
            else -> phraseFor(persona)
        }
        if (persona == ChatPersona.NEWBIE && target == null) pendingQuestion = nick

        remember(nick, text)
        return ChatMessage(
            id = ++messageIds,
            nick = nick,
            color = CHAT_NICK_COLORS[(viewerSeed % CHAT_NICK_COLORS.size).toInt()],
            badge = persona.badge,
            avatar = "",
            real = false,
            replyTo = target,
            text = text,
            mood = mood,
        )
    }

    /**
     * Строка под настроение и персону.
     *
     * Персональный набор весит вдвое: из-за этого токсичный узнаётся токсичным, но
     * не превращается в одну и ту же шарманку из восьми фраз.
     */
    private fun phraseFor(persona: ChatPersona): String =
        fresh(ChatLines.personal(persona, mood), ChatLines.common(mood))

    /**
     * Строка, которой давно не было.
     *
     * Окно «недавнего» подстраивается под РАЗМЕР НАБОРА, а не берётся постоянным.
     * Здесь была ошибка, которую поймал тест: окно в семьдесят строк шире, чем весь
     * набор спокойной сцены, — все кандидаты оказывались «недавними», отбор сдавался
     * и возвращал первый попавшийся, то есть ровно повтор, от которого и защищался.
     * Две трети набора оставляют треть свободной всегда.
     */
    private fun fresh(personal: List<String>, common: List<String>): String {
        val available = personal.size + common.size
        val window = (available * 2 / 3).coerceIn(MIN_WINDOW, TEXT_MEMORY)
        val recent = if (recentTexts.size <= window) recentTexts else recentTexts.toList().takeLast(window)
        repeat(PHRASE_TRIES) {
            val fromPersonal = personal.isNotEmpty() && random.nextFloat() < PERSONAL_SHARE
            val candidate = pick(if (fromPersonal) personal else common)
            if (candidate !in recent) return candidate
        }
        // Перебор не помог — идём по набору подряд и берём первое свободное.
        val everything = personal + common
        return everything.firstOrNull { it !in recent } ?: pick(everything)
    }

    private fun pick(list: List<String>): String =
        if (list.isEmpty()) "..." else list[random.nextInt(list.size)]

    private fun <T> List<T>.randomOrNull(rnd: java.util.Random): T? =
        if (isEmpty()) null else this[rnd.nextInt(size)]

    private fun remember(nick: String, text: String) {
        recentNicks.addLast(nick)
        while (recentNicks.size > NICK_MEMORY) recentNicks.removeFirst()
        recentTexts.addLast(text)
        while (recentTexts.size > TEXT_MEMORY) recentTexts.removeFirst()
    }

    /** Персона зрителя: постоянна для одного и того же номера. */
    private fun personaOf(viewerSeed: Long): ChatPersona {
        val total = ChatPersona.entries.sumOf { it.share }
        var roll = ((viewerSeed * 2654435761L) ushr 17).mod(total.toLong()).toInt()
        for (persona in ChatPersona.entries) {
            roll -= persona.share
            if (roll < 0) return persona
        }
        return ChatPersona.CALM
    }

    private var messageIds = 0L

    private companion object {
        val IDS = AtomicLong(0L)

        /** Первое сообщение — почти сразу: пустой чат на старте выглядит сломанным. */
        const val FIRST_MESSAGE_MS = 1_500L
        const val AFTER_SEEK_MS = 1_200L

        /** Реакция на резкий момент приходит не мгновенно — люди сначала смотрят. */
        const val REACTION_LAG_MS = 700L

        /** Сколько настроение обязано продержаться, прежде чем сменится на плавное. */
        const val MOOD_HOLD_MS = 6_000L

        const val BURST_MIN = 4
        const val BURST_SPREAD = 6
        const val BURST_DIVISOR = 4

        /** Пока прошлый всплеск не отгремел, новый не начинается: иначе очередь копится. */
        const val BURST_COOLDOWN_MS = 20_000L

        const val MIN_GAP_MS = 220L
        const val MIN_PER_MINUTE = 3f
        const val MAX_PER_MINUTE = 160f
        const val REFERENCE_VIEWERS = 150.0

        /** Доля настоящих комментариев в ленте, пока они не кончились. */
        const val REAL_SHARE = 0.22f

        const val REPLY_SHARE = 0.16f
        const val ANSWER_SHARE = 0.45f
        const val PERSONAL_SHARE = 0.5f
        const val PHRASE_TRIES = 14

        const val NICK_MEMORY = 14
        const val TEXT_MEMORY = 70

        /** Меньше этого окно не сжимается даже у самого куцего набора. */
        const val MIN_WINDOW = 4
    }
}

/** Одно сообщение чата. */
internal data class ChatMessage(
    val id: Long,
    val nick: String,
    val color: Long,
    /** Подпись персоны («ЧИТАЛ МАНГУ»), либо null. */
    val badge: String?,
    /** Ссылка на аватарку — только у настоящих комментариев. */
    val avatar: String,
    /** true — это настоящая реплика из обсуждения тайтла, а не сгенерированная. */
    val real: Boolean,
    /** Кому отвечают («@ник»), либо null. */
    val replyTo: String?,
    val text: String,
    val mood: SceneMood,
    /** В боковой панели потенциальный спойлер раскрывается только явным кликом. */
    val potentialSpoiler: Boolean = false,
    /** Ключ источника настоящей реплики («anixart», «yummy»); пусто у выдуманных. */
    val source: String = "",
    /** Голоса настоящей реплики (лайки минус дизлайки); у выдуманных 0. */
    val votes: Int = 0,
    /** Ключ ветки (см. threadKey) записи-родителя; пусто — самостоятельная запись. */
    val parentKey: String = "",
    /** Собственный ключ ветки настоящей записи — чтобы ответы находили родителя в ленте. */
    val threadKey: String = "",
    /** Насколько выделять по голосам — относительно ОСТАЛЬНЫХ реплик этой сессии. */
    val voteTier: ChatVoteTier = ChatVoteTier.NONE,
)

private val HIGH_CONFIDENCE_SPOILER_PATTERNS = listOf(
    Regex("""\b(?:умира(?:ет|ют)|умер(?:ла|ли)?|погиб(?:ает|ла|ли)?|убива(?:ет|ют)|убил(?:а|и)?|убит(?:а|ы)?)\b"""),
    Regex("""\b(?:предатель(?:ница)?|предателем|предательницей|предал(?:а|и)?|преда[её]т)\b"""),
    Regex("""\b(?:оказывается|оказал(?:ся|ась|ись)|выясняется|раскрывается),?\s+что\b"""),
    // Само упоминание финала или концовки не спойлер: сюжетный исход обязан
    // содержать конкретное раскрытие, которое ловят соседние шаблоны.
    Regex("""\b(?:главн\p{L}*\s+(?:злоде\p{L}*|убийц\p{L}*)|настоящ\p{L}*\s+(?:отец|мать|брат|сестра))\b"""),
    Regex("""\b(?:dies?|was\s+killed|turns\s+out|traitor)\b"""),
)

/**
 * Консервативная локальная проверка: отсутствие номера/таймкода само по себе не
 * делает обычную реакцию спойлером. Приоритет имеют явная метка источника,
 * комментарий из будущей серии и только высокоуверенные фразы раскрытия сюжета.
 */
internal fun isPotentialChatSpoiler(comment: TitleComment, currentEpisode: Int): Boolean {
    if (comment.isSpoiler) return true
    if (currentEpisode > 0 && comment.episode > currentEpisode) return true
    val normalized = comment.message.lowercase().replace(Regex("\\s+"), " ").trim()
    return HIGH_CONFIDENCE_SPOILER_PATTERNS.any { it.containsMatchIn(normalized) }
}

/** Насколько плотно идёт чат. */
enum class ChatIntensity(val key: String, val label: String, val basePerMinute: Float) {
    QUIET("quiet", "Спокойно", 7f),
    NORMAL("normal", "Обычно", 16f),
    LIVELY("lively", "Живо", 30f),
    STORM("storm", "Шторм", 55f),
    ;

    companion object {
        fun of(key: String): ChatIntensity = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/**
 * Настроение, которое можно ДОКАЗАТЬ по данным. null — доказать нечем.
 *
 * Опенинг и эндинг приходят из AniSkip и потому точны. Остальное считается по
 * кадрам, и пороги здесь ОТНОСИТЕЛЬНЫЕ — «вдвое живее обычного для этого тайтла», а
 * не «движение больше 0.06»: абсолютное число пришлось бы подбирать под каждую
 * студию заново (см. [SceneEnergy]).
 */
internal fun measuredMood(
    positionMs: Long,
    opening: OpeningRange?,
    ending: OpeningRange?,
    energy: SceneSample,
    /** Сколько подряд ничего не измерялось — по этому опознаётся поворот. */
    calmForMs: Long,
    /** Сколько подряд держится признак экшена (см. [fightNow]). */
    fightForMs: Long,
    /** Сколько подряд держится темнота и неподвижность (см. [darkNow]). */
    darkForMs: Long,
): SceneMood? {
    if (opening != null && opening.isValid && positionMs in opening.startMs..opening.endMs) {
        return SceneMood.OPENING
    }
    if (ending != null && ending.isValid && positionMs in ending.startMs..ending.endMs) {
        return SceneMood.ENDING
    }
    if (!energy.known) return null
    // Жёсткая склейка после долгого затишья — это и есть «внезапный момент».
    //
    // Условие на редкость склеек здесь обязательно. В разговорной сцене режут между
    // собеседниками постоянно (замерено: 12–15 раз в минуту), и без него поворотом
    // объявлялся бы каждый второй ответ в диалоге. Смысл именно в контрасте: долго
    // не резали — и вдруг резанули.
    if (energy.cutSeen && calmForMs >= TWIST_CALM_MS && energy.cutsPerMinute <= TWIST_MAX_CUTS) {
        return SceneMood.TWIST
    }
    // ВЫДЕРЖКА, а не мгновенное значение.
    //
    // Драка — это секунды, а не один такт. Раньше хватало одного превышения, и по
    // живому журналу видно, чем это кончалось: замер прыгал от x0.03 до x15.18, и
    // «драка» объявлялась в разговорной сцене всякий раз, когда такт попадал на
    // всплеск. Теперь признак обязан продержаться [FIGHT_HOLD_MS] подряд.
    if (fightForMs >= FIGHT_HOLD_MS) return SceneMood.FIGHT
    if (darkForMs >= SAD_HOLD_MS) return SceneMood.SAD
    return null
}

/**
 * Держится ли ПРЯМО СЕЙЧАС признак экшена.
 *
 * Пороги разные на вход и на выход ([FIGHT_ENTER] против [FIGHT_STAY]): без этого
 * значение, гуляющее вокруг единственного порога, перещёлкивало бы настроение
 * несколько раз в секунду.
 */
internal fun fightNow(energy: SceneSample, current: SceneMood): Boolean {
    if (!energy.known) return false
    val inFight = current == SceneMood.FIGHT
    val ratio = if (inFight) FIGHT_STAY else FIGHT_ENTER
    val cuts = if (inFight) FIGHT_CUTS_STAY else FIGHT_CUTS
    return energy.motionRatio >= ratio && energy.cutsPerMinute >= cuts
}

/** Держится ли прямо сейчас признак тихой тёмной сцены. Пороги тоже с гистерезисом. */
internal fun darkNow(energy: SceneSample, current: SceneMood): Boolean {
    if (!energy.known) return false
    val inSad = current == SceneMood.SAD
    return energy.brightnessRatio <= (if (inSad) SAD_DARK_STAY else SAD_DARK) &&
        energy.motionRatio <= (if (inSad) SAD_STILL_STAY else SAD_STILL)
}

/**
 * Настроение из расписания — для того, что по кадру не определяется.
 *
 * Смешное отличить от обычного по пикселям НЕЛЬЗЯ: у шутки нет ни яркости, ни
 * движения, которыми она выдавала бы себя. Поэтому такие моменты раскладываются по
 * серии волной: детерминированной (одна и та же серия — одно и то же расписание,
 * пока не перематывали), но разной у разных серий и разной после каждой перемотки.
 *
 * Последняя восьмая часть серии — подъём: развязка почти всегда там, и это
 * единственное допущение о драматургии, которое приложение себе позволяет.
 */
internal fun arcMood(
    positionMs: Long,
    lengthMs: Long,
    episodeKey: String,
    seekEpoch: Int,
    energy: SceneSample = SceneSample.UNKNOWN,
): SceneMood {
    if (lengthMs > 0 && positionMs > lengthMs * CLIMAX_FROM) return SceneMood.HYPE
    val bucket = positionMs / ARC_BUCKET_MS
    var hash = episodeKey.hashCode().toLong() * 31 + bucket * 1_000_003L + seekEpoch * 7_919L
    hash = hash xor (hash ushr 29)
    hash *= -3_685_477_567L
    hash = hash xor (hash ushr 32)
    val guess = when ((hash.mod(100L)).toInt()) {
        in 0..17 -> SceneMood.FUNNY
        in 18..27 -> SceneMood.HYPE
        else -> SceneMood.CALM
    }
    // Смешное по кадру не определяется — но кадр всё же может ОПРОВЕРГНУТЬ догадку.
    // На тёмной и замершей сцене шутки не случается почти никогда, и «АХАХА» там
    // выглядит хуже, чем молчание. Это не превращает догадку в замер, но убирает
    // самые заметные промахи.
    if (guess == SceneMood.FUNNY && energy.known &&
        (energy.brightnessRatio < FUNNY_MIN_BRIGHT || energy.motionRatio < FUNNY_MIN_MOTION)
    ) {
        return SceneMood.CALM
    }
    return guess
}

/** Смена плана считается поворотом только после стольких мс затишья. */
private const val TWIST_CALM_MS = 9_000L

/**
 * Больше стольких склеек в минуту — и это просто монтаж, а не событие.
 *
 * ЗАМЕРЕНО на живом просмотре: разговорная сцена идёт на 12–15 склейках в минуту.
 * Порог ниже этого, чтобы поворотом считалась склейка в по-настоящему статичном
 * куске.
 */
private const val TWIST_MAX_CUTS = 8f

/** Сколько признак экшена обязан держаться подряд, прежде чем это назовут дракой. */
private const val FIGHT_HOLD_MS = 3_000L

/** Сколько обязана держаться темнота, прежде чем это назовут тихой сценой. */
private const val SAD_HOLD_MS = 8_000L

/** Порог входа в экшен и порог выхода из него. */
private const val FIGHT_ENTER = 1.8f
private const val FIGHT_STAY = 1.15f
private const val FIGHT_CUTS_STAY = 10f

/**
 * Смен плана в минуту, ниже которых это не экшен.
 *
 * ЗАМЕРЕНО на живом просмотре («Ао Аси», шестая серия): спокойные сцены идут на
 * 12–15 склейках в минуту — обычный разговорный монтаж с перекладкой между
 * собеседниками. Порог обязан лежать выше этого, иначе условие не отделяет ничего
 * и решение целиком ложится на движение.
 */
private const val FIGHT_CUTS = 18f
private const val SAD_DARK = 0.74f
private const val SAD_STILL = 0.62f
private const val SAD_DARK_STAY = 0.86f
private const val SAD_STILL_STAY = 0.80f

/** Ниже этой яркости и этого движения «весёлым» момент не назовут. */
private const val FUNNY_MIN_BRIGHT = 0.88f
private const val FUNNY_MIN_MOTION = 0.35f

/** Шаг волны настроений: реже — и чат тянет одно и то же, чаще — дёргается. */
private const val ARC_BUCKET_MS = 35_000L

/** С какой доли серии начинается подъём к развязке. */
private const val CLIMAX_FROM = 0.86

/**
 * Держатель текущего чата — один на процесс, как и плеер.
 *
 * Возвращает ТУ ЖЕ сессию текущей серии. Догрузка комментариев, настройки, пересбор
 * экрана, дрожание длины и состояние VLC её не сбрасывают.
 */
internal object ChatSessions {

    private var current: ChatEngine? = null

    /**
     * Сколько настоящих комментариев берётся в ленту.
     *
     * Полный пул остаётся в репозитории; очередь чата получает равномерный срез до
     * шестисот настоящих реплик из всей глубины сезона.
     */
    @Synchronized
    fun obtain(
        episodeKey: String,
        comments: List<TitleComment>,
        viewers: Int,
        intensity: ChatIntensity,
        freshFirst: Boolean,
        alwaysQuiet: Boolean = false,
        seed: Long = System.nanoTime(),
        speed: Float = 1f,
        episode: Int = 0,
        popularFirst: Boolean = false,
        episodeOnly: Boolean = false,
    ): ChatEngine {
        val cached = current
        // Смена порядка («сначала популярные») или строгости по серии — новая очередь:
        // старая уже перетасована и набрана по другому правилу.
        if (cached != null && cached.episodeKey == episodeKey && cached.popularFirst == popularFirst && cached.episodeOnly == episodeOnly) {
            cached.mergeReal(chatPicksForEpisode(comments, episode, freshFirst, CHAT_REAL_LIMIT, popularFirst, episodeOnly), comments.size)
            cached.retune(intensity, viewers, alwaysQuiet, speed)
            return cached
        }
        // Источник отдаёт обсуждение сезонного релиза целиком, но комментарии будущих
        // серий нельзя переносить назад. У остальных метка спойлера определяется
        // источником и консервативным анализом текста, а не отсутствием таймкода.
        val real = chatPicksForEpisode(comments, episode, freshFirst, CHAT_REAL_LIMIT, popularFirst, episodeOnly)
        val engine = ChatEngine(
            episodeKey, comments.size, viewers, intensity, alwaysQuiet, emptyList(), seed, speed,
            currentEpisode = episode,
        ).also { it.popularFirst = popularFirst; it.episodeOnly = episodeOnly; it.mergeReal(real, comments.size) }
        PlayerDiagnostics.log(
            "chat.session",
            "episode=$episodeKey; session=${engine.id}; viewers=$viewers; " +
                "intensity=${intensity.key}; speed=${normalizeChatSpeed(speed)}; quiet=$alwaysQuiet; real=${real.size} of ${comments.size}; " +
                "phrases=${ChatLines.totalPhrases()}",
        )
        current = engine
        return engine
    }

    fun merge(engine: ChatEngine, comments: List<TitleComment>, freshFirst: Boolean, episode: Int = 0, popularFirst: Boolean = false, episodeOnly: Boolean = false) {
        engine.mergeReal(chatPicksForEpisode(comments, episode, freshFirst, CHAT_REAL_LIMIT, popularFirst, episodeOnly), comments.size)
    }
}

/** UI queue only; the complete deduplicated source pool remains in repository/cache. */
private const val CHAT_REAL_LIMIT = 600

/** Меньше стольких настоящих реплик с голосами — выделять не по чему. */
internal const val MIN_POOL_FOR_TIERS = 8

/** Ниже этого голосов не выделяем даже в крошечном пуле. */
internal const val MIN_NOTABLE_VOTES = 3

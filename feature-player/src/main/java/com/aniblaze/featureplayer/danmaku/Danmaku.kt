package com.aniblaze.featureplayer.danmaku

import com.aniblaze.aggregator.model.TitleComment
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Комментарии зрителей, изредка проплывающие поверх видео. Перенесено с настольной
 * версии; здесь — вся логика отбора и расписания, без единой строчки интерфейса.
 *
 * Про источник. У комментариев Anixart НЕТ таймкодов — это обсуждение тайтла целиком,
 * а не привязанные к секунде реплики. Поэтому они и показываются изредка и вразнобой:
 * честно изображать «прямо сейчас об этой сцене» было бы враньём. Зато они настоящие и
 * относятся именно к тому, что смотрят.
 */

/**
 * Как часто всплывают реплики.
 *
 * Первая — случайно в окне [firstMinMs]..[firstMaxMs]. Дальше шаг считается от длины
 * серии (растянуть запас на [spreadShare] её длины), но всегда зажат в
 * [minGapMs]..[maxGapMs]: при 20–50 репликах человек не ждёт десять минут, а горстка
 * из восьми не расстреливается за первые две.
 */
enum class DanmakuRate(
    val key: String,
    val label: String,
    val firstMinMs: Long,
    val firstMaxMs: Long,
    val minGapMs: Long,
    val maxGapMs: Long,
    val spreadShare: Double,
) {
    RARE("rare", "Редко", 20_000L, 40_000L, 90_000L, 300_000L, 2.0),
    NORMAL("normal", "Обычно", 8_000L, 15_000L, 45_000L, 160_000L, 1.0),
    OFTEN("often", "Часто", 4_000L, 10_000L, 15_000L, 60_000L, 0.5),
    ;

    companion object {
        fun of(key: String): DanmakuRate = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/**
 * Состояние показа ОДНОЙ серии — вне Compose.
 *
 * Это ответ на главный дефект наивной реализации: колода, счётчики и «что уже
 * показано» живут внутри композиции с летучими ключами, и любая мелочь — пересбор
 * экрана, подрагивание длины HLS, пауза — создаёт колоду заново. Отсюда качели: сброс
 * «показанного» даёт дубликаты, а вечный перезапуск паузы — пустой экран.
 *
 * Здесь наоборот. Объект создаётся ОДИН раз на пару «тайтл + серия» ([episodeKey]) и
 * живёт, пока её не сменили:
 *
 *  • очередь нормализована, дедуплицирована и перетасована один раз;
 *  • показанное — просто индекс [next]: показанные никогда не возвращаются;
 *  • часы — НАКОПЛЕННОЕ ВРЕМЯ ПРОСМОТРА, которое двигает только внешний тик при
 *    играющем видео. Пауза не двигает часы; перемотка и буферизация на них не влияют;
 *  • длительность серии — только ПОДСКАЗКА для расчёта шага: её дрожание меняет
 *    будущие интервалы, но не может ничего перезапустить или сбросить.
 *
 * Реплика считается показанной по протоколу «выбрали → отдали на экран →
 * подтвердили»: [peekDue] ничего не тратит, индекс двигает только [commitShown].
 */
class DanmakuSession(
    val episodeKey: String,
    /** Сколько сырых комментариев легло в основу — по нему [DanmakuSessions] узнаёт дозагрузку. */
    val sourceSize: Int,
    val freshFirst: Boolean,
    private val rate: DanmakuRate,
    texts: List<String>,
    seed: Long,
) {
    val id: Long = IDS.incrementAndGet()
    val rateKey: String get() = rate.key

    private val random = java.util.Random(seed)
    private val queue: List<String> = texts.shuffled(java.util.Random(seed))

    /** Всё до этого индекса УЖЕ показано и не вернётся никогда. */
    private var next = 0

    /** Накопленное время просмотра, мс. Двигается только [accumulate]. */
    private var watchedMs = 0L

    /** Когда пора показать следующую реплику (в часах [watchedMs]). */
    private var dueAtMs = randomBetween(rate.firstMinMs, rate.firstMaxMs)

    private val activeSchedulers = AtomicInteger(0)

    val usable: Int get() = queue.size
    val shownCount: Int get() = next

    fun hasMore(): Boolean = next < queue.size

    /** Тик просмотра: зовётся только пока видео играет. Пауза — просто не зовут. */
    fun accumulate(deltaMs: Long) {
        watchedMs += deltaMs
    }

    /**
     * Реплика, которую ПОРА показать, — или null. НИЧЕГО не тратит: сколько ни зови,
     * вернёт одну и ту же, пока её не подтвердят [commitShown].
     */
    fun peekDue(laneFree: Boolean): String? {
        if (!laneFree || next >= queue.size || watchedMs < dueAtMs) return null
        return queue[next]
    }

    /**
     * Реплика реально ушла на экран: двигаем индекс и назначаем следующий срок.
     *
     * [durationHintMs] — приблизительная длина серии, только для шага. Запас
     * растягивается на серию (маленький — реже, большой — чаще), но шаг всегда зажат
     * в границы настройки, а случайный множитель ±25% убирает механическую
     * равномерность.
     */
    fun commitShown(durationHintMs: Long) {
        if (next >= queue.size) return
        next++
        dueAtMs = watchedMs + gapMs(durationHintMs)
    }

    private fun gapMs(durationHintMs: Long): Long {
        val base = if (durationHintMs > 0) {
            ((durationHintMs * rate.spreadShare).toLong() / (usable + 1))
                .coerceIn(rate.minGapMs, rate.maxGapMs)
        } else {
            (rate.minGapMs + rate.maxGapMs) / 2
        }
        val jitter = 0.75 + random.nextDouble() * 0.5
        return (base * jitter).toLong()
    }

    private fun randomBetween(from: Long, to: Long): Long =
        from + (random.nextDouble() * (to - from)).toLong()

    /** Счётчик живых циклов показа — для гарантии «ровно один». */
    fun schedulerEnter(): Int = activeSchedulers.incrementAndGet()
    fun schedulerExit(): Int = activeSchedulers.decrementAndGet()

    private companion object {
        val IDS = AtomicLong(0L)
    }
}

/**
 * Держатель текущей сессии. Один на процесс: плеер в приложении один.
 *
 * [obtain] возвращает ТУ ЖЕ сессию, пока не изменилось что-то из настоящих причин
 * пересоздать её: другая серия, дозагрузившиеся комментарии (пусто → пришли), смена
 * настроек показа. Пересбор экрана, новая identity того же списка, длительность,
 * качество, озвучка сессию НЕ трогают.
 */
object DanmakuSessions {

    private var current: DanmakuSession? = null

    @Synchronized
    fun obtain(
        episodeKey: String,
        comments: List<TitleComment>,
        episode: Int,
        freshFirst: Boolean,
        rate: DanmakuRate,
        seed: Long = System.nanoTime(),
    ): DanmakuSession {
        val cached = current
        if (cached != null &&
            cached.episodeKey == episodeKey &&
            cached.sourceSize == comments.size &&
            cached.freshFirst == freshFirst &&
            cached.rateKey == rate.key
        ) {
            return cached
        }
        val pool = danmakuPool(comments, episode, freshFirst)
        val session = DanmakuSession(episodeKey, comments.size, freshFirst, rate, pool, seed)
        current = session
        return session
    }

    /** Смена тайтла: следующая серия обязана начать с чистого листа. */
    @Synchronized
    fun forget() {
        current = null
    }
}

/**
 * Нормализованный ключ реплики для дедупликации: нижний регистр, только буквы и
 * цифры. Пробелы, регистр, повторная пунктуация и эмодзи различий не создают:
 * «Лучшее аниме сезона!!!», «лучшее   аниме сезона» и «Лучшее аниме сезона 😀» —
 * один ключ. Префикс — чтобы «почти одинаковые» с разными хвостами тоже слиплись.
 */
internal fun danmakuKey(text: String): String {
    val builder = StringBuilder(text.length)
    for (ch in text.lowercase()) if (ch.isLetterOrDigit()) builder.append(ch)
    return builder.toString().take(KEY_PREFIX)
}

/**
 * Отбор реплик, годных для показа поверх видео.
 *
 * Защита от спама здесь же, и она нужна: под тайтлами полно «первый!!!», простыней на
 * пол-экрана и одного и того же текста от разных людей. На кадре такое не оживляет, а
 * раздражает — и читается хуже всего.
 */
internal fun danmakuPool(
    comments: List<TitleComment>,
    /** Серия, которую смотрят сейчас. 0 = не важно (фильм). */
    episode: Int = 0,
    /** true — сначала свежие, иначе порядок источника (там сверху лучшие по оценкам). */
    freshFirst: Boolean = false,
): List<String> {
    // Порядок шагов: СНАЧАЛА отбор, ПОТОМ раскладка по сериям. Наоборот выходит криво:
    // отбор выкидывает около шестидесяти процентов, и нарезанная ДО него доля в
    // двадцать пять реплик превращается в десяток.
    val clean = cleaned(comments, freshFirst)
    return episodeScope(clean, episode).take(POOL_LIMIT).map { it.message }
}

private val HIGH_CONFIDENCE_SPOILER_PATTERNS = listOf(
    Regex("""\b(?:умира(?:ет|ют)|умер(?:ла|ли)?|погиб(?:ает|ла|ли)?|убива(?:ет|ют)|убил(?:а|и)?|убит(?:а|ы)?)\b"""),
    Regex("""\b(?:предатель(?:ница)?|предателем|предательницей|предал(?:а|и)?|преда[её]т)\b"""),
    Regex("""\b(?:оказывается|оказал(?:ся|ась|ись)|выясняется|раскрывается),?\s+что\b"""),
    // Само упоминание финала или концовки — обычная реакция, пока в тексте нет
    // конкретного сюжетного раскрытия из соседних шаблонов.
    Regex("""\b(?:главн\p{L}*\s+(?:злоде\p{L}*|убийц\p{L}*)|настоящ\p{L}*\s+(?:отец|мать|брат|сестра))\b"""),
    Regex("""\b(?:dies?|was\s+killed|turns\s+out|traitor)\b"""),
)

internal const val NEW_MESSAGE_TEXT = "Новое сообщение"

/**
 * Безопасный локальный фильтр без сетевого AI: обычная реакция не становится
 * спойлером из-за отсутствия номера серии. Отсекаются метка источника и только
 * высокоуверенные фразы раскрытия сюжета; чужую серию отдельно удаляет [episodeScope].
 */
internal fun isPotentialDanmakuSpoiler(comment: TitleComment): Boolean {
    if (comment.isSpoiler) return true
    val normalized = comment.message.lowercase().replace(Regex("\\s+"), " ").trim()
    return HIGH_CONFIDENCE_SPOILER_PATTERNS.any { it.containsMatchIn(normalized) }
}

/**
 * Real parser comments used by the mobile chat panel. Unlike the floating overlay,
 * this keeps the original text so a spoiler can be revealed deliberately in-place.
 * Only Russian-language messages for the current episode slice are returned.
 */
internal fun mobileChatComments(comments: List<TitleComment>, episode: Int): List<TitleComment> {
    val seen = HashSet<String>()
    return episodeScope(comments, episode).asSequence()
        .sortedByDescending { it.timestamp }
        .map { it.copy(message = it.message.replace(Regex("\\s+"), " ").trim()) }
        .filter { it.message.length in MIN_LEN..MAX_LEN * 3 }
        .filter { isRussianComment(it.message) }
        .filter { seen.add(danmakuKey(it.message)) }
        .take(120)
        .toList()
}

internal fun isRussianComment(text: String): Boolean {
    val letters = text.count(Char::isLetter)
    if (letters < MIN_LETTERS) return false
    val cyrillic = text.count { it in '\u0400'..'\u052F' || it in '\u2DE0'..'\u2DFF' || it in '\uA640'..'\uA69F' }
    return cyrillic >= 4 && cyrillic.toDouble() / letters >= 0.55
}

/** Отсев мусора и повторов; сообщения возвращаются уже нормализованными. */
private fun cleaned(comments: List<TitleComment>, freshFirst: Boolean): List<TitleComment> {
    val seen = HashSet<String>()
    return comments.asSequence()
        .let { if (freshFirst) it.sortedByDescending { c -> c.timestamp }.asSequence() else it }
        // Не выбрасываем всю очередь из-за меток спойлера. Содержимое такой реплики
        // не попадает на кадр, но сам факт активности остаётся нейтральным сообщением.
        .map { comment ->
            val normalized = comment.message.replace(Regex("\\s+"), " ").trim()
            comment.copy(message = if (isPotentialDanmakuSpoiler(comment)) NEW_MESSAGE_TEXT else normalized)
        }
        .filter { it.message.length in MIN_LEN..MAX_LEN }
        // Ссылки и разметку выкидываем — на кадре это шум.
        .filter { !it.message.contains("http", ignoreCase = true) }
        // Кричащие и однословные — мимо.
        .filter { c -> c.message.count { it.isLetter() } >= MIN_LETTERS }
        .filter { c ->
            val upper = c.message.count { it.isUpperCase() }
            val letters = c.message.count { it.isLetter() }.coerceAtLeast(1)
            upper.toDouble() / letters < MAX_UPPER_SHARE
        }
        // Один и тот же текст в любом написании — только один раз (см. danmakuKey).
        .filter { val key = danmakuKey(it.message); key.isNotBlank() && seen.add(key) }
        .toList()
}

/**
 * Что достаётся КОНКРЕТНОЙ серии.
 *
 * Здесь причина дубликатов, которые видно не внутри серии, а МЕЖДУ сериями. Общий пул
 * тайтла, отданный целиком каждой серии подряд, даёт одни и те же фразы на пятой,
 * шестой и седьмой: внутри серии повторов нет, а по ощущению они есть.
 *
 * Почему нельзя просто «только своя серия». ЗАМЕРЕНО по двенадцати страницам
 * обсуждения:
 *
 *     Деревня кузнецов   294 комментария, с указанием серии — 3
 *     High School DxD    283 комментария, с указанием серии — 15
 *     Невеста демона     247 комментариев, с указанием серии — 198 (серии 1–6)
 *
 * Поле «серия» у Anixart появилось недавно: у выходящих сейчас тайтлов оно заполнено,
 * у прежних пустое почти везде. Строгий отбор оставил бы два тайтла из трёх вообще без
 * комментариев.
 *
 * Поэтому правило такое:
 *  • реплики ЧУЖИХ серий не показываются НИКОГДА (на первой серии реплика с шестой —
 *    готовый спойлер, а пометку «спойлер» ставят далеко не всегда);
 *  • свои реплики серии берутся все;
 *  • общий пул НАРЕЗАН НА НЕПЕРЕСЕКАЮЩИЕСЯ ДОЛИ, и серия получает свою.
 */
internal fun episodeScope(comments: List<TitleComment>, episode: Int): List<TitleComment> {
    val unscoped = comments.filter { it.episode <= 0 }
    if (episode <= 0) return unscoped
    val own = comments.filter { it.episode == episode }
    // Своих реплик хватает — общий пул НЕ подмешиваем вовсе: именно он и создаёт
    // пересечения между сериями.
    if (own.size >= SLICE_TARGET) return own
    return own + episodeSlice(unscoped, episode)
}

/**
 * Доля общего пула, причитающаяся серии.
 *
 * Долей столько, чтобы в каждой было около [SLICE_TARGET] реплик; серия берёт свою по
 * номеру. Доли не пересекаются, значит подряд идущие серии не повторяются, пока круг
 * не замкнётся — при 291 общей реплике это одиннадцать серий, то есть целый сезон без
 * единого повтора.
 */
private fun episodeSlice(unscoped: List<TitleComment>, episode: Int): List<TitleComment> {
    if (unscoped.isEmpty()) return emptyList()
    val slices = (unscoped.size / SLICE_TARGET).coerceAtLeast(1)
    if (slices == 1) return unscoped
    val mine = (episode - 1).mod(slices)
    return unscoped.filterIndexed { index, _ -> index % slices == mine }
}

/** Сколько реплик из общего пула отводится одной серии. */
private const val SLICE_TARGET = 25

// Пороги отбора ослаблены осознанно. Строгий набор (12..90 знаков, букв ≥ 8, капса
// < 50%) съедал 55 реплик из 75 на живом тайтле — из-за него запас и кончался посреди
// серии. Режется только настоящий мусор: «первый!!!», простыни, сплошной капс, ссылки.
private const val MIN_LEN = 10
private const val MAX_LEN = 140
private const val MIN_LETTERS = 6
private const val MAX_UPPER_SHARE = 0.7

/** Длина нормализованного ключа дедупликации (буквы и цифры, без пунктуации). */
private const val KEY_PREFIX = 40

private const val POOL_LIMIT = 80

package com.aniblaze.desktop.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.TitleComment
import com.aniblaze.aggregator.source.commentIdentity

/**
 * Комментарии зрителей, изредка проплывающие поверх видео.
 *
 * Это НЕ чат. Задача — оживить просмотр, а не занять экран, поэтому здесь ровно
 * столько ограничений, сколько нужно, чтобы реплики не мешали смотреть:
 *
 *  • живут только в ВЕРХНЕЙ ПОЛОСЕ кадра — центр (лица) и низ (субтитры, панель
 *    управления) не трогаются вообще;
 *  • дорожек всего [LANES], и каждая занята не больше чем одной репликой за раз —
 *    накладываться друг на друга им негде;
 *  • одновременно на экране не больше [LANES] штук, а между появлениями — пауза;
 *  • появление и уход — плавные, движение равномерное, без рывков;
 *  • длинные реплики обрезаются одной строкой.
 *
 * Про источник. У комментариев Anixart НЕТ таймкодов — это обсуждение тайтла
 * целиком, а не привязанные к секунде реплики. Поэтому они и показываются изредка и
 * вразнобой: честно изображать «прямо сейчас об этой сцене» было бы враньём. Зато
 * они настоящие и относятся именно к тайтлу. Поскольку точной позиции внутри серии
 * источник не даёт, потенциально раскрывающий сюжет текст заменяется нейтральным
 * уведомлением; автоматически раскрываются только уже пройденные серии.
 */
@Composable
fun DanmakuOverlay(
    /** Устойчивый ключ серии — «тайтл:серия». ЕДИНСТВЕННОЕ, что пересоздаёт показ. */
    episodeKey: String,
    comments: List<TitleComment>,
    /** Серия, которую смотрят: реплики других серий не показываются (спойлеры). */
    episode: Int,
    /** Только свежие: сначала недавние, а не лучшие по оценкам. */
    freshFirst: Boolean,
    /** Приблизительная длина серии — только подсказка шага. Дрожит — и пусть. */
    lengthMs: Long,
    playing: Boolean,
    rate: DanmakuRate,
    opacity: Float,
    fontSize: Int,
    moving: Boolean,
    modifier: Modifier = Modifier,
) {
    if (comments.isEmpty()) return
    // ВСЁ СОСТОЯНИЕ ПОКАЗА — В СЕССИИ, ВНЕ КОМПОЗИЦИИ (см. DanmakuSession, там же
    // разбор, почему remember-состояние с летучими ключами и было причиной качелей
    // «всё дублируется ↔ ничего нет»). Здесь только отрисовка и один цикл-водитель.
    //
    // remember — лишь кэш обращения к держателю; пересоздание composable вернёт ТУ ЖЕ
    // сессию с тем же «показано». comments.size в ключе — не identity: пересбор
    // экрана даёт новый список тех же 75 штук, и сессия обязана пережить его.
    val session = remember(episodeKey, comments.size, freshFirst, rate) {
        DanmakuSessions.obtain(episodeKey, comments, episode, freshFirst, rate)
    }
    if (session.usable == 0) return
    // Живые плашки привязаны к сессии: смена серии убирает с экрана чужие реплики.
    val live = remember(session) { mutableStateListOf<DanmakuShot>() }
    var issued by remember(session) { mutableStateOf(0) }

    // playing и lengthMs читаются ВНУТРИ цикла через rememberUpdatedState — ключами
    // эффекта им быть нельзя: длина HLS дрожит (1435059 → 1420044 → 1450154 за один
    // просмотр), и как ключ она перезапускала цикл чаще, чем истекала начальная
    // пауза, — реплики не появлялись вовсе.
    val playingNow by rememberUpdatedState(playing)
    val lengthNow by rememberUpdatedState(lengthMs)
    LaunchedEffect(session) {
        val active = session.schedulerEnter()
        PlayerDiagnostics.log(
            "danmaku.scheduler.start",
            "episode=${session.episodeKey}; session=${session.id}; active=$active; " +
                "usable=${session.usable}; alreadyShown=${session.shownCount}",
        )
        try {
            while (session.hasMore()) {
                kotlinx.coroutines.delay(TICK_MS)
                // Пауза: часы сессии стоят, ничего не сгорает и не сбрасывается.
                if (!playingNow) continue
                session.accumulate(TICK_MS)
                // Свободная дорожка: занятых не трогаем — наложений не бывает.
                val busy = live.map { it.lane }.toSet()
                val free = (0 until LANES).firstOrNull { it !in busy }
                // Протокол «выбрали → отдали на экран → подтвердили»: peek ничего не
                // тратит; реплика считается показанной только после live.add. Отмена
                // цикла между ними не сжигает её.
                val text = session.peekDue(laneFree = free != null) ?: continue
                live.add(DanmakuShot(id = issued++, lane = free!!, text = text))
                session.commitShown(lengthNow)
                PlayerDiagnostics.log(
                    "danmaku.shown",
                    "session=${session.id}; n=${session.shownCount}/${session.usable}",
                )
            }
        } finally {
            PlayerDiagnostics.log(
                "danmaku.scheduler.stop",
                "episode=${session.episodeKey}; session=${session.id}; " +
                    "reason=${if (session.hasMore()) "cancelled" else "exhausted"}; shown=${session.shownCount}",
            )
            session.schedulerExit()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Полоса, в которой вообще разрешено рисовать: сверху вниз до трети кадра.
        // Ниже — лица и субтитры, туда реплики не заходят никогда.
        val bandHeight = maxHeight * BAND_SHARE
        val laneHeight = bandHeight / LANES
        // Ширина реплики — ДОЛЯ КАДРА, а не жёсткие 420 dp.
        //
        // С фиксированным числом в узком окне плашка была шире самого кадра и уезжала
        // за край, а в широком занимала лишь угол. Доля держит одинаковый вид на любом
        // размере, а верхняя граница не даёт реплике растянуться на полэкрана.
        val lineWidth = (maxWidth * WIDTH_SHARE).coerceIn(MIN_WIDTH, MAX_WIDTH)
        live.forEach { shot ->
            key(shot.id) {
                DanmakuLine(
                    shot = shot,
                    laneTop = LANE_TOP_INSET + laneHeight * shot.lane,
                    travel = maxWidth,
                    lineWidth = lineWidth,
                    moving = moving,
                    opacity = opacity,
                    fontSize = fontSize,
                    onFinished = { live.remove(shot) },
                )
            }
        }
    }
}

@Composable
private fun DanmakuLine(
    shot: DanmakuShot,
    laneTop: Dp,
    travel: Dp,
    lineWidth: Dp,
    moving: Boolean,
    opacity: Float,
    fontSize: Int,
    onFinished: () -> Unit,
) {
    var visible by remember(shot.id) { mutableStateOf(false) }
    val fade by animateFloatAsState(
        if (visible) 1f else 0f,
        tween(FADE_MS),
        label = "danmakuFade",
    )
    // Ход слева направо только в подвижном режиме; в статичном реплика просто
    // всплывает у правого края и гаснет.
    val progress by animateFloatAsState(
        if (visible) 1f else 0f,
        tween(if (moving) shot.travelMs else 0, easing = LinearEasing),
        label = "danmakuTravel",
    )
    LaunchedEffect(shot.id) {
        visible = true
        kotlinx.coroutines.delay((if (moving) shot.travelMs else STATIC_HOLD_MS).toLong())
        visible = false
        kotlinx.coroutines.delay(FADE_MS.toLong())
        onFinished()
    }
    // offset, а НЕ padding: реплика обязана уезжать за левый край, а отрицательный
    // padding в Compose — исключение на этапе раскладки.
    val offsetX = if (moving) travel - (travel + lineWidth) * progress else travel - lineWidth - EDGE_GAP
    Box(
        Modifier
            .offset(x = offsetX, y = laneTop)
            .widthIn(max = lineWidth)
            .alpha(fade * opacity)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66000000))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        // ДВЕ строки, а не одна.
        //
        // С одной строкой длинная реплика обрезалась многоточием почти сразу — на
        // экране висел огрызок фразы, который нечего и читать. Длина в отборе
        // ограничена так, чтобы в две строки помещалось целиком, а многоточие
        // осталось только страховкой на самый мелкий кадр.
        Text(
            shot.text,
            color = Color.White,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = (fontSize * 1.25).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Одна реплика на экране. */
private class DanmakuShot(val id: Int, val lane: Int, val text: String) {
    /** Дольше для длинных: короткая фраза не должна ползти через весь кадр минуту. */
    val travelMs: Int = (TRAVEL_BASE_MS + text.length * TRAVEL_PER_CHAR_MS).coerceAtMost(TRAVEL_MAX_MS)
}

/**
 * Как часто всплывают реплики.
 *
 * Первая — случайно в окне [firstMinMs]..[firstMaxMs]. Дальше шаг считается от
 * длины серии (растянуть запас на [spreadShare] её длины), но всегда зажат в
 * [minGapMs]..[maxGapMs]: при 20–50 репликах пользователь не ждёт десять минут,
 * а горстка из восьми не расстреливается за первые две.
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
 * Защита от спама здесь же и она нужна: под тайтлами полно «первый!!!», простыней на
 * пол-экрана и одного и того же текста от разных людей. На кадре такое не оживляет, а
 * раздражает — и читается хуже всего.
 */
internal fun danmakuPool(
    comments: List<TitleComment>,
    /** Серия, которую смотрят сейчас. 0 = не важно (фильм). */
    episode: Int = 0,
    /** true — сначала свежие, иначе порядок источника (там сверху лучшие по оценкам). */
    freshFirst: Boolean = false,
): List<String> = danmakuPicks(comments, episode, freshFirst).map { danmakuPreviewText(it, episode) }

/**
 * То же самое, но целыми комментариями — с автором, аватаркой и оценкой.
 *
 * Нужно симулятору чата ([ChatEngine]): там настоящая реплика показывается от имени
 * настоящего человека, а не безымянной строкой. Отбор и нарезка по сериям обязаны
 * быть ТЕ ЖЕ САМЫЕ, иначе чат начал бы показывать то, что оверлей справедливо
 * выбросил, — спойлеры чужих серий в первую очередь.
 */
internal fun danmakuPicks(
    comments: List<TitleComment>,
    episode: Int = 0,
    freshFirst: Boolean = false,
    /**
     * Потолок очереди. У оверлея он низкий: на экране одновременно три реплики, и
     * запас сверх [POOL_LIMIT] всё равно не всплывёт. Чат за серию успевает показать
     * втрое больше, поэтому просит потолок повыше.
     */
    limit: Int = POOL_LIMIT,
): List<TitleComment> {
    // Порядок шагов: СНАЧАЛА отбор, ПОТОМ раскладка по сериям.
    //
    // Раньше было наоборот, и доли выходили кривыми: отбор выкидывает около
    // шестидесяти процентов, так что нарезанная до него доля в двадцать пять реплик
    // на экране превращалась в десяток.
    val clean = cleaned(comments, freshFirst, collapseSimilarText = true)
    return episodeScope(clean, episode).take(limit)
}

/**
 * Настоящие реплики для чата: единый пул всего сезонного релиза.
 *
 * В отличие от danmaku поверх кадра, здесь не режем по текущей серии и не считаем
 * похожие фразы одним сообщением. Источник уже дал устойчивые id; при их отсутствии
 * fallback включает автора, время и точный нормализованный текст. Если пул больше
 * очереди чата, берём равномерный срез из всей глубины, а не первые N страниц.
 */
internal fun seasonChatPicks(
    comments: List<TitleComment>,
    freshFirst: Boolean = false,
    limit: Int,
    /** По убыванию голосов и БЕЗ равномерного среза: первые — самые заплюсованные. */
    popularFirst: Boolean = false,
): List<TitleComment> {
    val clean = cleaned(comments, freshFirst, collapseSimilarText = false)
    // Ветки: отбираются САМОСТОЯТЕЛЬНЫЕ записи, а ответы идут следом за своей —
    // блоком, как в Reddit. Ответ без родителя в пуле в чат не попадает: «@ник»
    // без контекста непонятен.
    val replies = clean.filter { it.parentId != 0L }.groupBy { threadKey(it.source, it.parentId) }
    val roots = clean.filter { it.parentId == 0L }
    val chosen = if (popularFirst) {
        roots.sortedWith(compareByDescending<TitleComment> { it.votes }.thenByDescending { it.timestamp }).take(limit)
    } else {
        spreadTake(roots, limit)
    }
    return chosen.flatMap { root ->
        val thread = replies[threadKey(root.source, root.id)].orEmpty()
            .sortedWith(compareByDescending<TitleComment> { it.votes }.thenBy { it.timestamp })
            .take(CHAT_REPLIES_PER_THREAD)
        listOf(root) + thread
    }
}

/** Ключ ветки: источник + id записи (числовые id Anixart и Yummy пересекаются). */
internal fun threadKey(source: String, id: Long): String = "${source.ifBlank { "unknown" }}:$id"

/** Сколько ответов показывать под одной записью в чате. */
internal const val CHAT_REPLIES_PER_THREAD = 3

/**
 * Production-срез настоящих реплик чата для конкретной серии.
 *
 * Старый путь вызывал [seasonChatPicks] прямо и отдавал первой серии комментарии
 * двенадцатой. Это расходилось даже с тестовым helper чата, который использовал
 * безопасный [danmakuPicks]. Здесь сохраняется большой сезонный пул, но всё явно
 * размеченное будущей серией удаляется до попадания в очередь.
 */
internal fun chatPicksForEpisode(
    comments: List<TitleComment>,
    episode: Int,
    freshFirst: Boolean = false,
    limit: Int,
    popularFirst: Boolean = false,
    /** Строго: только реплики с пометкой этой серии; без пометки — мимо. */
    episodeOnly: Boolean = false,
): List<TitleComment> {
    val eligible = when {
        episodeOnly && episode > 0 -> comments.filter { it.episode == episode }
        episode > 0 -> comments.filter { it.episode <= 0 || it.episode <= episode }
        else -> comments.filter { it.episode <= 0 }
    }
    return seasonChatPicks(eligible, freshFirst, limit, popularFirst)
}

/** Текст, который разрешено показать автоматически поверх ещё не досмотренного кадра. */
internal fun danmakuPreviewText(comment: TitleComment, episode: Int): String =
    if (isPotentialChatSpoiler(comment, episode)) NEW_MESSAGE_TEXT else comment.message

internal const val NEW_MESSAGE_TEXT = "Новое сообщение"

private fun <T> spreadTake(items: List<T>, limit: Int): List<T> {
    if (limit <= 0 || items.isEmpty()) return emptyList()
    if (items.size <= limit) return items
    return List(limit) { bucket ->
        val start = ((bucket.toLong() * items.size) / limit).toInt()
        val end = (((bucket + 1L) * items.size) / limit).toInt().coerceAtMost(items.size)
        val width = (end - start).coerceAtLeast(1)
        // Не всегда первый элемент бакета: периодические признаки (например номер
        // серии 1..24) иначе могут войти в резонанс с равным шагом и потерять половину
        // разнообразия, хотя срез формально прошёл весь список.
        items[start + ((bucket * 31) % width)]
    }
}

/** Отсев мусора и повторов; сообщения возвращаются уже нормализованными. */
private fun cleaned(
    comments: List<TitleComment>,
    freshFirst: Boolean,
    collapseSimilarText: Boolean,
): List<TitleComment> {
    val seen = HashSet<String>()
    return comments.asSequence()
        .let { if (freshFirst) it.sortedByDescending { c -> c.timestamp }.asSequence() else it }
        .map { it.copy(message = it.message.replace(Regex("\\s+"), " ").trim()) }
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
        .filter { comment ->
            val key = if (collapseSimilarText) danmakuKey(comment.message) else commentIdentity(comment)
            key.isNotBlank() && seen.add(key)
        }
        .toList()
}

/**
 * Что достаётся КОНКРЕТНОЙ серии.
 *
 * Здесь была причина дубликатов, которые видно не внутри серии, а МЕЖДУ сериями.
 * Общий пул тайтла отдавался целиком каждой серии подряд: смотришь пятую, шестую,
 * седьмую — и всюду те же фразы. Внутри серии повторов не было, а по ощущению они
 * были, и справедливо.
 *
 * Почему нельзя просто «только своя серия». ЗАМЕРЕНО по двенадцати страницам
 * обсуждения:
 *
 *     Деревня кузнецов   294 комментария, с указанием серии — 3
 *     High School DxD    283 комментария, с указанием серии — 15
 *     Невеста демона     247 комментариев, с указанием серии — 198 (серии 1–6)
 *
 * Поле `posted_at_episode` появилось у Anixart недавно: у выходящих сейчас тайтлов
 * оно заполнено, у прежних пустое почти везде. Строгий отбор «только своя серия»
 * оставил бы два тайтла из трёх вообще без комментариев.
 *
 * Поэтому правило такое:
 *  • реплики ЧУЖИХ серий не показываются НИКОГДА (на первой серии реплика с шестой —
 *    готовый спойлер, а пометку «спойлер» ставят далеко не всегда);
 *  • свои реплики серии берутся все;
 *  • общий пул НАРЕЗАН НА НЕПЕРЕСЕКАЮЩИЕСЯ ДОЛИ, и серия получает свою. Пятая и
 *    шестая серии не увидят ни одной общей фразы.
 */
internal fun episodeScope(comments: List<TitleComment>, episode: Int): List<TitleComment> {
    if (episode <= 0) return comments.filter { it.episode <= 0 }
    // Общий пул — это записи БЕЗ пометки серии И записи ПРОЙДЕННЫХ серий.
    //
    // Раньше сюда шли только беспометочные, и на длинном тайтле это оставляло экран
    // пустым. Замерено 23.08 на «Наруто», 141-я серия: из полутораста свежих записей
    // без пометки НЕ БЫЛО НИ ОДНОЙ, а помеченных 141-й серией — тоже ни одной (пишут
    // те, кто смотрит конец, за двухсотую). Отбор честно выбрасывал всё, и чат молчал.
    //
    // Записи серий, которые зритель УЖЕ ПРОШЁЛ, спойлером не являются по определению —
    // он это видел. Спойлерами остаются записи серий ВПЕРЕДИ, и они по-прежнему не
    // показываются никогда.
    val unscoped = comments.filter { it.episode <= 0 || it.episode < episode }
    val own = comments.filter { it.episode == episode }
    // Своих реплик хватает — общий пул НЕ подмешиваем вовсе. Именно он и создаёт
    // пересечения между сериями, а у хорошо размеченного тайтла нужды в нём нет.
    // ЗАМЕРЕНО на «Невесте демона»: серии 1–5 имеют по 24–45 своих реплик, и без
    // этой отсечки все они дополнительно делили один общий пул из 49 штук.
    if (own.size >= SLICE_TARGET) return own
    return own + episodeSlice(unscoped, episode)
}

/**
 * Доля общего пула, причитающаяся серии.
 *
 * Долей столько, чтобы в каждой было около [SLICE_TARGET] реплик; серия берёт свою
 * по номеру. Доли не пересекаются, значит подряд идущие серии не повторяются, пока
 * круг не замкнётся — при 291 общей реплике это одиннадцать серий, то есть целый
 * сезон без единого повтора.
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

/** Дорожек по вертикали — столько же и максимум реплик на экране одновременно. */
private const val LANES = 3

/** Шаг часов цикла показа. */
private const val TICK_MS = 500L

/** Какую долю высоты кадра занимает полоса с репликами (сверху). */
private const val BAND_SHARE = 0.33f

private val LANE_TOP_INSET = 12.dp

/** Доля ширины кадра под одну реплику. */
private const val WIDTH_SHARE = 0.38f

/** Границы, между которыми доля зажимается: не уже читаемого, не шире трети экрана. */
private val MIN_WIDTH = 240.dp
private val MAX_WIDTH = 560.dp

/** Отступ статичной реплики от правого края. */
private val EDGE_GAP = 16.dp

private const val FADE_MS = 600
private const val STATIC_HOLD_MS = 5_000
private const val TRAVEL_BASE_MS = 9_000
private const val TRAVEL_PER_CHAR_MS = 55
private const val TRAVEL_MAX_MS = 20_000

// Пороги отбора ОСЛАБЛЕНЫ ОСОЗНАННО. Прежний набор (12..90 знаков, букв ≥ 8,
// капса < 50%) съедал 55 реплик из 75 на живом тайтле — из-за него запас и
// кончался посреди серии. Теперь режется только настоящий мусор: «первый!!!»,
// простыни, сплошной капс, ссылки. Длинное (до 140) переносится на две строки.
private const val MIN_LEN = 10
private const val MAX_LEN = 140
private const val MIN_LETTERS = 6
private const val MAX_UPPER_SHARE = 0.7

/** Длина нормализованного ключа дедупликации (буквы и цифры, без пунктуации). */
private const val KEY_PREFIX = 40

private const val POOL_LIMIT = 80

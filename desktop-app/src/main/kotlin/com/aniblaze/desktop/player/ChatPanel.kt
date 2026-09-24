package com.aniblaze.desktop.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.model.TitleComment
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.PersistedState

/**
 * Живой чат зрителей рядом с плеером.
 *
 * Здесь ТОЛЬКО отрисовка и один цикл-водитель; всё, что решает «кто и когда
 * напишет», живёт в [ChatEngine] вне композиции — по той же причине, по которой там
 * же живёт состояние реплик поверх кадра (см. [DanmakuSession]).
 *
 * Панель существует в двух видах, и оба берут сообщения из одной и той же ленты:
 * пристыкованная сбоку ([ChatSidePanel]) и прозрачная поверх видео
 * ([ChatOverlay]). Ни один из них НЕ ставит обработчиков указателя на области
 * видео — клик по кадру обязан доходить до кадра.
 */

/**
 * Настройки чата одним свёртком.
 *
 * Свёртком, а не десятком отдельных параметров: у [VlcPlayerView] их и без того за
 * шестьдесят, и ещё девять в общем ряду сделали бы список нечитаемым.
 */
data class ChatOptions(
    val enabled: Boolean = false,
    /** Сколько виртуальных зрителей «в зале». */
    val viewers: Int = 150,
    val intensity: ChatIntensity = ChatIntensity.NORMAL,
    val speed: Float = 1f,
    /** «Всегда тихо»: ровный темп без ускорений и всплесков (см. [ChatEngine]). */
    val alwaysQuiet: Boolean = false,
    /**
     * «Только реальные зрители»: в ленту пускаются ТОЛЬКО настоящие реплики из
     * обсуждения тайтла, выдуманный зал не показывается нигде.
     *
     * Отбор идёт здесь, а не в [ChatEngine]: движок продолжает жить своей жизнью и
     * считать настроение, а лента просто не берёт у него сгенерированное.
     */
    val realOnly: Boolean = false,
    /** «Сначала популярные»: очередь настоящих реплик по убыванию голосов (см. [ChatSessions.obtain]). */
    val popularFirst: Boolean = false,
    /** Только реплики с пометкой текущей серии (см. AppSettings.chatEpisodeOnly). */
    val episodeOnly: Boolean = false,
    /** Размер текста в боковой панели, sp. */
    val fontSize: Int = 13,
    /** Ширина боковой панели, dp. */
    val panelWidth: Int = 320,
    val side: String = "right",
    /** true — вместо панели сообщения идут прозрачно поверх кадра. */
    val overlayMode: Boolean = false,
    val overlayPosition: ChatOverlayPosition = ChatOverlayPosition.BOTTOM_LEFT,
    val overlayLines: Int = 8,
    val overlayOpacity: Float = 0.85f,
    val overlayFontSize: Int = 13,
)

/** Лента: то, что уже написано, плюс живые показатели зала. */
internal class ChatFeed {
    val messages = mutableStateListOf<ChatMessage>()
    var viewers by mutableStateOf(0)
    var speed by mutableStateOf(1f)
    var mood by mutableStateOf(SceneMood.CALM)

    /** Настроение доказано замером — только тогда его показывают подписью. */
    var moodProven by mutableStateOf(false)

    /** «Только реальные зрители»: см. [ChatOptions.realOnly]. */
    var realOnly by mutableStateOf(false)

    /** Сколько настоящих реплик вообще нашлось у сезона — этим объясняется пустая лента. */
    var realAvailable by mutableStateOf(0)

    /** Загрузка / кэш / сеть: пустоту нельзя объявлять до завершения обхода. */
    var commentState by mutableStateOf(RealCommentState.LOADING)

    fun push(message: ChatMessage) {
        // Отсев ЗДЕСЬ, а не при отрисовке. Потолок ленты общий, и выдуманные реплики,
        // попав в неё, вытесняли бы настоящие: на «шторме» две сотни сообщений
        // набегают за пару минут, а настоящих на всю серию десятки — зритель увидел бы
        // пустую панель ровно потому, что его же реплики уже выдавило из буфера.
        if (!keepInChatFeed(message, realOnly)) return
        messages.add(message)
        // Потолок ленты. Держать всю историю серии незачем: прокрутка выше сотни
        // сообщений никому не нужна, а список в тысячи элементов — это уже память
        // и лишняя работа на каждой перерисовке.
        while (messages.size > BACKLOG) messages.removeAt(0)
    }

    /** Режим включили посреди серии — уже показанный выдуманный зал убирается сразу. */
    fun dropInvented() {
        messages.retainAll { keepInChatFeed(it, realOnly = true) }
    }

    private companion object {
        const val BACKLOG = 220
    }
}

enum class RealCommentState {
    LOADING,
    CACHED_REFRESHING,
    READY,
    EMPTY,
    ERROR,
}

/**
 * Своё сообщение узнаётся по ОТРИЦАТЕЛЬНОМУ номеру.
 *
 * Такие номера выдаёт только поле ввода ([ChatSidePanel]); у движка счётчик всегда
 * растёт от единицы. Отдельного признака в [ChatMessage] нет и завести его негде —
 * тип принадлежит [ChatEngine].
 */
internal fun isOwnChatMessage(message: ChatMessage): Boolean = message.id < 0

/**
 * Пускать ли реплику в ленту при включённом «только реальные зрители».
 *
 * Своё сообщение остаётся ВСЕГДА: его написал живой человек — тот самый, для кого
 * режим и включён. Пропадающая после отправки собственная строка читалась бы как
 * поломка ввода, а не как строгий отбор.
 */
internal fun keepInChatFeed(message: ChatMessage, realOnly: Boolean): Boolean =
    !realOnly || message.real || isOwnChatMessage(message)

/**
 * Чем объяснить пустую ленту в режиме «только реальные зрители».
 *
 * Пустая панель без подписи читается как сломанный чат, поэтому молчать здесь нельзя:
 * у сезона либо вовсе нет обсуждения, либо реплики есть, но идут редко, — и это два
 * разных ответа на вопрос «почему тихо».
 */
internal fun realOnlyEmptyNote(
    realAvailable: Int,
    state: RealCommentState = RealCommentState.READY,
): String = when {
    state == RealCommentState.LOADING && realAvailable <= 0 ->
        "Загружаем настоящие реплики со всех серий этого сезона…"
    state == RealCommentState.CACHED_REFRESHING && realAvailable <= 0 ->
        "Проверяем сохранённый пул сезона и догружаем новые реплики…"
    state == RealCommentState.ERROR && realAvailable <= 0 ->
        "Не удалось обновить обсуждение сезона. Повторим при следующем открытии."
    state == RealCommentState.EMPTY && realAvailable <= 0 ->
        "Во всём обсуждении этого сезона не нашлось настоящих реплик. " +
            "Выключи режим в шапке чата, чтобы вернуть зал."
    realAvailable <= 0 ->
        "В обсуждении сезона не нашлось подходящих настоящих реплик. " +
            "Выключи режим в шапке чата, чтобы вернуть зал."
    state == RealCommentState.CACHED_REFRESHING ->
        "Сохранённый пул уже доступен, новые реплики сезона догружаются. Найдено: $realAvailable."
    state == RealCommentState.LOADING ->
        "Настоящие реплики уже доступны, полный пул сезона ещё загружается. Найдено: $realAvailable."
    else ->
        "Ждём живых зрителей. Реплики берутся из общего обсуждения сезона и идут без повторов. " +
            "Всего найдено: $realAvailable."
}

/**
 * Заводит ленту и крутит её.
 *
 * [positionMs] читается ВНУТРИ цикла, а не служит ключом эффекта: позиция меняется
 * четыре раза в секунду, и как ключ она перезапускала бы цикл непрерывно. Ровно на
 * этом уже обжигались реплики поверх кадра.
 */
@Composable
internal fun rememberChatFeed(
    episodeKey: String,
    episode: Int,
    comments: List<TitleComment>,
    commentState: RealCommentState,
    freshFirst: Boolean,
    viewers: Int,
    intensity: ChatIntensity,
    alwaysQuiet: Boolean,
    speed: Float = 1f,
    /** Запасное значение «только реальные»: живое берётся из настроек, см. [rememberRealOnly]. */
    realOnly: Boolean = false,
    /** См. [ChatOptions.popularFirst]. */
    popularFirst: Boolean = false,
    /** См. [ChatOptions.episodeOnly]. */
    episodeOnly: Boolean = false,
    playing: Boolean,
    positionMs: Long,
    lengthMs: Long,
    opening: OpeningRange?,
    ending: OpeningRange?,
    energy: () -> SceneSample,
): ChatFeed {
    // Догрузка сезонного пула больше НЕ является ключом: новые комментарии сливаются
    // в существующую очередь, поэтому лента не очищается на 25/50/100-й записи.
    val engine = remember(episodeKey, popularFirst, episodeOnly) {
        ChatSessions.obtain(
            episodeKey, comments, viewers, intensity, freshFirst,
            episode = episode,
            alwaysQuiet = alwaysQuiet,
            speed = speed,
            popularFirst = popularFirst,
            episodeOnly = episodeOnly,
        )
    }
    // Смена серии начинает новую экранную ленту, но источник у неё тот же сезонный.
    val feed = remember(engine) { ChatFeed() }
    SideEffect { feed.commentState = commentState; feed.speed = normalizeChatSpeed(speed) }
    LaunchedEffect(engine, comments.size, freshFirst) {
        ChatSessions.merge(engine, comments, freshFirst, episode, popularFirst, episodeOnly)
        feed.realAvailable = engine.realUsable
    }
    // Настройки едут на лету, сессию не трогая.
    LaunchedEffect(engine, intensity, viewers, alwaysQuiet, speed) {
        engine.retune(intensity, viewers, alwaysQuiet, speed)
    }
    // Отбор ленты живёт на стороне панели, поэтому и флаг ставится здесь — до того,
    // как цикл ниже начнёт что-то в неё складывать.
    val realOnlyNow = rememberRealOnly(realOnly)
    SideEffect { feed.realOnly = realOnlyNow }
    LaunchedEffect(feed, engine, realOnlyNow) {
        engine.setRealOnly(realOnlyNow)
        if (realOnlyNow) feed.dropInvented()
    }

    val playingNow by rememberUpdatedState(playing)
    val positionNow by rememberUpdatedState(positionMs)
    val lengthNow by rememberUpdatedState(lengthMs)
    val openingNow by rememberUpdatedState(opening)
    val endingNow by rememberUpdatedState(ending)
    val energyNow by rememberUpdatedState(energy)

    LaunchedEffect(engine) {
        // Запас настоящих реплик — единственное, чем можно объяснить молчание в режиме
        // «только реальные»: «обсуждения нет» и «оно есть, но редкое» выглядят
        // одинаково, а значат разное.
        feed.realAvailable = engine.realUsable
        PlayerDiagnostics.log(
            "chat.start",
            "episode=${engine.episodeKey}; session=${engine.id}; viewers=${engine.viewers}; " +
                "real=${engine.realUsable}",
        )
        var lastPosition = positionNow
        var lastReport = 0L
        var watched = 0L
        while (true) {
            kotlinx.coroutines.delay(CHAT_TICK_MS)
            val position = positionNow
            // Перемотка опознаётся по разрыву позиции: за один такт честное
            // воспроизведение уходит вперёд на длину такта, не больше.
            //
            // Первые секунды из этого правила ИСКЛЮЧЕНЫ. По журналу живого запуска
            // видно, почему: пока плеер устраивается на точке продолжения, позиция
            // успевает сходить 0 → 72733 → 0 → 72100, и без исключения чат считал бы
            // это тремя перемотками подряд — три раза сбрасывал бы расписание и три
            // раза замолкал на паузу «подумать» ещё до первой реплики.
            if (watched >= SETTLE_MS && kotlin.math.abs(position - lastPosition) > SEEK_JUMP_MS) {
                PlayerDiagnostics.log("chat.seek", "from=$lastPosition; to=$position")
                engine.onSeek()
            }
            lastPosition = position
            // Пауза: часы стоят, новых реакций нет — ровно как просили.
            if (!playingNow) continue
            engine.accumulate(CHAT_TICK_MS)
            watched += CHAT_TICK_MS
            val sample = energyNow()
            val fresh = engine.poll(
                positionMs = position,
                lengthMs = lengthNow,
                opening = openingNow,
                ending = endingNow,
                energy = sample,
                tickMs = CHAT_TICK_MS,
            )
            fresh.forEach(feed::push)
            feed.viewers = engine.viewersNow()
            val moodBefore = feed.mood
            feed.mood = engine.moodNow
            // Подпись показывается ТОЛЬКО когда настроение доказано кадром или
            // AniSkip. Догадка расписания темп менять может, а утверждать что-то со
            // шапки — нет: «ВЕСЕЛО» над серьёзной сценой и есть то, что читается как
            // «оно неправильно определяет».
            feed.moodProven = engine.moodProven
            if (moodBefore != engine.moodNow) {
                // Каждая смена настроения — со всеми числами, которые её вызвали.
                // Без этого спорить о том, верно ли оно определяется, было бы не о чем.
                PlayerDiagnostics.log(
                    "chat.mood",
                    "${moodBefore.key} -> ${engine.moodNow.key}; proven=${engine.moodProven}; " +
                        "motion=x${"%.2f".format(sample.motionRatio)}; " +
                        "bright=x${"%.2f".format(sample.brightnessRatio)}; " +
                        "cuts=${"%.0f".format(sample.cutsPerMinute)}; cut=${sample.cutSeen}; pos=$position",
                )
            }
            // Замер по кадру — единственная часть чата, которая ЧТО-ТО УТВЕРЖДАЕТ о
            // происходящем на экране. Раз в полминуты она пишется в журнал, чтобы
            // это утверждение можно было проверить по живому просмотру, а не поверить
            // на слово. Реже — и в журнале не за что зацепиться, чаще — мусор.
            if (watched - lastReport >= ENERGY_REPORT_MS) {
                lastReport = watched
                PlayerDiagnostics.log(
                    "chat.energy",
                    "mood=${engine.moodNow.key}; known=${sample.known}; " +
                        "motion=${"%.4f".format(sample.motion)}/${"%.4f".format(sample.motionBase)}" +
                        " (x${"%.2f".format(sample.motionRatio)}); " +
                        "bright=${"%.3f".format(sample.brightness)}/${"%.3f".format(sample.brightnessBase)}" +
                        " (x${"%.2f".format(sample.brightnessRatio)}); " +
                        "cuts=${"%.1f".format(sample.cutsPerMinute)}/мин; " +
                        "sent=${engine.messagesIssued}; real=${engine.realShown}/${engine.realUsable}",
                )
            }
        }
    }
    return feed
}

/** Пристыкованная сбоку панель — основной вид чата. */
@Composable
internal fun ChatSidePanel(
    feed: ChatFeed,
    fontSize: Int,
    onHide: () -> Unit,
    onTypingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(PANEL_BACKGROUND)) {
        ChatHeader(feed, onHide)
        Box(Modifier.weight(1f)) {
            ChatList(feed, fontSize, Modifier.fillMaxSize())
        }
        ChatComposer(fontSize, onTypingChange) { text ->
            feed.push(
                ChatMessage(
                    id = -System.nanoTime(),
                    nick = "Ты",
                    color = 0xFFFF6A3D,
                    badge = null,
                    avatar = "",
                    real = false,
                    replyTo = null,
                    text = text,
                    mood = feed.mood,
                ),
            )
        }
    }
}

@Composable
private fun ChatHeader(feed: ChatFeed, onHide: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Точка «в эфире» мигает медленно — это опознавательный знак живой ленты, а
        // не индикатор, за которым следят.
        val pulse by animateFloatAsState(
            if ((feed.messages.lastOrNull()?.id ?: 0L) % 2L == 0L) 1f else 0.45f,
            tween(700),
            label = "chatPulse",
        )
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).alpha(pulse).background(Color(0xFFFF3B30)))
        Text(
            "Чат",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            // Счётчик зала — часть той же выдумки, от которой отказались: показывать
            // «150 зрителей» над лентой из одних живых людей значит врать ровно там,
            // где зритель попросил не врать.
            if (feed.realOnly) "  только реальные" else "  ${formatViewers(feed.viewers)}",
            color = Color(0xFF9C9CAB),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (feed.moodProven) MoodChip(feed.mood)
        ChatModesButton()
        IconButton(onClick = onHide, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Скрыть чат", tint = Color(0xFF9C9CAB), modifier = Modifier.size(16.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF)))
}

/**
 * Основные режимы чата прямо в шапке.
 *
 * Ради плотности и «всегда тихо» уходить на экран настроек — значит бросить серию:
 * плеер занимает всё окно, и обратно возвращаешься уже к другой сцене. Меню пишет
 * в те же [AppSettings], что и экран настроек, поэтому второго источника истины не
 * появляется — переключение видно в обоих местах сразу.
 */
@Composable
private fun ChatModesButton() {
    // Настройки берутся из общего CompositionLocal, а не из параметров: [ChatOptions]
    // едет сверху вниз и на запись не годится, а обратный путь через VlcPlayerView
    // добавил бы ему ещё четыре обработчика ради четырёх переключателей.
    val settings = com.aniblaze.desktop.ui.LocalAppSettings.current ?: return
    val modes by rememberChatModes(settings)
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = "Режимы чата",
                tint = Color(0xFF9C9CAB),
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 300.dp),
        ) {
            Text("Скорость чата", color = Color(0xFF9C9CAB),
                fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CHAT_SPEED_PRESETS.forEach { (speed, label) ->
                    val active = normalizeChatSpeed(modes.speed) == speed
                    Box(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (active) Color(0xFFFF6A3D) else Color(0x1FFFFFFF))
                        .clickable { settings.setChatSpeed(speed) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(label, fontSize = 11.sp,
                            color = if (active) Color(0xFF0C0B12) else Color(0xFFD8D8E0))
                    }
                }
            }
            Text("Частота сообщений, не скорость видео", color = Color(0xFF9C9CAB),
                fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Text("Размер сообщений · ${modes.fontSize}", color = Color(0xFF9C9CAB),
                fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CHAT_FONT_PRESETS.forEach { (size, label) ->
                    Box(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (modes.fontSize == size) Color(0xFFFF6A3D) else Color(0x1FFFFFFF))
                        .clickable { settings.setChatFontSize(size) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(label, fontSize = 11.sp,
                            color = if (modes.fontSize == size) Color(0xFF0C0B12) else Color(0xFFD8D8E0))
                    }
                }
            }
            Text(
                "Плотность",
                color = Color(0xFF9C9CAB),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            )
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChatIntensity.entries.forEach { level ->
                    val active = level == modes.intensity
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (active) Color(0xFFFF6A3D) else Color(0x1FFFFFFF))
                            .clickable { settings.setChatIntensity(level.key) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            level.label,
                            color = if (active) Color(0xFF0C0B12) else Color(0xFFD8D8E0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Color(0x1AFFFFFF)))
            ChatModeSwitch(
                title = "Всегда тихо",
                note = "Ровный темп без всплесков на драке",
                checked = modes.alwaysQuiet,
                onToggle = { settings.setChatAlwaysQuiet(it) },
            )
            ChatModeSwitch(
                title = "Только реальные зрители",
                note = "Лишь реплики живых людей из обсуждения",
                checked = modes.realOnly,
                onToggle = { settings.setChatRealOnly(it) },
            )
            ChatModeSwitch(
                title = "Поверх видео",
                // Предупреждение обязательно: включив прозрачный режим, зритель теряет
                // и саму шапку, а вместе с ней — эту кнопку. Обратно только настройками.
                note = "Вместо боковой панели; вернуть — в настройках",
                checked = modes.overlayMode,
                onToggle = { settings.setChatOverlayMode(it) },
            )
            listOf("right" to "Chat Right", "left" to "Chat Left").forEach { (side, label) ->
                DropdownMenuItem(
                    text = { Text(if (modes.side == side) "✓ $label" else label) },
                    onClick = { settings.setChatSide(side) },
                )
            }
        }
    }
}

/** Одна строка-переключатель в меню режимов. */
@Composable
private fun ChatModeSwitch(
    title: String,
    note: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!checked) }
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = com.aniblaze.desktop.ui.TextPrimary, fontSize = 13.sp)
            Text(note, color = Color(0xFF9C9CAB), fontSize = 11.sp, lineHeight = 14.sp)
        }
        // onCheckedChange = null: нажатие ловит вся строка целиком — по узкому
        // тумблеру в меню промахиваются постоянно.
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Что показывает и переключает меню режимов. */
private data class ChatModes(
    val intensity: ChatIntensity,
    val alwaysQuiet: Boolean,
    val realOnly: Boolean,
    val overlayMode: Boolean,
    val side: String,
    val fontSize: Int,
    val speed: Float,
)

/**
 * Подписка только на поля меню, а не на весь снимок настроек.
 *
 * Снимок меняется от любого сохранения, а прогресс просмотра пишется каждые несколько
 * секунд, — подписка на него целиком пересобирала бы шапку весь сеанс.
 */
@Composable
private fun rememberChatModes(settings: AppSettings): androidx.compose.runtime.State<ChatModes> {
    fun read(state: PersistedState) = ChatModes(
        intensity = ChatIntensity.of(state.chatIntensity),
        alwaysQuiet = state.chatAlwaysQuiet,
        realOnly = state.chatRealOnly,
        overlayMode = state.chatOverlayMode,
        side = state.chatSide,
        fontSize = state.chatFontSize,
        speed = state.chatSpeed,
    )
    val flow = remember(settings) { settings.state.map { read(it) }.distinctUntilChanged() }
    return flow.collectAsState(read(settings.state.value))
}

/**
 * «Только реальные зрители» — живое значение настройки.
 *
 * Читается прямо из настроек, а не только из [ChatOptions]: переключатель в шапке
 * пишет туда же, и лента обязана отзываться на него сразу, не дожидаясь, пока
 * главный поток пробросит поле сверху. [fallback] остаётся на случай, когда настроек
 * в дереве нет вовсе.
 */
@Composable
private fun rememberRealOnly(fallback: Boolean): Boolean {
    val settings = com.aniblaze.desktop.ui.LocalAppSettings.current ?: return fallback
    val flow = remember(settings) {
        settings.state.map { it.chatRealOnly }.distinctUntilChanged()
    }
    return flow.collectAsState(settings.state.value.chatRealOnly).value
}

@Composable
private fun MoodChip(mood: SceneMood) {
    val label = moodLabel(mood) ?: return
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(Color(mood.tint).copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(label, color = Color(mood.tint), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChatList(feed: ChatFeed, fontSize: Int, modifier: Modifier) {
    val listState = rememberLazyListState()
    // Автопрокрутка отключается, как только зритель сам ушёл вверх читать: дёргать
    // ленту из-под пальца — худшее, что может делать чат.
    var pinned by remember { mutableStateOf(true) }
    var following by remember { mutableStateOf(false) }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) pinned = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        // Наблюдаем ДВА ЧИСЛА, а не весь layoutInfo. Он пересчитывается на каждом
        // проходе раскладки, и подписка на него целиком означала бы поток объектов на
        // потоке интерфейса всё время, пока лента живёт, — а тот же поток опрашивает
        // libVLC с полуторасекундной отсечкой.
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (last, total) -> if (total > 0 && !following) pinned = last >= total - 2 }
    }
    // Ключ — НОМЕР последнего сообщения, а не размер списка: список упирается в
    // потолок и перестаёт расти, и на ключе-размере прокрутка встала бы намертво
    // ровно тогда, когда серия разговорится.
    LaunchedEffect(feed.messages.lastOrNull()?.id, pinned) {
        if (pinned && feed.messages.isNotEmpty()) {
            // Reveal only the new bottom edge instead of teleporting the whole
            // paragraph to the top. 0.5x slows this motion as well as message cadence.
            following = true
            try {
                withFrameNanos { } // new message must first participate in layout
                var info = listState.layoutInfo
                while (info.visibleItemsInfo.none { it.index == feed.messages.lastIndex } && listState.canScrollForward) {
                    if (info.viewportEndOffset <= info.viewportStartOffset) break
                    listState.animateScrollBy((info.viewportEndOffset - info.viewportStartOffset) / 2f,
                        tween(chatScrollDurationMs(feed.speed)))
                    info = listState.layoutInfo
                }
                val last = info.visibleItemsInfo.lastOrNull { it.index == feed.messages.lastIndex }
                val viewport = info.viewportEndOffset - info.viewportStartOffset
                // For a paragraph taller than the panel, keep its beginning readable.
                val overflow = last?.let { it.offset + minOf(it.size, viewport) - info.viewportEndOffset } ?: 0
                if (overflow > 0) listState.animateScrollBy(overflow.toFloat(), tween(chatScrollDurationMs(feed.speed)))
            } finally { following = false }
        }
    }
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(userScroll),
            contentPadding = PaddingValues(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            itemsIndexed(feed.messages, key = { _, m -> m.id }) { index, message ->
                // Ответ рисуется веткой под записью; если родитель выше уже уехал
                // (или его нет в ленте) — с подписью, на что это ответ.
                val previous = feed.messages.getOrNull(index - 1)
                val underParent = message.parentKey.isNotBlank() &&
                    previous != null && (previous.threadKey == message.parentKey || previous.parentKey == message.parentKey)
                ChatRow(message, fontSize, showRealBadge = !feed.realOnly, underParent = underParent)
            }
        }
        // Пустая панель без объяснения читается как сломанный чат. В обычном режиме
        // объяснять нечего — первая реплика приходит через секунды; молчание бывает
        // только когда зал выключен целиком.
        if (feed.realOnly && feed.messages.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Только реальные зрители",
                    color = Color(0xFF4ADE80),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    realOnlyEmptyNote(feed.realAvailable, feed.commentState),
                    color = Color(0xFF5E5E6B),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (!pinned) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFF6A3D))
                    .clickable { pinned = true }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text("К новым сообщениям", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ChatRow(message: ChatMessage, fontSize: Int, showRealBadge: Boolean, underParent: Boolean = false) {
    var spoilerRevealed by remember(message.id) { mutableStateOf(false) }
    val isReply = message.parentKey.isNotBlank()
    val shownMessage = if (message.potentialSpoiler && !spoilerRevealed) {
        message.copy(text = chatPanelPreviewText(message, revealed = false), replyTo = null)
    } else {
        message
    }
    val textModifier = Modifier.padding(start = 7.dp).let { base ->
        if (message.potentialSpoiler && !spoilerRevealed) {
            base.clickable { spoilerRevealed = true }
        } else {
            base
        }
    }
    // Заплюсованная реплика — как «выделенное сообщение» на Twitch: полоса слева и
    // лёгкая заливка ряда в цвет приложения. Порог — относительно этой сессии.
    val tier = message.voteTier
    val highlight = tier.color
    Row(
        Modifier.fillMaxWidth()
            // Ветка: ответ сдвинут вправо, слева — линия ветки, как в Reddit.
            .padding(start = if (isReply) 22.dp else 6.dp, end = 6.dp, top = 1.dp, bottom = 1.dp)
            .then(
                if (isReply) {
                    Modifier.drawBehind {
                        drawRect(
                            com.aniblaze.desktop.ui.Surface4,
                            topLeft = androidx.compose.ui.geometry.Offset(-10.dp.toPx(), 0f),
                            size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .then(
                if (highlight != null) {
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(highlight.copy(alpha = tier.fill))
                        .drawBehind {
                            drawRect(highlight, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                        }
                        .padding(start = 4.dp)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Аватарка есть только у настоящих комментариев — у выдуманных зрителей её
        // взять неоткуда, и рисовать заглушку честнее, чем чужое лицо.
        if (message.avatar.isNotBlank()) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(com.aniblaze.desktop.ui.Surface3)) {
                AsyncImage(
                    model = message.avatar,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        } else {
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(9.dp))
                    .background(Color(message.color).copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    message.nick.take(1).uppercase(),
                    color = Color(message.color),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(textModifier.fillMaxWidth()) {
            if (isReply && !underParent) {
                Text(
                    "↳ ответ ${message.replyTo ?: "на запись выше"}",
                    color = com.aniblaze.desktop.ui.TextTertiary,
                    fontSize = (fontSize - 3).coerceAtLeast(8).sp,
                    lineHeight = (fontSize - 1).coerceAtLeast(9).sp,
                )
            }
            Text(
                chatLine(shownMessage, fontSize, showRealBadge),
                fontSize = fontSize.sp,
                lineHeight = chatLineHeight(fontSize).sp,
            )
        }
    }
}

/** Прозрачный чат поверх видео. */
@Composable
internal fun ChatOverlay(
    feed: ChatFeed,
    position: ChatOverlayPosition,
    lines: Int,
    opacity: Float,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    // Без remember намеренно. Ключом напрашивается messages.size, но лента упирается
    // в потолок в 220 штук и дальше размер НЕ МЕНЯЕТСЯ — с таким ключом прозрачный
    // чат замер бы навсегда, стоило серии дойти до трёхсотого сообщения. Чтение
    // самого списка подписывает на любое изменение, а takeLast по двум сотням
    // элементов не стоит ничего.
    val shown = feed.messages.takeLast(lines.coerceAtLeast(1))
    if (shown.isEmpty()) return
    // НИ ОДНОГО обработчика указателя: панель управления и клик по кадру должны
    // работать так, будто чата здесь нет.
    Box(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Column(
            Modifier.align(position.alignment).widthIn(max = OVERLAY_MAX_WIDTH).alpha(opacity),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            shown.forEach { message ->
                // Поверх кадра нет кнопки «раскрыть» и не должно быть полного текста
                // потенциального спойлера. Сам чат хранит исходную реплику целиком;
                // здесь меняется только автоматическое превью.
                val overlayMessage = if (message.potentialSpoiler) {
                    message.copy(
                        nick = "Чат",
                        badge = null,
                        real = false,
                        replyTo = null,
                        text = chatOverlayPreviewText(message),
                    )
                } else {
                    message
                }
                Box(
                    Modifier.clip(RoundedCornerShape(7.dp))
                        .background(Color(0x59000000))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        chatLine(overlayMessage, fontSize, showRealBadge = !feed.realOnly),
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.3).sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal const val HIDDEN_SPOILER_TEXT = "Возможный спойлер · нажмите, чтобы показать"

internal fun chatPanelPreviewText(message: ChatMessage, revealed: Boolean): String =
    if (message.potentialSpoiler && !revealed) HIDDEN_SPOILER_TEXT else message.text

internal fun chatOverlayPreviewText(message: ChatMessage): String =
    if (message.potentialSpoiler) NEW_MESSAGE_TEXT else message.text

/** Куда класть прозрачный чат. */
enum class ChatOverlayPosition(val key: String, val label: String, val alignment: Alignment) {
    TOP_LEFT("topLeft", "Слева сверху", Alignment.TopStart),
    TOP_RIGHT("topRight", "Справа сверху", Alignment.TopEnd),
    BOTTOM_LEFT("bottomLeft", "Слева снизу", Alignment.BottomStart),
    BOTTOM_RIGHT("bottomRight", "Справа снизу", Alignment.BottomEnd),
    ;

    companion object {
        fun of(key: String): ChatOverlayPosition = entries.firstOrNull { it.key == key } ?: BOTTOM_LEFT
    }
}

/** Поле ввода: своё сообщение попадает в ту же ленту. */
@Composable
private fun ChatComposer(
    fontSize: Int,
    onTypingChange: (Boolean) -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    fun send() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }
    Box(Modifier.fillMaxWidth().padding(8.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { if (it.length <= MAX_INPUT) text = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = fontSize.sp),
            cursorBrush = SolidColor(Color(0xFFFF6A3D)),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            // Пока курсор в поле, горячие клавиши плеера ОБЯЗАНЫ молчать: иначе
            // пробел в слове ставит видео на паузу, а «f» разворачивает на весь экран.
            // Отсюда и уходит сигнал наружу — см. chatTyping в VlcPlayerView.
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { onTypingChange(it.isFocused) }
                // Enter на настольной системе до keyboardActions доходит не всегда —
                // ловим его здесь и не пускаем дальше.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        send()
                        true
                    } else {
                        false
                    }
                }
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x14FFFFFF))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text("Написать в чат…", color = Color(0xFF5E5E6B), fontSize = fontSize.sp)
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * Одна строка чата: ник, значок персоны, обращение и текст с эмоциями.
 *
 * Эмоции рисуются не картинками, а цветной подложкой под словом — приложение не
 * тащит спрайты сторонних сервисов, а узнаваемость эмоции даёт цвет и значок.
 */
internal fun chatLine(
    message: ChatMessage,
    fontSize: Int,
    /**
     * Значок «ЗРИТЕЛЬ» отличает настоящую реплику от выдуманной. В режиме «только
     * реальные» отличать не от чего — значок стоял бы на каждой строке и превратился
     * бы из метки в фон.
     */
    showRealBadge: Boolean = true,
): AnnotatedString = buildAnnotatedString {
    message.badge?.let { badge ->
        withStyle(
            SpanStyle(
                color = Color(0xFF0C0B12),
                background = Color(message.color),
                fontWeight = FontWeight.Bold,
                fontSize = (fontSize - 3).coerceAtLeast(8).sp,
            ),
        ) { append(" $badge ") }
        append(" ")
    }
    if (message.real && showRealBadge) {
        withStyle(
            SpanStyle(
                color = Color(0xFF0C0B12),
                background = Color(0xFF4ADE80),
                fontWeight = FontWeight.Bold,
                fontSize = (fontSize - 3).coerceAtLeast(8).sp,
            ),
        ) { append(" ЗРИТЕЛЬ ") }
        append(" ")
    }
    withStyle(SpanStyle(color = Color(message.color), fontWeight = FontWeight.Bold)) {
        append(message.nick)
    }
    // Откуда настоящая реплика — коротко и тускло, чтобы не спорить с ником.
    chatSourceTag(message.source)?.let { tag ->
        withStyle(SpanStyle(color = com.aniblaze.desktop.ui.TextTertiary, fontSize = (fontSize - 4).coerceAtLeast(8).sp)) {
            append(" $tag")
        }
    }
    // Сколько поддержки собрала настоящая реплика — «▲222», мелко; у заметных — в цвет.
    if (message.real && message.votes > 0) {
        val tone = message.voteTier.color ?: Color(0xFF8FA98F)
        withStyle(SpanStyle(color = tone, fontSize = (fontSize - 4).coerceAtLeast(8).sp, fontWeight = FontWeight.SemiBold)) {
            append(" ▲${chatVotesLabel(message.votes)}")
        }
    }
    withStyle(SpanStyle(color = Color(0xFF5E5E6B))) { append(": ") }
    // Кому отвечают — только у выдуманных перекличек; настоящие ответы рисуются
    // веткой под записью (см. ChatRow), и «@ник» в строке им не нужен.
    if (!message.real) {
        message.replyTo?.let { target ->
            withStyle(SpanStyle(color = Color(0xFF7FD1FF), fontWeight = FontWeight.SemiBold)) {
                append("@$target ")
            }
        }
    }
    val words = message.text.split(' ')
    words.forEachIndexed { index, word ->
        val emote = ChatEmotes.find(word)
        if (emote != null) {
            withStyle(
                SpanStyle(
                    color = Color(emote.color),
                    background = Color(emote.color).copy(alpha = 0.16f),
                    fontWeight = FontWeight.Bold,
                ),
            ) { append(" ${emote.glyph} ${emote.code} ") }
        } else {
            withStyle(SpanStyle(color = Color(0xFFD8D8E0))) { append(word) }
        }
        if (index < words.lastIndex) append(" ")
    }
}

/** Подпись настроения в шапке. У обычного хода серии подписи нет — нечего подписывать. */
internal fun moodLabel(mood: SceneMood): String? = when (mood) {
    SceneMood.OPENING -> "ОПЕНИНГ"
    SceneMood.ENDING -> "ЭНДИНГ"
    SceneMood.FIGHT -> "ЭКШЕН"
    SceneMood.TWIST -> "ПОВОРОТ"
    SceneMood.SAD -> "ТИХО"
    SceneMood.FUNNY -> "ВЕСЕЛО"
    SceneMood.HYPE -> "НАПРЯЖЕНИЕ"
    SceneMood.CALM -> null
}

private val SceneMood.tint: Long
    get() = when (this) {
        SceneMood.OPENING, SceneMood.ENDING -> 0xFF9D5CFF
        SceneMood.FIGHT -> 0xFFFF6A3D
        SceneMood.TWIST -> 0xFF2DE2E6
        SceneMood.SAD -> 0xFF7FD1FF
        SceneMood.FUNNY -> 0xFFFFC24B
        SceneMood.HYPE -> 0xFFFF4D9D
        SceneMood.CALM -> 0xFF9C9CAB
    }

/** «1 204» вместо «1204»: в шапке это читается заметно быстрее. */
internal fun formatViewers(count: Int): String {
    val digits = count.toString()
    // Разделитель — НЕРАЗРЫВНЫЙ пробел: обычный позволил бы шапке перенести строку
    // посреди числа и показать «1» на одной строке, «204 зрителя» на другой.
    val grouped = digits.reversed().chunked(3).joinToString(NBSP).reversed()
    val word = when {
        digits.endsWith("11") || digits.endsWith("12") || digits.endsWith("13") || digits.endsWith("14") -> "зрителей"
        digits.endsWith("1") -> "зритель"
        digits.last() in '2'..'4' -> "зрителя"
        else -> "зрителей"
    }
    return "$grouped $word"
}

/** Значок «чат» для панели управления. */
internal val ChatIcon = Icons.Filled.Chat

private val PANEL_BACKGROUND = Color(0xFF0C0B12)
private val OVERLAY_MAX_WIDTH = 420.dp

/** Такт ленты. Реже — и всплеск на драке рассыпается, чаще — работа впустую. */
internal const val CHAT_TICK_MS = 400L

/** Разрыв позиции, после которого это уже перемотка, а не ход воспроизведения. */
private const val SEEK_JUMP_MS = 3_000L

/** Как часто замер кадра уходит в журнал. */
private const val ENERGY_REPORT_MS = 30_000L

/** Сколько просмотра должно накопиться, прежде чем скачок позиции считать перемоткой. */
private const val SETTLE_MS = 5_000L

private const val MAX_INPUT = 200

/** Неразрывный пробел — разделитель разрядов в счётчике зрителей. */
internal const val NBSP = " "

/** Короткая подпись источника настоящей реплики; null — источник неизвестен/не нужен. */
internal fun chatSourceTag(source: String): String? = when (source.lowercase()) {
    "anixart" -> "Anixart"
    "yummy" -> "Yummy"
    "" -> null
    else -> source.replaceFirstChar { it.uppercase() }
}

/** «222», «1,2к» — компактно, чтобы не расталкивать строку. */
internal fun chatVotesLabel(votes: Int): String = when {
    votes >= 10_000 -> "${votes / 1000}к"
    votes >= 1_000 -> "%.1fк".format(java.util.Locale.ROOT, votes / 1000.0).replace(".0к", "к").replace('.', ',')
    else -> votes.toString()
}

/**
 * Уровень выделения по голосам — считается движком относительно пула сессии
 * (см. ChatEngine.voteTierOf). Цвета — палитра приложения: акцент и «Легенда».
 */
internal enum class ChatVoteTier(val color: Color?, val fill: Float) {
    NONE(null, 0f),
    NOTABLE(Color(0xFFFF6A3D), 0.10f),
    TOP(Color(0xFFFFC24B), 0.14f),
}

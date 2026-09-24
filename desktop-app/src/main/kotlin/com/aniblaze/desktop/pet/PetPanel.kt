package com.aniblaze.desktop.pet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniblaze.aggregator.model.Anime
import kotlin.math.roundToInt

/**
 * Питомец в обычном интерфейсе: постоянный угол экрана, по клику — карточка со
 * статистикой тайтла, кнопкой «продолжить» и рекомендацией из уже существующего
 * движка рекомендаций AniBlaze.
 */
@Composable
fun PetCorner(
    pet: PetDef,
    mood: PetMood,
    scale: Float,
    say: String?,
    stats: List<String>,
    statsTitle: String?,
    /** Почему речь именно об этом тайтле: «Сейчас смотрим», «Последнее из истории». */
    statsSubtitle: String? = null,
    resume: Pair<String, () -> Unit>?,
    recommendation: PetRecommendation?,
    onAnotherRecommendation: (() -> Unit)?,
    onOpenRecommendation: ((Anime) -> Unit)?,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    diary: List<String> = emptyList(),
    onInteract: () -> Unit = {},
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val dragNow by rememberUpdatedState(onDrag)
    val dragEndNow by rememberUpdatedState(onDragEnd)
    var tapped by remember(pet.id) { mutableStateOf(false) }
    LaunchedEffect(tapped, pet.id) {
        if (tapped) { kotlinx.coroutines.delay(pet.clip(PetAction.WAVE).durations.sum()); tapped = false }
    }
    val accent = Color(pet.accent)
    BoxWithConstraints(modifier) {
    val cardRoom = (maxHeight - 60.dp - (96 * scale).dp).coerceAtLeast(0.dp)
    Column(horizontalAlignment = Alignment.End) {
        AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .heightIn(max = cardRoom).verticalScroll(rememberScrollState())
                    .clip(com.aniblaze.desktop.ui.Shapes.card)
                    .background(com.aniblaze.desktop.ui.Surface2)
                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), com.aniblaze.desktop.ui.Shapes.card)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        statsTitle ?: pet.displayName,
                        color = com.aniblaze.desktop.ui.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Скрыть питомца",
                        tint = com.aniblaze.desktop.ui.TextTertiary,
                        modifier = Modifier.size(16.dp).clickable { onHide() },
                    )
                }
                // Заголовок — название тайтла, и без подписи непонятно, ОТКУДА он взялся:
                // на «Главной» это последний тайтл истории, а выглядело как случайный.
                statsSubtitle?.takeIf { it.isNotBlank() }?.let { hint ->
                    Text(hint, color = accent, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
                stats.forEach { line ->
                    Text(DrizzDialogue.text(pet.id, line), color = com.aniblaze.desktop.ui.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (diary.isNotEmpty()) {
                    Text("Дневник просмотра", color = accent, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                    diary.forEach { line ->
                        Text(line, color = com.aniblaze.desktop.ui.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                // Карточка без единой строки выглядела сломанной: заголовок, крестик и
                // пустота. Говорим прямо, что данных ещё нет.
                if (stats.isEmpty() && resume == null && recommendation == null) {
                    Text(
                        DrizzDialogue.text(pet.id, if (statsTitle.isNullOrBlank()) "Открой любой тайтл — расскажу про него" else "Собираю данные об этом тайтле…"),
                        color = com.aniblaze.desktop.ui.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                resume?.let { (label, action) ->
                    Row(
                        Modifier.padding(top = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(accent)
                            .clickable { action() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
                        Text(label, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                recommendation?.let { rec ->
                    Spacer(Modifier.height(10.dp))
                    Text(DrizzDialogue.text(pet.id, rec.reason), color = com.aniblaze.desktop.ui.TextSecondary, fontSize = 12.sp)
                    Row(
                        Modifier.padding(top = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(com.aniblaze.desktop.ui.Surface3)
                            .clickable { onOpenRecommendation?.invoke(rec.anime) }
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = com.aniblaze.desktop.ui.posterUrl(rec.anime.poster, 160),
                            contentDescription = null,
                            modifier = Modifier.size(width = 34.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                        Text(
                            rec.anime.title,
                            color = com.aniblaze.desktop.ui.TextPrimary,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                        )
                    }
                    onAnotherRecommendation?.let { another ->
                        Row(
                            Modifier.padding(top = 6.dp).clickable { another() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
                            Text("Покажи другое", color = accent, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
        // Слот под реплику зарезервирован: спрайт не прыгает, когда фраза появляется.
        Box(Modifier.size(width = 230.dp, height = 60.dp), contentAlignment = Alignment.BottomEnd) {
            say?.takeIf { !expanded }?.let { text ->
                Text(
                    DrizzDialogue.text(pet.id, text),
                    color = com.aniblaze.desktop.ui.TextPrimary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 230.dp)
                        .padding(bottom = 4.dp)
                        .clip(com.aniblaze.desktop.ui.Shapes.chip)
                        // По реплике жали — и ничего не происходило. Теперь она открывает
                        // карточку так же, как клик по самому питомцу.
                        .clickable { expanded = true; onInteract() }
                        .background(com.aniblaze.desktop.ui.Surface2)
                        .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), com.aniblaze.desktop.ui.Shapes.chip)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        PetSprite(
            pet = pet,
            action = if (tapped) PetAction.WAVE else petActionFor(mood),
            animate = animate,
            modifier = Modifier
                .size(width = (88 * scale).dp, height = (96 * scale).dp)
                .semantics { contentDescription = "Питомец ${pet.displayName}" }
                .then(if (onDrag != null) Modifier.pointerInput(pet.id) {
                    detectDragGestures(onDragStart = { expanded = false; tapped = false; onInteract() },
                        onDragEnd = { dragEndNow() }, onDragCancel = { dragEndNow() }) { change, delta ->
                        change.consume(); dragNow?.invoke(delta)
                    }
                } else Modifier)
                .clickable { expanded = !expanded; tapped = true; onInteract() },
        )
    }
}
}

/** Рекомендация с обоснованием — берётся из уже существующих рекомендаций AniBlaze. */
data class PetRecommendation(val anime: Anime, val reason: String)

/**
 * Окно «как тебе аниме?» — питомец просит оценку, когда сезон закончился.
 *
 * Звёзды здесь те же, что на странице тайтла ([com.aniblaze.desktop.ui.RatingStars]),
 * и пишут в то же хранилище: отдельной «оценки от питомца» не существует, иначе две
 * оценки одного тайтла разошлись бы. Окно закрывается крестиком, по оценке и само —
 * висеть поверх кадра вечно оно не должно.
 */
@Composable
fun PetRateCard(
    title: String,
    poster: String,
    rating: Int,
    onRate: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Цвет персонажа — обводка окна в его тон, а не в общий акцент приложения. */
    accent: Color = com.aniblaze.desktop.ui.AccentOrange,
    character: String = "claude",
) {
    Column(
        modifier.width(320.dp)
            .clip(com.aniblaze.desktop.ui.Shapes.card)
            .background(com.aniblaze.desktop.ui.Surface2)
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.55f)), com.aniblaze.desktop.ui.Shapes.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (poster.isNotBlank()) {
                AsyncImage(
                    model = com.aniblaze.desktop.ui.posterUrl(poster, 200),
                    contentDescription = null,
                    modifier = Modifier.size(width = 46.dp, height = 66.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            Column(Modifier.weight(1f).padding(start = if (poster.isNotBlank()) 10.dp else 0.dp)) {
                Text(
                    DrizzDialogue.text(character, "Сезон закончен. Как тебе?"),
                    color = com.aniblaze.desktop.ui.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (title.isNotBlank()) {
                    Text(
                        title,
                        color = com.aniblaze.desktop.ui.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = "Закрыть",
                tint = com.aniblaze.desktop.ui.TextTertiary,
                modifier = Modifier.size(16.dp).clickable { onClose() },
            )
        }
        com.aniblaze.desktop.ui.RatingStars(
            rating = rating,
            onRate = { score -> onRate(score); if (score > 0) onClose() },
            starSize = 24.dp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Питомец поверх плеера: компактно, в углу, с перетаскиванием. Позиция хранится в
 * долях окна, поэтому переживает смену размера и полноэкранный режим; по краям —
 * отступ, чтобы питомец и реплика не уезжали за границу и не лезли на субтитры и
 * панель управления (нижняя треть по центру остаётся свободной).
 */
@Composable
fun PetPlayerOverlay(
    pet: PetDef,
    mood: PetMood,
    scale: Float,
    say: String?,
    xFraction: Float,
    yFraction: Float,
    onMove: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    /** Клик по реплике (например, «вернуться на 14:32»); null — реплика не кнопка. */
    onSayClick: (() -> Unit)? = null,
    onPetClick: (() -> Unit)? = null,
    onDragStart: () -> Unit = {},
    /** Темп анимации (скорость воспроизведения). */
    tempo: Float = 1f,
    animate: Boolean = true,
    avoidRight: Boolean = false,
    avoidLeft: Boolean = false,
    /** Окно просьбы — у верхнего края, вне субтитров. */
    card: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        // Реплика-кнопка красится в цвет ПЕРСОНАЖА, а не в оранжевый приложения:
        // оранжевая плашка над чёрным Drizz с зелёными глазами выглядела чужой.
        // Текст — чёрный или белый по яркости заливки, чтобы читался на любом цвете.
        val accent = Color(pet.accent)
        val onAccent = if (accent.luminance() > 0.45f) Color.Black else Color.White
        val usableWidth = if (avoidRight || avoidLeft) maxWidth * 0.5f else maxWidth
        val petW = minOf((72 * scale).dp, usableWidth)
        val petH = (80 * scale).dp
        // Место под реплику зарезервировано ВСЕГДА: раньше пузырь появлялся над
        // спрайтом и сдвигал его вниз на секунду — «телепортация» при каждой фразе.
        // Место под реплику: три строки — длинные фразы («Мы провели за «…» 4 ч 18 мин»)
        // не обрезаются; ширина растёт с размером питомца.
        val bubbleW = minOf((200 * scale.coerceAtLeast(1f)).dp, usableWidth)
        val bubbleH = 58.dp
        val blockW = maxOf(petW, bubbleW)
        val blockH = petH + bubbleH
        val originX = if (avoidLeft) maxWidth * 0.5f else 0.dp
        val maxX = (usableWidth - blockW).coerceAtLeast(0.dp)
        // Keep the whole bubble + sprite above subtitles and the transport bar.
        val maxY = petSafeVerticalSpace(maxHeight.value, blockH.value).dp
        val minY = minOf(48.dp, maxY)
        // Позиция ведётся локально: сохранение в настройки не должно дёргать
        // перетаскивание, поэтому remember ключуется только размером окна.
        var dragX by remember(maxX, xFraction) { mutableStateOf(maxX.value * xFraction.coerceIn(0f, 1f)) }
        var dragY by remember(maxY, yFraction) { mutableStateOf((maxY.value * yFraction.coerceIn(0f, 1f)).coerceAtLeast(minY.value)) }
        var tapped by remember(pet.id) { mutableStateOf(false) }
        var cardSize by remember { mutableStateOf(IntSize.Zero) }
        val latestDragStart by rememberUpdatedState(onDragStart)
        LaunchedEffect(tapped, pet.id) {
            if (tapped) { kotlinx.coroutines.delay(pet.clip(PetAction.WAVE).durations.sum()); tapped = false }
        }
        Column(
            Modifier.offset { IntOffset((originX + dragX.dp).roundToPx(), dragY.dp.roundToPx()) }
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragStart = { tapped = false; latestDragStart() },
                        onDragEnd = {
                            onMove(
                                if (maxX.value > 0f) dragX / maxX.value else 0f,
                                if (maxY.value > 0f) dragY / maxY.value else 0f,
                            )
                        },
                    ) { change, delta ->
                        change.consume()
                        dragX = (dragX + delta.x / density).coerceIn(0f, maxX.value)
                        dragY = (dragY + delta.y / density).coerceIn(minY.value, maxY.value)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(width = bubbleW, height = bubbleH), contentAlignment = Alignment.BottomCenter) {
                say?.let { text ->
                    // Пузырь — в стиле плашек приложения: поверхность Surface2, скругление
                    // как у чипов, тонкая обводка в акцент приложения; кнопка — сплошной акцент.
                    Text(
                        DrizzDialogue.text(pet.id, text),
                        color = if (onSayClick != null) onAccent else com.aniblaze.desktop.ui.TextPrimary,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (onSayClick != null) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = bubbleW)
                            .padding(bottom = 3.dp)
                            .clip(com.aniblaze.desktop.ui.Shapes.chip)
                            .background(if (onSayClick != null) accent else com.aniblaze.desktop.ui.Surface2.copy(alpha = 0.94f))
                            .then(
                                if (onSayClick != null) {
                                    Modifier.clickable { onSayClick() }
                                } else {
                                    Modifier.border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), com.aniblaze.desktop.ui.Shapes.chip)
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            PetSprite(pet = pet, action = if (tapped) PetAction.WAVE else petActionFor(mood),
                modifier = Modifier.size(width = petW, height = petH)
                    .semantics { contentDescription = "Питомец ${pet.displayName}" }
                    .clickable { tapped = true; onPetClick?.invoke() }, tempo = tempo, animate = animate)
        }
        card?.let { content ->
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val cardWidth = minOf(280.dp, usableWidth)
            val cardHeight = cardSize.height / density
            val petLeft = originX.value + dragX + (blockW.value - petW.value) / 2
            val petTop = dragY + bubbleH.value
            val placeBeside = petTop < cardHeight + 8f && petLeft - originX.value >= cardWidth.value + 8f
            val left = (if (placeBeside) petLeft - cardWidth.value - 8f else originX.value + dragX + (blockW.value - cardWidth.value) / 2)
                .coerceIn(originX.value, (originX + usableWidth - cardWidth).value.coerceAtLeast(originX.value))
            val top = if (placeBeside) (petTop + (petH.value - cardHeight) / 2).coerceIn(0f, (maxHeight.value - cardHeight).coerceAtLeast(0f))
                else petCardTop(petTop, petH.value, cardHeight, maxHeight.value)
            Box(Modifier.offset { IntOffset((left * density).roundToInt(), (top * density).roundToInt()) }
                .width(cardWidth).onSizeChanged { cardSize = it }) { content() }
        }
    }
}

/** Позиция по умолчанию: правый верхний угол — там нет ни субтитров, ни управления. */
const val PET_DEFAULT_X = 0.94f
const val PET_DEFAULT_Y = 0.12f

internal fun petSafeVerticalSpace(height: Float, blockHeight: Float): Float =
    (height * 0.55f - blockHeight).coerceAtLeast(0f)

/** Округление доли до пикселей — вынесено ради теста. */
fun petPixelPosition(fraction: Float, available: Float): Float =
    (available * fraction.coerceIn(0f, 1f)).coerceIn(0f, available.coerceAtLeast(0f)).let { it.roundToInt().toFloat() }

/**
 * Питомец в плеере целиком: что сказать и как выглядеть, решает [PetDirector].
 *
 * Здесь только сбор снимка: позиция, пауза, буферизация, сколько серий подряд и
 * сколько длится заход. Реплика держится [PET_SAY_MS] и гаснет; перетаскивание
 * питомца на состояние не влияет — позиция живёт отдельно от речи.
 */
@Composable
fun PetPlayerHost(
    pet: PetDef,
    scale: Float,
    xFraction: Float,
    yFraction: Float,
    onMove: (Float, Float) -> Unit,
    episode: Int,
    episodesAvailable: Int,
    episodesTotal: Int,
    playing: Boolean,
    buffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    /** Перемотка плеера — для «вернуться на 14:32» после отлучки. */
    onSeek: ((Long) -> Unit)? = null,
    /** Кулдаун необязательных реплик, мс. */
    chatterCooldownMs: Long = PetDirector.CHATTER_COOLDOWN_MS,
    /** Память и контекст тайтла (см. [PetPlayerContext]); пусто — питомец про это молчит. */
    context: PetPlayerContext = PetPlayerContext(),
    /** Скорость воспроизведения: темп анимации и повод для реакции. */
    speed: Float = 1f,
    /** Перейти к следующей серии — для «мотаем эндинг?»; null — предлагать нечего. */
    onNextEpisode: (() -> Unit)? = null,
    session: PetPlaybackSession? = null,
    userPaused: Boolean = false,
    pauseStartedAtMs: Long = 0L,
    ended: Boolean = false,
    playbackError: Boolean = false,
    endingConfirmed: Boolean = false,
    speechEnabled: Boolean = true,
    quietWatching: Boolean = false,
    openedPositionMs: Long? = null,
    animate: Boolean = true,
    avoidRight: Boolean = false,
    avoidLeft: Boolean = false,
) {
    val playbackSession = session ?: remember { PetPlaybackSession() }
    val director = playbackSession.director
    val nextEpisodeNow by rememberUpdatedState(onNextEpisode)
    director.chatterCooldownMs = chatterCooldownMs
    val contextNow by rememberUpdatedState(context)
    val speedNow by rememberUpdatedState(speed)
    val userPausedNow by rememberUpdatedState(userPaused)
    val endedNow by rememberUpdatedState(ended)
    val errorNow by rememberUpdatedState(playbackError)
    val endingNow by rememberUpdatedState(endingConfirmed)
    val speechNow by rememberUpdatedState(speechEnabled)
    val quietNow by rememberUpdatedState(quietWatching)

    // Перемотки: скачок позиции больше, чем прошло времени, — вперёд или назад.
    // Считаем за последнюю минуту; несколько подряд — повод оглянуться.
    val rewindTimes = remember(episode) { ArrayDeque<Long>() }
    val forwardTimes = remember(episode) { ArrayDeque<Long>() }
    var lastTickPosition by remember(episode) { mutableStateOf(positionMs) }
    var lastTickAt by remember(episode) { mutableStateOf(System.currentTimeMillis()) }
    var seekBackwards by remember(episode) { mutableStateOf(false) }
    var titleCardUntil by remember(pet.id, episode) { mutableStateOf(0L) }
    // Позиция, с которой серия открылась: «осталось N мин с прошлого просмотра».
    val openedAt = remember(episode) { openedPositionMs ?: positionMs }
    // Источник в начале серии; сменился — «переключил источник».
    var initialSource by remember(episode) { mutableStateOf<String?>(null) }
    // Тик живёт долго, а параметры composable внутри корутины НЕ обновляются: без
    // rememberUpdatedState питомец навсегда видел бы позицию и паузу такими, какими
    // они были в момент запуска — отсюда «молчит и не реагирует на паузу».
    val playingNow by rememberUpdatedState(playing)
    val bufferingNow by rememberUpdatedState(buffering)
    val positionNow by rememberUpdatedState(positionMs)
    val durationNow by rememberUpdatedState(durationMs)
    val episodeNow by rememberUpdatedState(episode)
    val availableNow by rememberUpdatedState(episodesAvailable)
    val totalNow by rememberUpdatedState(episodesTotal)
    var say by remember(pet.id, episode) { mutableStateOf<String?>(null) }
    var sayAt by remember(pet.id, episode) { mutableStateOf(0L) }
    var mood by remember(pet.id, episode) { mutableStateOf(PetMood.IDLE) }
    // Просьба оценить сезон: показывается один раз на серию-финал и закрывается
    // оценкой, крестиком или сама — висеть поверх кадра она не должна.
    var ask by remember(pet.id, episode) { mutableStateOf<PetAsk?>(null) }
    var askedFor by remember(episode) { mutableStateOf(-1) }
    var askAt by remember(episode) { mutableStateOf(0L) }
    // «Мотаем эндинг?» — реплика-кнопка на хвосте серии. Нажали или серия
    // сменилась — предложение на эту серию больше не возвращается.
    var skipOffer by remember(episode) { mutableStateOf(false) }
    var skipDoneFor by remember(episode) { mutableStateOf(-1) }

    // Пауза и возврат: питомец засыпает и здоровается позицией, с которой продолжили.
    var pausedSince by remember(episode) { mutableStateOf(pauseStartedAtMs) }
    var resumedFrom by remember(episode) { mutableStateOf(0L) }
    LaunchedEffect(episode, playing, userPaused, buffering, ended) {
        say = null
        titleCardUntil = 0L
        if (playing) {
            // Возврат после заметной паузы — повод поздороваться, короткой паузы нет.
            val paused = if (pausedSince > 0) System.currentTimeMillis() - pausedSince else 0L
            resumedFrom = if (paused >= RESUME_GREETING_AFTER_MS) positionNow else 0L
            pausedSince = 0L
        } else if (userPaused && !buffering && !ended) {
            if (pausedSince == 0L) pausedSince = pauseStartedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis()
            resumedFrom = 0L
        } else {
            pausedSince = 0L
            resumedFrom = 0L
        }
    }

    // Отлучка: окно свернули или ушли в другую программу, а серия шла дальше. По
    // возвращении — сколько тебя не было и предложение вернуться на то место.
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val focusedNow by rememberUpdatedState(focused)
    var awaySince by remember(episode) { mutableStateOf(0L) }
    var awayPosition by remember(episode) { mutableStateOf(0L) }
    var offer by remember(pet.id, episode) { mutableStateOf<PetAwayOffer?>(null) }
    var awayWatched by remember(episode) { mutableStateOf(0L) }
    LaunchedEffect(focused, episode, pet.id) {
        if (!focused) {
            // Focus changes must never cancel the offer's independent expiry.
            if (pet.id == "drizz") offer = null
            if (playingNow && !bufferingNow) {
                awaySince = System.currentTimeMillis(); awayPosition = positionNow
                awayWatched = playbackSession.episodeWatchedMs(episode)
            }
        } else if (awaySince > 0) {
            val away = System.currentTimeMillis() - awaySince
            val skipped = positionNow - awayPosition
            awaySince = 0L
            if (away >= AWAY_OFFER_AFTER_MS && skipped > 30_000L &&
                playbackSession.episodeWatchedMs(episode) - awayWatched >= 30_000L) {
                val now = System.currentTimeMillis()
                offer = PetAwayOffer(awayPosition, away, now + if (pet.id == "drizz") AWAY_OFFER_HIDE_MS else AWAY_OFFER_MS)
                PetSounds.play(PetSounds.Sound.OFFER)
            }
        }
    }
    PetOfferExpiry(offer) { offer = null }
    LaunchedEffect(pet.id, playbackError, ended, speechEnabled) {
        if (playbackError || ended || !speechEnabled) { offer = null; titleCardUntil = 0L }
    }

    // Один общий тик: снимок → решение. Отдельных таймеров на каждую реакцию нет.
    LaunchedEffect(pet.id, episode) {
        while (true) {
            val now = System.currentTimeMillis()
            // Перемотка: сдвиг позиции, не объяснимый ходом времени (с запасом на темп).
            val elapsed = now - lastTickAt
            val delta = positionNow - lastTickPosition
            val expected = (elapsed * speedNow).toLong()
            if (playingNow && delta < -SEEK_JUMP_MS) rewindTimes += now
            if (playingNow && delta > expected + SEEK_JUMP_MS) forwardTimes += now
            if (delta < -SEEK_JUMP_MS || delta > expected + SEEK_JUMP_MS) {
                playbackSession.seekSerial++
                seekBackwards = delta < 0
                offer = null
            }
            while (rewindTimes.isNotEmpty() && now - rewindTimes.first() > SEEK_WINDOW_MS) rewindTimes.removeFirst()
            while (forwardTimes.isNotEmpty() && now - forwardTimes.first() > SEEK_WINDOW_MS) forwardTimes.removeFirst()
            lastTickPosition = positionNow
            lastTickAt = now
            val ctx = contextNow
            val completion = if (pet.id == "drizz" && errorNow) null else playbackSession.takeCompletion(now)
            if (completion != null) { offer = null; titleCardUntil = 0L }
            if (initialSource == null && ctx.sourceName.isNotBlank()) initialSource = ctx.sourceName
            val decision = director.decide(
                PetDirector.Scene(
                    episode = episodeNow,
                    positionMs = positionNow,
                    durationMs = durationNow,
                    playing = playingNow,
                    buffering = bufferingNow,
                    episodesAvailable = availableNow,
                    episodesTotal = totalNow,
                    streak = playbackSession.completedCount,
                    sessionMs = playbackSession.watchedMs,
                    pausedForMs = if (pausedSince > 0) now - pausedSince else 0L,
                    resumedFromMs = resumedFrom,
                    now = now,
                    titleName = ctx.titleName,
                    titleWatchedMs = ctx.titleWatchedMs,
                    watchedEpisodes = ctx.watchedEpisodes,
                    daysSinceLastWatch = ctx.daysSinceLastWatch,
                    rewatch = ctx.rewatch,
                    openedAtMs = openedAt,
                    speed = speedNow,
                    rewinds = rewindTimes.size,
                    forwards = forwardTimes.size,
                    voiceName = ctx.voiceName,
                    sourceSwitched = initialSource != null && ctx.sourceName.isNotBlank() && ctx.sourceName != initialSource,
                    qualityForced = ctx.qualityForced,
                    hourOfDay = java.time.LocalTime.now().hour,
                    genres = ctx.genres,
                    rating = ctx.catalogRating,
                    ratingMax = ctx.ratingMax,
                    fullscreen = ctx.fullscreen,
                    recentGenres = ctx.recentGenres,
                    completion = completion?.event,
                    completionEpisode = completion?.episode ?: episodeNow,
                    hasNext = nextEpisodeNow != null,
                    endingConfirmed = endingNow,
                    userPaused = userPausedNow,
                    ended = endedNow,
                    error = errorNow,
                    episodeWatchedMs = playbackSession.episodeWatchedMs(episodeNow),
                    speechEnabled = speechNow,
                    quietWatching = quietNow,
                    character = pet.id,
                    muted = ctx.muted,
                    sourceName = ctx.sourceName,
                    qualityName = ctx.qualityName,
                    subtitlesName = ctx.subtitlesName,
                    seekSerial = playbackSession.seekSerial,
                    seekBackwards = seekBackwards,
                    activity = ctx.activity,
                ),
            )
            mood = decision.mood
            decision.say?.let { say = it; sayAt = now }
            // Просьба приходит каждым тиком, пока серия стоит на конце: показываем её
            // один раз на серию, чтобы закрытое окно не возвращалось.
            if (decision.ask == PetAsk.RATE_SEASON && askedFor != episodeNow && ctx.onRate != null) {
                ask = decision.ask
                askedFor = episodeNow
                askAt = now
                PetSounds.play(PetSounds.Sound.SAD)
            }
            val skipNext = decision.ask == PetAsk.SKIP_ENDING && skipDoneFor != episodeNow && nextEpisodeNow != null
            if (skipNext && !skipOffer) PetSounds.play(PetSounds.Sound.OFFER)
            skipOffer = skipNext
            if (ask != null && now - askAt > ASK_CARD_MS) ask = null
            if (say != null && now - sayAt > PET_SAY_MS) say = null
            if (resumedFrom > 0 && now - sayAt > PET_SAY_MS) resumedFrom = 0L
            if (titleCardUntil > 0 && now >= titleCardUntil) titleCardUntil = 0L
            if (pet.id == "drizz" && !playbackSession.drizzTitleShown && speechNow &&
                focusedNow && playingNow && !bufferingNow && !errorNow && !endedNow && completion == null &&
                ctx.titleName.isNotBlank() && say == null && ask == null && offer == null && decision.mood == PetMood.IDLE) {
                playbackSession.drizzTitleShown = true
                titleCardUntil = now + 6_000L
            }
            if (errorNow || endedNow || bufferingNow || ask != null || offer != null) titleCardUntil = 0L
            kotlinx.coroutines.delay(PET_TICK_MS)
        }
    }

    val pendingOffer = offer
    val pendingAsk = ask
    val askContext = contextNow
    val skipNow = skipOffer
    PetPlayerOverlay(
        pet = pet,
        mood = if (pendingOffer != null) PetMood.HAPPY else mood,
        scale = scale,
        say = if (!speechEnabled) null else pendingOffer?.let { petAwayText(it) } ?: if (skipNow) SKIP_ENDING_TEXT else say,
        xFraction = xFraction,
        yFraction = yFraction,
        onMove = onMove,
        modifier = modifier,
        onSayClick = when {
            pendingOffer != null && onSeek != null -> ({ onSeek(pendingOffer.positionMs); offer = null })
            skipNow -> ({
                skipDoneFor = episode
                skipOffer = false
                nextEpisodeNow?.invoke()
            })
            else -> null
        },
        tempo = 1f,
        animate = animate,
        avoidRight = avoidRight,
        avoidLeft = avoidLeft,
        onPetClick = if (pet.id == "drizz" && context.titleName.isNotBlank()) ({
            offer = null
            say = null
            titleCardUntil = if (titleCardUntil > 0) 0L else System.currentTimeMillis() + 6_000L
            playbackSession.drizzTitleShown = true
        }) else null,
        onDragStart = { titleCardUntil = 0L; offer = null; ask = null; say = null; playbackSession.drizzTitleShown = true },
        card = if (speechEnabled && pendingAsk == PetAsk.RATE_SEASON && askContext.onRate != null) {
            {
                PetRateCard(
                    title = askContext.titleName,
                    poster = askContext.poster,
                    rating = askContext.rating,
                    onRate = { score ->
                        askContext.onRate?.invoke(score)
                        if (score != askContext.rating) director.reactToRating(score, pet.id, System.currentTimeMillis())?.let { reaction ->
                            say = reaction.say
                            sayAt = System.currentTimeMillis()
                            mood = reaction.mood
                        }
                    },
                    onClose = { ask = null },
                    accent = Color(pet.accent),
                    character = pet.id,
                )
            }
        } else if (pet.id == "drizz" && titleCardUntil > 0 && speechEnabled && !playbackError && !ended) {
            {
                PetTitleCard(askContext, episode, episodesTotal, Color(pet.accent)) { titleCardUntil = 0L }
            }
        } else {
            null
        },
    )
}

/** Память и контекст тайтла для питомца в плеере — всё из настоящей истории. */
data class PetPlayerContext(
    val titleName: String = "",
    val titleWatchedMs: Long = 0L,
    val watchedEpisodes: Int = 0,
    val daysSinceLastWatch: Int = -1,
    val rewatch: Boolean = false,
    val voiceName: String = "",
    val sourceName: String = "",
    val qualityForced: Boolean = false,
    /** Постер тайтла — для окна «как тебе аниме?». */
    val poster: String = "",
    /** Личная оценка тайтла, 1..5; 0 — не оценивали. */
    val rating: Int = 0,
    /** Куда писать оценку. null — просить нечем, окно не показываем. */
    val onRate: ((Int) -> Unit)? = null,
    /** Жанры и оценка каталога — поводы для редких реплик «по существу». */
    val genres: String = "",
    /** Оценка КАТАЛОГА (не личная): о ней питомец иногда говорит. */
    val catalogRating: Double = 0.0,
    val ratingMax: Double = 0.0,
    /** Полноэкранный режим. */
    val fullscreen: Boolean = false,
    /** Жанры последних просмотренных тайтлов из истории (без текущего), свежие первыми. */
    val recentGenres: List<String> = emptyList(),
    val muted: Boolean = false,
    val qualityName: String = "",
    /** Имя включённой дорожки субтитров; пусто — выключены. */
    val subtitlesName: String = "",
    val activity: String = "normal",
)

/** Сколько висит окно просьбы, если на него не ответили. */
const val ASK_CARD_MS = 90_000L

/** Реплика-кнопка на титрах. Коротко — чтобы влезала в пузырь в одну-две строки. */
const val SKIP_ENDING_TEXT = "Эндинг? Мотаем к следующей серии"

/** Скачок позиции больше этого за тик — перемотка, а не ход времени. */
const val SEEK_JUMP_MS = 4_000L

/** Перемотки считаются за последнюю минуту. */
const val SEEK_WINDOW_MS = 60_000L

/** Предложение вернуться туда, где был, когда отвлёкся. */
data class PetAwayOffer(val positionMs: Long, val awayMs: Long, val expiresAt: Long = Long.MAX_VALUE)

/** Not keyed to window focus: switching away/back cannot strand a visible bubble. */
@Composable
internal fun PetOfferExpiry(offer: PetAwayOffer?, onExpire: () -> Unit) {
    val expire by rememberUpdatedState(onExpire)
    LaunchedEffect(offer) {
        if (offer != null) {
            kotlinx.coroutines.delay((offer.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            expire()
        }
    }
}

internal fun petRatingLines(personal: Int, catalog: Double, maximum: Double): List<String> = buildList {
    if (personal in 1..5) add("Ваша оценка: $personal/5")
    if (catalog.isFinite() && maximum.isFinite() && maximum > 0 && catalog > 0 && catalog <= maximum) {
        fun number(value: Double) = String.format(java.util.Locale.forLanguageTag("ru"), "%.1f", value).removeSuffix(",0")
        add("Оценка каталога: ${number(catalog)}/${number(maximum)}")
    }
}

@Composable
internal fun PetTitleCard(context: PetPlayerContext, episode: Int, total: Int, accent: Color, onClose: () -> Unit) {
    Column(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(12.dp))
        .background(com.aniblaze.desktop.ui.Surface2.copy(alpha = 0.96f))
        .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(context.titleName, color = com.aniblaze.desktop.ui.TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Close, "Закрыть информацию Drizz", tint = accent,
                modifier = Modifier.size(28.dp).clickable(onClick = onClose).padding(4.dp))
        }
        petRatingLines(context.rating, context.catalogRating, context.ratingMax).forEach {
            Text(DrizzDialogue.text("drizz", it), color = com.aniblaze.desktop.ui.TextPrimary, fontSize = 12.sp)
        }
        if (episode > 0) Text(if (total >= episode) "Серия $episode из $total" else "Серия $episode",
            color = com.aniblaze.desktop.ui.TextPrimary, fontSize = 12.sp)
    }
}

/** «Тебя не было 5 мин. Вернуться на 14:32?» */
fun petAwayText(offer: PetAwayOffer): String {
    val away = petWatchTimeLabel(offer.awayMs) ?: "${(offer.awayMs / 1000).coerceAtLeast(1)} с"
    return "Тебя не было $away. Вернуться на ${PetDirector.clock(offer.positionMs)}?"
}

/** Отлучка короче этого не стоит предложения. */
const val AWAY_OFFER_AFTER_MS = 60_000L

/** Сколько висит предложение вернуться, пока окно в фокусе. */
const val AWAY_OFFER_MS = 30_000L

/** Ушли из окна при висящем предложении — гасим через столько. */
const val AWAY_OFFER_HIDE_MS = 10_000L

/** Как часто питомец сверяется с происходящим. Раз в секунду — этого достаточно. */
const val PET_TICK_MS = 1_000L

/** После какой паузы здороваемся позицией («Продолжаем с 14:32»). */
const val RESUME_GREETING_AFTER_MS = 60_000L

internal fun petCardTop(petTop: Float, petHeight: Float, cardHeight: Float, height: Float): Float {
    val above = petTop - cardHeight - 8f
    val below = petTop + petHeight + 8f
    return (if (above >= 0) above else below).coerceIn(0f, (height - cardHeight).coerceAtLeast(0f))
}

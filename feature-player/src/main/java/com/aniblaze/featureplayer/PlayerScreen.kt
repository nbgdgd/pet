package com.aniblaze.featureplayer

import android.app.Activity
import android.media.AudioManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.core.graphics.toRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
import com.aniblaze.aggregator.model.TitleComment
import com.aniblaze.aggregator.model.Caption
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.StreamMode
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.aggregator.TorrentStreamStage
import com.aniblaze.aggregator.TorrentStreamStatus
import com.aniblaze.featureplayer.danmaku.DanmakuOverlay
import com.aniblaze.featureplayer.danmaku.DanmakuRate
import com.aniblaze.featureplayer.danmaku.isPotentialDanmakuSpoiler
import com.aniblaze.featureplayer.danmaku.mobileChatComments
import com.aniblaze.player.PlayerGestures
import com.aniblaze.ui.components.ErrorState
import com.aniblaze.ui.components.LoadingState
import com.aniblaze.ui.components.tvFocusRing
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Позиция и длительность тикают 3 раза в секунду всю серию.
 *
 * БЫЛО: обе величины лежали прямо в теле [PlayerScreen] как `mutableFloatStateOf`.
 * Чтение снапшот-состояния регистрирует читателя в ТОЙ области композиции, где оно
 * произошло, — то есть каждый тик перекомпоновывал весь экран целиком: AndroidView с
 * PlayerView, три pointerInput-модификатора жестов, оверлеи, кнопку пропуска. ~200
 * пересборок экрана в минуту, ~4800 за 24-минутную серию, и всё это в фазе композиции,
 * конкурируя с декодером за главный поток.
 *
 * СТАЛО: значения живут в отдельном holder'е, а читают их только листья, которым они
 * реально нужны, — сам SeekBar с таймкодами и кнопка «Пропустить опенинг». Тело
 * PlayerScreen от тикера больше не зависит.
 */
@Stable
private class ProgressHolder {
    var positionMs by mutableFloatStateOf(0f)
    var durationMs by mutableFloatStateOf(1f)
}

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    // «Случайное аниме после последней серии»: открыть плеер другого тайтла.
    onOpenTitle: (String) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // Только два поля вместо всего PlaybackState: объект целиком перезаписывается
    // раз в 4 с фоновым тикером прогресса, и подписка на него дёргала весь экран.
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val torrentStatus by viewModel.controller.torrentStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val danmakuOn by viewModel.danmakuEnabled.collectAsStateWithLifecycle()
    val danmakuRate by viewModel.danmakuRate.collectAsStateWithLifecycle()
    val chatEnabled by viewModel.chatEnabled.collectAsStateWithLifecycle()
    val chatHideSpoilers by viewModel.chatHideSpoilers.collectAsStateWithLifecycle()
    val autoSkipOpening by viewModel.autoSkipOpening.collectAsStateWithLifecycle()
    val autoSkipEnding by viewModel.autoSkipEnding.collectAsStateWithLifecycle()

    val switchTitle by viewModel.switchTitle.collectAsStateWithLifecycle()
    LaunchedEffect(switchTitle) {
        switchTitle?.let { next ->
            viewModel.consumeSwitchTitle()
            onOpenTitle(next)
        }
    }

    // Immersive fullscreen + mark the player active (for Picture-in-Picture).
    // System bars stay hidden the whole time the player is open and the content is
    // edge-to-edge, so nothing ever resizes/shifts the video when the controls toggle.
    DisposableEffect(Unit) {
        viewModel.controller.inPlayer = true
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (window != null) WindowCompat.setDecorFitsSystemWindows(window, false)
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            viewModel.controller.inPlayer = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var chatOpen by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    val progress = remember { ProgressHolder() }
    var overlayText by remember { mutableStateOf<String?>(null) }
    var videoScale by remember { mutableFloatStateOf(1f) }
    var videoFill by remember { mutableStateOf(false) }
    var skipSide by remember { mutableStateOf(0) } // -1 back, +1 forward, 0 none
    var skipTick by remember { mutableStateOf(0) }
    // TV remote: programmatic chat scroll (D-pad up/down while the chat is open).
    val chatListState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()

    // Smooth position ticker for the seek bar. Пишет в holder — читателей у него
    // всего два, и оба лежат ниже по дереву (см. комментарий к ProgressHolder).
    LaunchedEffect(ui.isResolving) {
        while (true) {
            progress.positionMs = viewModel.controller.currentPositionMs().toFloat()
            progress.durationMs = viewModel.controller.durationMs().coerceAtLeast(1).toFloat()
            delay(300)
        }
    }
    // Auto-hide controls — but never while a dropdown is open.
    LaunchedEffect(controlsVisible, isPlaying, menuOpen, chatOpen) {
        if (controlsVisible && isPlaying && !menuOpen && !chatOpen) {
            delay(4000)
            controlsVisible = false
        }
    }
    val gestureModifier = if (locked) {
        Modifier.pointerInput(Unit) {
            detectTapGestures { controlsVisible = !controlsVisible }
        }
    } else {
        Modifier.playerGestures(
            context = context,
            onSeek = { delta ->
                viewModel.onIntent(PlayerIntent.Seek(delta))
                overlayText = if (delta >= 0) "+${delta / 1000}s" else "${delta / 1000}s"
            },
            onToggleControls = { controlsVisible = !controlsVisible },
            onDoubleTapSkip = { forward ->
                val d = if (forward) PlayerGestures.DOUBLE_TAP_SKIP_MS else -PlayerGestures.DOUBLE_TAP_SKIP_MS
                viewModel.onIntent(PlayerIntent.Seek(d))
                skipSide = if (forward) 1 else -1
                skipTick++
            },
            onSpeedHold = { holding ->
                viewModel.onIntent(PlayerIntent.Speed(if (holding) 2.0f else 1.0f))
                overlayText = if (holding) "2.0x" else null
            },
            onLevel = { overlayText = it },
            onZoom = {
                videoFill = false
                videoScale = (videoScale * it).coerceIn(1f, 3f)
            },
        )
    }

    BackHandler(enabled = chatOpen) { chatOpen = false }

    val showChat = chatOpen && chatEnabled
    // Mouse users: any pointer move reveals the controls (touch never emits Move).
    val hoverModifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Move) controlsVisible = true
            }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(hoverModifier)
            .then(gestureModifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // An open dropdown menu owns the D-pad: focus navigation inside
                // the menu must not be hijacked by playback shortcuts.
                if (menuOpen && event.key != Key.Back) return@onPreviewKeyEvent false
                when (event.key) {
                    // D-pad center / Enter / Space = play/pause
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        viewModel.onIntent(PlayerIntent.PlayPause)
                        controlsVisible = true
                        true
                    }
                    // D-pad left / MediaRewind = seek back 10s
                    Key.DirectionLeft -> {
                        viewModel.onIntent(PlayerIntent.Seek(-10_000L))
                        overlayText = "-10s"
                        controlsVisible = true
                        true
                    }
                    // D-pad right / MediaFastForward = seek forward 10s
                    Key.DirectionRight -> {
                        viewModel.onIntent(PlayerIntent.Seek(10_000L))
                        overlayText = "+10s"
                        controlsVisible = true
                        true
                    }
                    // D-pad up/down: scroll the chat when it is open, otherwise
                    // toggle the controls. Chat scroll is programmatic so the
                    // remote never gets stuck in focus limbo.
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (showChat) {
                            val dy = if (event.key == Key.DirectionUp) -320f else 320f
                            uiScope.launch { chatListState.animateScrollBy(dy) }
                        } else {
                            controlsVisible = !controlsVisible
                        }
                        true
                    }
                    // Back button = close chat or go back
                    Key.Back -> {
                        if (chatOpen) chatOpen = false else onBack()
                        true
                    }
                    // Media play/pause button on remote
                    Key.MediaPlayPause -> {
                        viewModel.onIntent(PlayerIntent.PlayPause)
                        controlsVisible = true
                        true
                    }
                    else -> false
                }
            },
    ) {
        val chatWide = maxWidth >= 600.dp
        val chatPanelWidth = 340.dp
        val chatPanelHeight = 380.dp

        // ── Split layout: video + chat side-by-side or stacked ──
        if (showChat && chatWide) {
            // Landscape / wide: video on left, chat panel on right
            Row(Modifier.fillMaxSize()) {
                // Video viewport
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    VideoContent(
                        viewModel = viewModel,
                        isPlaying = isPlaying,
                        videoFill = videoFill,
                        videoScale = videoScale,
                        ui = ui,
                        danmakuOn = danmakuOn,
                        controlsVisible = controlsVisible,
                        locked = locked,
                        comments = comments,
                        danmakuRate = danmakuRate,
                        torrentStatus = torrentStatus,
                    )
                    // Overlay controls on video
                    PlayerOverlays(
                        ui = ui,
                        isPlaying = isPlaying,
                        controlsVisible = controlsVisible,
                        locked = locked,
                        menuOpen = menuOpen,
                        chatOpen = chatOpen,
                        chatEnabled = chatEnabled,
                        skipSide = skipSide,
                        skipTick = skipTick,
                        progress = progress,
                        speed = speed,
                        videoViewportModifier = Modifier,
                        onBack = onBack,
                        onLock = { locked = true; controlsVisible = false; chatOpen = false },
                        onPlayPause = { viewModel.onIntent(PlayerIntent.PlayPause) },
                        onNext = { viewModel.onIntent(PlayerIntent.Next) },
                        onSelectQuality = { viewModel.onIntent(PlayerIntent.SelectQuality(it)); overlayText = it.quality },
                        onSelectTranslation = { id ->
                            viewModel.onIntent(PlayerIntent.SelectTranslation(id))
                            overlayText = ui.translations.firstOrNull { it.id == id }?.name
                        },
                        onSelectSource = { name ->
                            viewModel.onIntent(PlayerIntent.SelectSource(name))
                            overlayText = name ?: "Авто"
                        },
                        onToggleAutoSwitch = {
                            viewModel.onIntent(PlayerIntent.ToggleAutoSwitch)
                            overlayText = if (!ui.autoSwitchRandom) "Случайное аниме после последней серии: вкл" else "Случайное аниме: выкл"
                        },
                        onSelectEpisode = { viewModel.onIntent(PlayerIntent.SelectEpisode(it)) },
                        onMenuExpandedChange = { menuOpen = it },
                        onSeekTo = { viewModel.onIntent(PlayerIntent.SeekTo(it.toLong())) },
                        onCycleSpeed = {
                            val steps = PlayerGestures.SPEED_STEPS
                            val next = steps[(steps.indexOf(speed).coerceAtLeast(0) + 1) % steps.size]
                            viewModel.onIntent(PlayerIntent.Speed(next))
                            overlayText = "${next}x"
                        },
                        onSelectVideoScale = { key ->
                            when (key) {
                                "fit" -> { videoFill = false; videoScale = 1f }
                                "fill" -> { videoFill = true; videoScale = 1f }
                                else -> {
                                    videoFill = false
                                    videoScale = (key.removePrefix("z").toIntOrNull() ?: 100) / 100f
                                }
                            }
                            overlayText = when {
                                videoFill -> "Заполнить"
                                videoScale > 1.001f -> "${(videoScale * 100).roundToInt()}%"
                                else -> "Вписать"
                            }
                        },
                        onToggleChat = { chatOpen = !chatOpen },
                        onSkipOpening = { label, target ->
                            viewModel.onIntent(PlayerIntent.SeekTo(target))
                            overlayText = label
                        },
                        availableSubtitles = ui.availableSubtitles,
                        selectedSubtitleIndex = ui.selectedSubtitleIndex,
                        onSelectSubtitle = { viewModel.onIntent(PlayerIntent.SelectSubtitle(it)) },
                    )
                }
                // Chat panel on the right
                ChatSidePanel(
                    comments = comments,
                    episode = ui.segmentNumber,
                    hideSpoilers = chatHideSpoilers,
                    listState = chatListState,
                    onClose = { chatOpen = false },
                )
            }
        } else if (showChat) {
            // Portrait narrow: video on top, chat below
            Column(Modifier.fillMaxSize()) {
                // Video viewport — takes remaining space
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    VideoContent(
                        viewModel = viewModel,
                        isPlaying = isPlaying,
                        videoFill = videoFill,
                        videoScale = videoScale,
                        ui = ui,
                        danmakuOn = danmakuOn,
                        controlsVisible = controlsVisible,
                        locked = locked,
                        comments = comments,
                        danmakuRate = danmakuRate,
                        torrentStatus = torrentStatus,
                    )
                    PlayerOverlays(
                        ui = ui,
                        isPlaying = isPlaying,
                        controlsVisible = controlsVisible,
                        locked = locked,
                        menuOpen = menuOpen,
                        chatOpen = chatOpen,
                        chatEnabled = chatEnabled,
                        skipSide = skipSide,
                        skipTick = skipTick,
                        progress = progress,
                        speed = speed,
                        videoViewportModifier = Modifier,
                        onBack = onBack,
                        onLock = { locked = true; controlsVisible = false; chatOpen = false },
                        onPlayPause = { viewModel.onIntent(PlayerIntent.PlayPause) },
                        onNext = { viewModel.onIntent(PlayerIntent.Next) },
                        onSelectQuality = { viewModel.onIntent(PlayerIntent.SelectQuality(it)); overlayText = it.quality },
                        onSelectTranslation = { id ->
                            viewModel.onIntent(PlayerIntent.SelectTranslation(id))
                            overlayText = ui.translations.firstOrNull { it.id == id }?.name
                        },
                        onSelectSource = { name ->
                            viewModel.onIntent(PlayerIntent.SelectSource(name))
                            overlayText = name ?: "Авто"
                        },
                        onToggleAutoSwitch = {
                            viewModel.onIntent(PlayerIntent.ToggleAutoSwitch)
                            overlayText = if (!ui.autoSwitchRandom) "Случайное аниме после последней серии: вкл" else "Случайное аниме: выкл"
                        },
                        onSelectEpisode = { viewModel.onIntent(PlayerIntent.SelectEpisode(it)) },
                        onMenuExpandedChange = { menuOpen = it },
                        onSeekTo = { viewModel.onIntent(PlayerIntent.SeekTo(it.toLong())) },
                        onCycleSpeed = {
                            val steps = PlayerGestures.SPEED_STEPS
                            val next = steps[(steps.indexOf(speed).coerceAtLeast(0) + 1) % steps.size]
                            viewModel.onIntent(PlayerIntent.Speed(next))
                            overlayText = "${next}x"
                        },
                        onSelectVideoScale = { key ->
                            when (key) {
                                "fit" -> { videoFill = false; videoScale = 1f }
                                "fill" -> { videoFill = true; videoScale = 1f }
                                else -> {
                                    videoFill = false
                                    videoScale = (key.removePrefix("z").toIntOrNull() ?: 100) / 100f
                                }
                            }
                            overlayText = when {
                                videoFill -> "Заполнить"
                                videoScale > 1.001f -> "${(videoScale * 100).roundToInt()}%"
                                else -> "Вписать"
                            }
                        },
                        onToggleChat = { chatOpen = !chatOpen },
                        onSkipOpening = { label, target ->
                            viewModel.onIntent(PlayerIntent.SeekTo(target))
                            overlayText = label
                        },
                        availableSubtitles = ui.availableSubtitles,
                        selectedSubtitleIndex = ui.selectedSubtitleIndex,
                        onSelectSubtitle = { viewModel.onIntent(PlayerIntent.SelectSubtitle(it)) },
                    )
                }
                // Chat panel below video
                ChatBottomPanel(
                    comments = comments,
                    episode = ui.segmentNumber,
                    hideSpoilers = chatHideSpoilers,
                    listState = chatListState,
                    onClose = { chatOpen = false },
                )
            }
        } else {
            // No chat: fullscreen video
            VideoContent(
                viewModel = viewModel,
                isPlaying = isPlaying,
                videoFill = videoFill,
                videoScale = videoScale,
                ui = ui,
                danmakuOn = danmakuOn,
                controlsVisible = controlsVisible,
                locked = locked,
                comments = comments,
                danmakuRate = danmakuRate,
                torrentStatus = torrentStatus,
            )
            PlayerOverlays(
                ui = ui,
                isPlaying = isPlaying,
                controlsVisible = controlsVisible,
                locked = locked,
                menuOpen = menuOpen,
                chatOpen = chatOpen,
                chatEnabled = chatEnabled,
                skipSide = skipSide,
                skipTick = skipTick,
                progress = progress,
                speed = speed,
                videoViewportModifier = Modifier,
                onBack = onBack,
                onLock = { locked = true; controlsVisible = false; chatOpen = false },
                onPlayPause = { viewModel.onIntent(PlayerIntent.PlayPause) },
                onNext = { viewModel.onIntent(PlayerIntent.Next) },
                onSelectQuality = { viewModel.onIntent(PlayerIntent.SelectQuality(it)); overlayText = it.quality },
                onSelectTranslation = { id ->
                    viewModel.onIntent(PlayerIntent.SelectTranslation(id))
                    overlayText = ui.translations.firstOrNull { it.id == id }?.name
                },
                onSelectSource = { name ->
                    viewModel.onIntent(PlayerIntent.SelectSource(name))
                    overlayText = name ?: "Авто"
                },
                onToggleAutoSwitch = {
                    viewModel.onIntent(PlayerIntent.ToggleAutoSwitch)
                    overlayText = if (!ui.autoSwitchRandom) "Случайное аниме после последней серии: вкл" else "Случайное аниме: выкл"
                },
                onSelectEpisode = { viewModel.onIntent(PlayerIntent.SelectEpisode(it)) },
                onMenuExpandedChange = { menuOpen = it },
                onSeekTo = { viewModel.onIntent(PlayerIntent.SeekTo(it.toLong())) },
                onCycleSpeed = {
                    val steps = PlayerGestures.SPEED_STEPS
                    val next = steps[(steps.indexOf(speed).coerceAtLeast(0) + 1) % steps.size]
                    viewModel.onIntent(PlayerIntent.Speed(next))
                    overlayText = "${next}x"
                },
                onSelectVideoScale = { key ->
                    when (key) {
                        "fit" -> { videoFill = false; videoScale = 1f }
                        "fill" -> { videoFill = true; videoScale = 1f }
                        else -> {
                            videoFill = false
                            videoScale = (key.removePrefix("z").toIntOrNull() ?: 100) / 100f
                        }
                    }
                    overlayText = when {
                        videoFill -> "Заполнить"
                        videoScale > 1.001f -> "${(videoScale * 100).roundToInt()}%"
                        else -> "Вписать"
                    }
                },
                onToggleChat = { chatOpen = !chatOpen },
                onSkipOpening = { label, target ->
                    viewModel.onIntent(PlayerIntent.SeekTo(target))
                    overlayText = label
                },
                availableSubtitles = ui.availableSubtitles,
                selectedSubtitleIndex = ui.selectedSubtitleIndex,
                onSelectSubtitle = { viewModel.onIntent(PlayerIntent.SelectSubtitle(it)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    modifier: Modifier = Modifier,
    title: String,
    isPlaying: Boolean,
    hasNext: Boolean,
    // Именно holder, а не два Float: если положение прокинуть значениями, тело Controls
    // (FlowRow из шести пилюль + выпадающие меню) пересобиралось бы 3 раза в секунду.
    progress: ProgressHolder,
    qualities: List<StreamVariant>,
    currentQuality: String,
    streamStatus: String,
    onSelectQuality: (StreamVariant) -> Unit,
    translations: List<Translation>,
    currentTranslationId: Int?,
    onSelectTranslation: (Int) -> Unit,
    sources: List<String> = emptyList(),
    activeSource: String? = null,
    onSelectSource: (String?) -> Unit = {},
    autoSwitchRandom: Boolean = false,
    onToggleAutoSwitch: () -> Unit = {},
    episodes: List<Int>,
    currentEpisode: Int,
    onSelectEpisode: (Int) -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit,
    onLock: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Float) -> Unit,
    speed: Float,
    onCycleSpeed: () -> Unit,
    videoScale: Float,
    videoFill: Boolean,
    onSelectVideoScale: (String) -> Unit,
    chatAvailable: Boolean,
    chatOpen: Boolean,
    onToggleChat: () -> Unit,
    availableSubtitles: List<com.aniblaze.aggregator.model.Caption> = emptyList(),
    selectedSubtitleIndex: Int = -1,
    onSelectSubtitle: (Int) -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(Color(0x66000000))) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = TextPrimary)
            }
            Text(title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            StreamStatusBadge(streamStatus)
            IconButton(onClick = onLock) {
                Icon(Icons.Filled.LockOpen, contentDescription = "Заблокировать", tint = TextPrimary)
            }
        }

        // Center transport
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = TextPrimary,
                    modifier = Modifier.size(56.dp),
                )
            }
            if (hasNext) {
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Следующая", tint = TextPrimary, modifier = Modifier.size(40.dp))
                }
            }
        }

        // Bottom: selectors (wrap on narrow screens) + seek bar
        // widthIn caps the controls on wide screens so seek bar doesn't span 1200dp
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = 960.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (episodes.size > 1) {
                    EpisodeSelector(episodes, currentEpisode, onSelectEpisode, onMenuExpandedChange)
                }
                if (translations.size > 1) {
                    TranslationSelector(translations, currentTranslationId, onSelectTranslation, onMenuExpandedChange)
                }
                if (sources.isNotEmpty()) {
                    SourceSelector(sources, activeSource, onSelectSource, onMenuExpandedChange)
                }
                if (qualities.size > 1) {
                    QualitySelector(qualities, currentQuality, onSelectQuality, onMenuExpandedChange)
                }
                PillButton(
                    icon = { Icon(Icons.Filled.Speed, contentDescription = "Скорость", tint = TextPrimary, modifier = Modifier.size(16.dp)) },
                    label = speedLabel(speed),
                    onClick = onCycleSpeed,
                )
                ScaleSelector(
                    scale = videoScale,
                    fill = videoFill,
                    onSelect = onSelectVideoScale,
                    onExpandedChange = onMenuExpandedChange,
                )
                if (chatAvailable) {
                    PillButton(
                        icon = {
                            Icon(
                                Icons.Filled.Chat,
                                contentDescription = "Чат",
                                tint = if (chatOpen) AccentOrange else TextPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = if (chatOpen) "Чат: открыт" else "Чат",
                        onClick = onToggleChat,
                    )
                }
                if (availableSubtitles.isNotEmpty()) {
                    PillButton(
                        icon = {
                            Icon(
                                Icons.Filled.Subtitles,
                                contentDescription = "Субтитры",
                                tint = if (selectedSubtitleIndex >= 0) AccentOrange else TextPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = if (selectedSubtitleIndex >= 0) {
                            availableSubtitles.getOrNull(selectedSubtitleIndex)?.language?.uppercase() ?: "Суб"
                        } else "Субтитры",
                        onClick = {
                            val next = if (selectedSubtitleIndex >= 0) -1 else 0
                            onSelectSubtitle(next)
                        },
                    )
                }
                // «Случайное аниме после последней серии» — shuffle-переключатель.
                PillButton(
                    icon = {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Случайное аниме после последней серии",
                            tint = if (autoSwitchRandom) AccentOrange else TextPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = if (autoSwitchRandom) "Рандом: вкл" else "Рандом",
                    onClick = onToggleAutoSwitch,
                )
            }
            SeekRow(progress = progress, onSeekTo = onSeekTo)
        }
    }
}

@Composable
private fun StreamStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color(0xB31C1C22),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
    ) {
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

internal fun streamStatusLabel(
    mode: StreamMode,
    source: String,
    loading: Boolean,
    torrent: TorrentStreamStatus = TorrentStreamStatus(),
): String {
    val isTorrent = mode == StreamMode.TORRENT || isTorrentSource(source)
    if (isTorrent && torrent.stage != TorrentStreamStage.IDLE) return torrentStatusLabel(torrent)
    if (loading) return when {
        isTorrent -> "Загрузка через торрент…"
        mode == StreamMode.PARSER -> "Загрузка через парсер…"
        mode == StreamMode.AUTO -> "Поиск источника…"
        else -> "Загрузка…"
    }
    return if (isTorrentSource(source)) {
        "Торрент"
    } else {
        "Парсер${source.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"
    }
}

internal fun torrentStatusLabel(status: TorrentStreamStatus): String {
    val sourceName = torrentSourceName(status.source)
    status.detail.takeIf { status.stage == TorrentStreamStage.ERROR && it.isNotBlank() }?.let {
        return "$sourceName · $it"
    }
    return when (status.stage) {
        TorrentStreamStage.IDLE -> "Торрент"
        TorrentStreamStage.SEARCHING -> "$sourceName · поиск раздачи…"
        TorrentStreamStage.METADATA -> "$sourceName · загрузка метаданных…"
        TorrentStreamStage.CONNECTING -> "$sourceName · подключение к пирами"
        TorrentStreamStage.PAUSED -> "$sourceName · загрузка на паузе"
        TorrentStreamStage.ERROR -> "$sourceName · ошибка запуска"
        TorrentStreamStage.BUFFERING,
        TorrentStreamStage.STREAMING -> buildString {
            append(sourceName)
            append(" · ")
            append(formatTorrentRate(status.downloadBytesPerSecond))
            append(" · ")
            append(torrentPeerText(status.peers))
            if (status.totalBytes > 0L) {
                val percent = ((status.downloadedBytes * 100L) / status.totalBytes).coerceIn(0L, 100L)
                append(" · ")
                append(percent)
                append('%')
            }
        }
    }
}

private fun torrentSourceName(raw: String): String {
    val normalized = raw.trim()
    return when {
        normalized.isBlank() || normalized.equals("Torrent", ignoreCase = true) -> "Торрент"
        else -> "Торрент · $normalized"
    }
}

internal fun isTorrentSource(source: String): Boolean = source.trim().let { value ->
    value.equals("Torrent", ignoreCase = true)
        || value.equals("TorrentIO", ignoreCase = true)
        || value.equals("Torrentio", ignoreCase = true)
        || value.startsWith("Торрент", ignoreCase = true)
        || value.equals("Stremio", ignoreCase = true)
        || value.startsWith("torrent", ignoreCase = true)
}

private fun torrentPeerText(peers: Int): String = when {
    peers <= 1 -> "${peers.coerceAtLeast(0)} пир"
    peers % 10 in 2..4 && peers % 100 !in 12..14 -> "${peers} пира"
    else -> "${peers} пиров"
}

internal fun formatTorrentRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L -> String.format(
        Locale.forLanguageTag("ru"),
        "%.1f МБ/с",
        bytesPerSecond / (1024.0 * 1024.0),
    )
    bytesPerSecond >= 1024L -> "${bytesPerSecond / 1024L} КБ/с"
    else -> "$bytesPerSecond Б/с"
}

/**
 * Chat panel as a right-side vertical panel (landscape / wide screens).
 * Does NOT overlay the video — the video takes weight(1f) and this takes fixed width.
 */
@Composable
private fun ChatSidePanel(
    comments: List<TitleComment>,
    episode: Int,
    hideSpoilers: Boolean,
    listState: LazyListState,
    onClose: () -> Unit,
) {
    val scoped = remember(comments, episode) { mobileChatComments(comments, episode) }
    var revealed by remember(episode) { mutableStateOf(emptySet<Long>()) }

    Surface(
        modifier = Modifier
            .width(340.dp)
            .fillMaxHeight(),
        color = Color(0xE815151C),
        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        ChatContent(scoped, episode, hideSpoilers, revealed, { revealed = revealed + it }, listState, onClose)
    }
}

/**
 * Chat panel stacked below video (portrait narrow screens).
 * Takes up to 45% of screen height, video gets the rest.
 */
@Composable
private fun ChatBottomPanel(
    comments: List<TitleComment>,
    episode: Int,
    hideSpoilers: Boolean,
    listState: LazyListState,
    onClose: () -> Unit,
) {
    val scoped = remember(comments, episode) { mobileChatComments(comments, episode) }
    var revealed by remember(episode) { mutableStateOf(emptySet<Long>()) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.45f),
        color = Color(0xE815151C),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        ChatContent(scoped, episode, hideSpoilers, revealed, { revealed = revealed + it }, listState, onClose)
    }
}

@Composable
private fun ChatContent(
    scoped: List<TitleComment>,
    episode: Int,
    hideSpoilers: Boolean,
    revealed: Set<Long>,
    onReveal: (Long) -> Unit,
    listState: LazyListState,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Chat, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(20.dp))
            Text(
                text = "Чат · ${scoped.size}",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть чат", tint = TextPrimary)
            }
        }
        Text(
            "Только русские комментарии парсера для текущей серии",
            color = TextPrimary.copy(alpha = 0.62f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )
        if (scoped.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Русских комментариев для этой серии пока нет",
                    color = TextPrimary.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(scoped, key = { it.id }) { comment ->
                    val spoiler = hideSpoilers && isPotentialDanmakuSpoiler(comment)
                    val showText = !spoiler || comment.id in revealed
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.055f))
                            .tvFocusRing(14.dp)
                            .then(
                                if (!showText) Modifier.clickable { onReveal(comment.id) }
                                else Modifier.focusable()
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (comment.avatar.isNotBlank()) {
                            AsyncImage(
                                model = comment.avatar,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(32.dp).clip(CircleShape),
                            )
                        } else {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(AccentOrange.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(comment.author.take(1).uppercase(), color = AccentOrange, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 9.dp)) {
                            Text(
                                comment.author.ifBlank { "Зритель" },
                                color = AccentOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (showText) comment.message else "Возможный спойлер · нажмите, чтобы показать",
                                color = TextPrimary.copy(alpha = if (showText) 0.9f else 0.68f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Video viewport — the PlayerView + danmaku + status overlays.
 * Extracted so it can be reused across chat/no-chat layouts.
 */
@Composable
private fun VideoContent(
    viewModel: PlayerViewModel,
    isPlaying: Boolean,
    videoFill: Boolean,
    videoScale: Float,
    ui: PlayerUiState,
    danmakuOn: Boolean,
    controlsVisible: Boolean,
    locked: Boolean,
    comments: List<TitleComment>,
    danmakuRate: String,
    torrentStatus: TorrentStreamStatus,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                player = viewModel.controller.player
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = {
            it.keepScreenOn = isPlaying
            it.resizeMode = if (videoFill) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = if (videoFill) 1f else videoScale
                scaleX = scale
                scaleY = scale
            },
    )

    // Danmaku on top of video
    if (danmakuOn && !controlsVisible && !locked && !ui.isResolving) {
        DanmakuOverlay(
            episodeKey = "${ui.title}:${ui.segmentNumber}",
            comments = comments,
            episode = ui.segmentNumber,
            lengthMs = viewModel.controller.durationMs(),
            playing = isPlaying,
            rate = DanmakuRate.of(danmakuRate),
        )
    }

    // Loading / error states + badges overlaid
    Box(Modifier.fillMaxSize()) {
        when {
            ui.isResolving -> {
                LoadingState()
                StreamStatusBadge(
                    text = streamStatusLabel(ui.streamMode, ui.currentStreamSource, loading = true, torrent = torrentStatus),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                )
            }
            ui.error != null -> ErrorState(ui.error!!, onRetry = { viewModel.onIntent(PlayerIntent.Retry) })
        }

        // Torrent status badge
        if (!ui.isResolving && !controlsVisible && isTorrentSource(ui.currentStreamSource)) {
            StreamStatusBadge(
                text = streamStatusLabel(ui.streamMode, ui.currentStreamSource, loading = false, torrent = torrentStatus),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }
}

/**
 * All overlays on top of the video: controls, skip, auto-skip, lock, seek overlay.
 */
@Composable
private fun PlayerOverlays(
    ui: PlayerUiState,
    isPlaying: Boolean,
    controlsVisible: Boolean,
    locked: Boolean,
    menuOpen: Boolean,
    chatOpen: Boolean,
    chatEnabled: Boolean,
    skipSide: Int,
    skipTick: Int,
    progress: ProgressHolder,
    speed: Float,
    videoViewportModifier: Modifier,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSelectQuality: (StreamVariant) -> Unit,
    onSelectTranslation: (Int) -> Unit,
    onSelectSource: (String?) -> Unit,
    onToggleAutoSwitch: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit,
    onSeekTo: (Float) -> Unit,
    onCycleSpeed: () -> Unit,
    onSelectVideoScale: (String) -> Unit,
    onToggleChat: () -> Unit,
    onSkipOpening: (String, Long) -> Unit,
    availableSubtitles: List<com.aniblaze.aggregator.model.Caption> = emptyList(),
    selectedSubtitleIndex: Int = -1,
    onSelectSubtitle: (Int) -> Unit = {},
) {
    // Skip flash
    SkipFlash(side = skipSide, tick = skipTick) {}

    // Auto-skip op/ed
    if (!ui.isResolving) {
        AutoSkipEffect(
            episodeKey = "${ui.title}:${ui.segmentNumber}",
            progress = progress,
            opening = ui.opening,
            ending = ui.ending,
            skipOpening = true,
            skipEnding = true,
            onSkip = onSkipOpening,
        )
    }

    // Skip segment button
    if (!locked && !controlsVisible && !ui.isResolving) {
        SkipSegmentButton(
            progress = progress,
            opening = ui.opening,
            ending = ui.ending,
            onSkip = onSkipOpening,
        )
    }

    // Locked unlock button
    if (locked) {
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                IconButton(
                    onClick = onLock,
                    modifier = Modifier.padding(start = 16.dp),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = "Разблокировать", tint = TextPrimary)
                }
            }
        }
    } else {
        // Controls panel
        AnimatedVisibility(
            visible = controlsVisible || menuOpen || chatOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
        ) {
            Controls(
                modifier = videoViewportModifier,
                title = ui.title.ifBlank { "Серия ${ui.segmentNumber}" },
                isPlaying = isPlaying,
                hasNext = ui.hasNext,
                progress = progress,
                qualities = ui.qualities,
                currentQuality = ui.currentQuality,
                streamStatus = streamStatusLabel(ui.streamMode, ui.currentStreamSource, loading = false, torrent = TorrentStreamStatus()),
                onSelectQuality = onSelectQuality,
                translations = ui.translations,
                currentTranslationId = ui.currentTranslationId,
                onSelectTranslation = onSelectTranslation,
                sources = ui.sources,
                activeSource = ui.activeSource,
                onSelectSource = onSelectSource,
                autoSwitchRandom = ui.autoSwitchRandom,
                onToggleAutoSwitch = onToggleAutoSwitch,
                episodes = ui.episodes,
                currentEpisode = ui.segmentNumber,
                onSelectEpisode = onSelectEpisode,
                onMenuExpandedChange = onMenuExpandedChange,
                onLock = onLock,
                onBack = onBack,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onSeekTo = onSeekTo,
                speed = speed,
                onCycleSpeed = onCycleSpeed,
                videoScale = 1f,
                videoFill = false,
                onSelectVideoScale = onSelectVideoScale,
                chatAvailable = chatEnabled,
                chatOpen = chatOpen,
                onToggleChat = onToggleChat,
                availableSubtitles = availableSubtitles,
                selectedSubtitleIndex = selectedSubtitleIndex,
                onSelectSubtitle = onSelectSubtitle,
            )
        }
    }
}

/**
 * Ползунок + таймкоды. Отдельный composable, потому что это единственное место в
 * контролах, которое обязано читать позицию на каждый тик; тело [Controls] благодаря
 * этому от тикера не зависит.
 */
@Composable
private fun SeekRow(progress: ProgressHolder, onSeekTo: (Float) -> Unit) {
    var preview by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth()) {
        SeekBar(
            progress = progress,
            onScrub = { preview = it },
            onSeek = { onSeekTo(it); preview = null },
        )
        val duration = progress.durationMs
        val shownMs = (preview ?: progress.positionMs).coerceIn(0f, duration.coerceAtLeast(0f))
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(shownMs.toLong()), color = TextPrimary, fontSize = 12.sp)
            Text(formatMs(duration.toLong()), color = TextPrimary, fontSize = 12.sp)
        }
    }
}

/**
 * Пропуск опенинга/эндинга по ТОЧНЫМ границам из AniSkip. Кнопка видна только внутри
 * известного интервала (+5 с форы перед началом) и прыгает ровно на его конец — раньше
 * это был слепой сдвиг на +85 с в первые две минуты у любого тайтла.
 *
 * Живёт отдельным composable: проверка интервала — единственная причина читать позицию,
 * пока контролы спрятаны, и раньше она делала это в теле PlayerScreen, перекомпоновывая
 * весь плеер 3 раза в секунду.
 */
@Composable
private fun SkipSegmentButton(
    progress: ProgressHolder,
    opening: com.aniblaze.aggregator.model.OpeningRange?,
    ending: com.aniblaze.aggregator.model.OpeningRange?,
    onSkip: (label: String, targetMs: Long) -> Unit,
) {
    val position = progress.positionMs
    fun inRange(r: com.aniblaze.aggregator.model.OpeningRange?): Boolean {
        if (r == null || !r.isValid) return false
        // Интервал, взятый у соседней серии (AniSkip знает не все), может
        // «уехать» на секунды — окно показа для него шире.
        val lead = if (r.approximate) 45_000L else 5_000L
        val tail = if (r.approximate) 20_000L else 0L
        return position >= (r.startMs - lead).coerceAtLeast(0L) && position < r.endMs + tail
    }
    val skip = when {
        inRange(opening) -> "Пропустить опенинг" to opening!!.endMs
        inRange(ending) -> "Пропустить эндинг" to ending!!.endMs
        else -> return
    }
    val (skipLabel, skipTarget) = skip
    Box(Modifier.fillMaxSize().padding(end = 16.dp, bottom = 96.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier
                .background(Color(0xCC1B1B20), RoundedCornerShape(24.dp))
                .border(1.dp, AccentOrange.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .clickable { onSkip(skipLabel, skipTarget) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.FastForward, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(18.dp))
            Text(skipLabel, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Отдельный маленький читатель позиции: автопропуск не перекомпоновывает весь плеер. */
@Composable
private fun AutoSkipEffect(
    episodeKey: String,
    progress: ProgressHolder,
    opening: com.aniblaze.aggregator.model.OpeningRange?,
    ending: com.aniblaze.aggregator.model.OpeningRange?,
    skipOpening: Boolean,
    skipEnding: Boolean,
    onSkip: (label: String, targetMs: Long) -> Unit,
) {
    var openingHandled by remember(episodeKey, opening) { mutableStateOf(false) }
    var endingHandled by remember(episodeKey, ending) { mutableStateOf(false) }
    val decision = autoSkipDecision(
        positionMs = progress.positionMs.toLong(),
        opening = opening,
        ending = ending,
        skipOpening = skipOpening,
        skipEnding = skipEnding,
        openingHandled = openingHandled,
        endingHandled = endingHandled,
    )
    LaunchedEffect(decision) {
        when (decision?.kind) {
            AutoSkipKind.OPENING -> {
                openingHandled = true
                onSkip("Опенинг пропущен", decision.targetMs)
            }
            AutoSkipKind.ENDING -> {
                endingHandled = true
                onSkip("Эндинг пропущен", decision.targetMs)
            }
            null -> Unit
        }
    }
}

/**
 * Custom seek bar: rounded "liquid" orange track + a thumb that always stays
 * inside the bar's bounds (so nothing ever pokes out past the screen edge like
 * the stock Material3 Slider did). Scrubs locally and commits on release.
 */
@Composable
private fun SeekBar(
    progress: ProgressHolder,
    onScrub: (Float) -> Unit,
    onSeek: (Float) -> Unit,
) {
    val dur = progress.durationMs.coerceAtLeast(0f)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    // Во время перетаскивания позицию из тикера НЕ читаем — ползунок перестаёт
    // дёргаться от фонового опроса и следует только за пальцем.
    val shown = (if (scrubbing) scrubValue else progress.positionMs).coerceIn(0f, dur.coerceAtLeast(1f))
    val fraction = if (dur > 0f) (shown / dur).coerceIn(0f, 1f) else 0f
    val thumb = 16.dp

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(dur) {
                detectTapGestures { off ->
                    if (dur > 0f) onSeek(((off.x / size.width).coerceIn(0f, 1f)) * dur)
                }
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        scrubbing = true
                        scrubValue = ((off.x / size.width).coerceIn(0f, 1f)) * dur
                        onScrub(scrubValue)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        scrubValue = ((change.position.x / size.width).coerceIn(0f, 1f)) * dur
                        onScrub(scrubValue)
                    },
                    onDragEnd = { onSeek(scrubValue); scrubbing = false },
                    onDragCancel = { scrubbing = false },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val travel = maxWidth - thumb
        // Inactive track (subtle glass).
        Box(
            Modifier
                .padding(horizontal = thumb / 2)
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(TextPrimary.copy(alpha = 0.22f)),
        )
        // Active track — liquid orange gradient.
        Box(
            Modifier
                .padding(start = thumb / 2)
                .width(travel * fraction)
                .height(6.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(AccentOrange.copy(alpha = 0.85f), AccentOrange),
                    ),
                ),
        )
        // Thumb — stays fully within bounds at both ends.
        Box(
            Modifier
                .offset(x = travel * fraction)
                .size(thumb)
                .clip(CircleShape)
                .background(AccentOrange)
                .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        )
    }
}

@Composable
private fun PillButton(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(PillShape)
            .background(PillGlass)
            .border(1.dp, AccentOrange.copy(alpha = 0.38f), PillShape)
            .tvFocusRing(14.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        icon()
        Text(
            label,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp).padding(start = 6.dp),
        )
    }
}

// Shared "liquid glass" styling for the player's selector pills and their menus.
private val PillShape = RoundedCornerShape(14.dp)
private val PillGlass = Color(0x4015151A)
private val MenuShape = RoundedCornerShape(18.dp)
private val MenuContainer = Color(0xF2181820)
private val MenuBorder = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.45f))

@Composable
private fun ScaleSelector(
    scale: Float,
    fill: Boolean,
    onSelect: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun set(value: Boolean) { expanded = value; onExpandedChange(value) }
    val label = when {
        fill -> "Заполнить"
        scale > 1.001f -> "${(scale * 100).roundToInt()}%"
        else -> "Вписать"
    }
    Box {
        PillButton(
            icon = { Icon(Icons.Filled.AspectRatio, contentDescription = "Масштаб видео", tint = if (fill || scale > 1.001f) AccentOrange else TextPrimary, modifier = Modifier.size(16.dp)) },
            label = label,
            onClick = { set(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { set(false) },
            shape = MenuShape,
            containerColor = MenuContainer,
            border = MenuBorder,
        ) {
            val choices = listOf(
                "fit" to "Вписать в экран",
                "fill" to "Заполнить (обрезает края)",
                "z110" to "110%",
                "z125" to "125%",
                "z150" to "150%",
                "z175" to "175%",
                "z200" to "200%",
            )
            choices.forEach { (key, text) ->
                val selected = when (key) {
                    "fit" -> !fill && scale <= 1.001f
                    "fill" -> fill
                    else -> !fill && (scale * 100).roundToInt() == key.removePrefix("z").toInt()
                }
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { set(false); onSelect(key) },
                    trailingIcon = { if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = AccentOrange) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeSelector(
    episodes: List<Int>,
    current: Int,
    onSelect: (Int) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun set(v: Boolean) { expanded = v; onExpandedChange(v) }
    Box {
        PillButton(
            icon = { Icon(Icons.Filled.FormatListNumbered, contentDescription = "Серия", tint = TextPrimary, modifier = Modifier.size(16.dp)) },
            label = "Серия $current",
            onClick = { set(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { set(false) },
            modifier = Modifier.heightIn(max = 360.dp),
            shape = MenuShape,
            containerColor = MenuContainer,
            border = MenuBorder,
        ) {
            episodes.forEach { ep ->
                DropdownMenuItem(
                    text = { Text("Серия $ep") },
                    onClick = { set(false); onSelect(ep) },
                    trailingIcon = {
                        if (ep == current) Icon(Icons.Filled.Check, contentDescription = null, tint = AccentOrange)
                    },
                )
            }
        }
    }
}

@Composable
private fun QualitySelector(
    qualities: List<StreamVariant>,
    current: String,
    onSelect: (StreamVariant) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun set(v: Boolean) { expanded = v; onExpandedChange(v) }
    Box {
        PillButton(
            icon = {},
            label = current.ifBlank { "Auto" },
            onClick = { set(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { set(false) },
            shape = MenuShape,
            containerColor = MenuContainer,
            border = MenuBorder,
        ) {
            qualities.forEach { variant ->
                DropdownMenuItem(
                    text = { Text(variant.quality) },
                    onClick = { set(false); onSelect(variant) },
                    trailingIcon = {
                        if (variant.quality == current) Icon(Icons.Filled.Check, contentDescription = null, tint = AccentOrange)
                    },
                )
            }
        }
    }
}

/** «Источник» — через какой сайт резолвится поток. AnimeVost = прогрессивный MP4
 *  с мгновенной перемоткой; остальные HLS. «Авто» = обычная гонка по приоритету. */
@Composable
private fun SourceSelector(
    sources: List<String>,
    active: String?,
    onSelect: (String?) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun set(v: Boolean) { expanded = v; onExpandedChange(v) }
    Box {
        PillButton(
            icon = { Icon(Icons.Filled.Cloud, contentDescription = "Источник", tint = TextPrimary, modifier = Modifier.size(16.dp)) },
            label = active ?: "Авто",
            onClick = { set(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { set(false) },
            modifier = Modifier.heightIn(max = 360.dp),
            shape = MenuShape,
            containerColor = MenuContainer,
            border = MenuBorder,
        ) {
            (listOf<String?>(null) + sources).forEach { name ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (name) {
                                null -> "Авто"
                                "AnimeVost" -> "AnimeVost · быстрая перемотка"
                                else -> name
                            },
                        )
                    },
                    onClick = { set(false); onSelect(name) },
                    trailingIcon = {
                        if (name == active) Icon(Icons.Filled.Check, contentDescription = null, tint = AccentOrange)
                    },
                )
            }
        }
    }
}

@Composable
private fun TranslationSelector(
    translations: List<Translation>,
    currentId: Int?,
    onSelect: (Int) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun set(v: Boolean) { expanded = v; onExpandedChange(v) }
    val current = translations.firstOrNull { it.id == currentId }
    Box {
        PillButton(
            icon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Озвучка", tint = TextPrimary, modifier = Modifier.size(16.dp)) },
            label = current?.name ?: "Озвучка",
            onClick = { set(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { set(false) },
            modifier = Modifier.heightIn(max = 360.dp),
            shape = MenuShape,
            containerColor = MenuContainer,
            border = MenuBorder,
        ) {
            // Share of this title's views per dub (Anixart's per-release counters) —
            // which озвучка people actually watch THIS anime in.
            val totalViews = remember(translations) { translations.sumOf { it.views } }
            translations.forEach { t ->
                val share = if (totalViews > 0 && t.views > 0) {
                    (t.views * 100.0 / totalViews).roundToInt().coerceAtLeast(1)
                } else null
                DropdownMenuItem(
                    text = {
                        Text(
                            buildString {
                                append(if (t.isSub) "${t.name} (суб)" else t.name)
                                share?.let { append("  · $it%") }
                            },
                        )
                    },
                    onClick = { set(false); onSelect(t.id) },
                    trailingIcon = {
                        if (t.id == currentId) Icon(Icons.Filled.Check, contentDescription = null, tint = AccentOrange)
                    },
                )
            }
        }
    }
}

@Composable
private fun SkipFlash(side: Int, tick: Int, onDone: () -> Unit) {
    var lastSide by remember { mutableStateOf(1) }
    LaunchedEffect(tick) {
        if (side != 0) {
            lastSide = side
            delay(600)
            onDone()
        }
    }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = if (lastSide < 0) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        AnimatedVisibility(
            visible = side != 0,
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.6f),
            exit = fadeOut(tween(250)),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 48.dp)
                    .size(120.dp)
                    .background(Color(0x66000000), CircleShape),
            ) {
                Icon(
                    if (lastSide < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(40.dp),
                )
                Text("10 сек", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/** "1x", "1.5x", "2x" — whole speeds without a trailing ".0". */
private fun speedLabel(s: Float): String =
    if (s == s.toLong().toFloat()) "${s.toLong()}x" else "${s}x"

/**
 * Tap to toggle controls, double-tap to skip ±10s, horizontal drag to seek,
 * vertical drag for brightness/volume, long-press for 2x speed.
 */
private fun Modifier.playerGestures(
    context: android.content.Context,
    onSeek: (Long) -> Unit,
    onToggleControls: () -> Unit,
    onDoubleTapSkip: (forward: Boolean) -> Unit,
    onSpeedHold: (holding: Boolean) -> Unit,
    onLevel: (String) -> Unit,
    onZoom: (Float) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        // Pinch-to-zoom: only act on multi-touch so single-finger seek still works.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.size >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        onZoom(zoom)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onToggleControls() },
            onDoubleTap = { offset -> onDoubleTapSkip(offset.x > size.width / 2) },
            onLongPress = { onSpeedHold(true) },
            onPress = {
                tryAwaitRelease()
                onSpeedHold(false)
            },
        )
    }
    .pointerInput(Unit) {
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val activity = context as? Activity
        var totalDragX = 0f
        detectDragGestures(
            onDragStart = { totalDragX = 0f },
            onDragEnd = {
                if (abs(totalDragX) > 8f) onSeek(PlayerGestures.horizontalSeekMs(totalDragX, size.width.toFloat()))
            },
            onDrag = { change, drag ->
                change.consume()
                if (abs(drag.x) > abs(drag.y)) {
                    totalDragX += drag.x
                } else {
                    val delta = PlayerGestures.verticalLevelDelta(drag.y, size.height.toFloat())
                    when (PlayerGestures.zoneFor(change.position.x, size.width.toFloat())) {
                        PlayerGestures.Zone.Volume -> {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val next = (cur + (delta * max)).toInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                            onLevel("Звук ${(next * 100 / max)}%")
                        }
                        PlayerGestures.Zone.Brightness -> {
                            activity?.window?.let { w ->
                                val lp = w.attributes
                                val b = ((if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness) + delta).coerceIn(0.01f, 1f)
                                lp.screenBrightness = b
                                w.attributes = lp
                                onLevel("Яркость ${(b * 100).toInt()}%")
                            }
                        }
                    }
                }
            },
        )
    }

// force rebuild

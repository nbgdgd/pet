package com.aniblaze.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Мост между проигрывателем и окошком «картинка в картинке».
 *
 * Окошко живёт СНАРУЖИ главного окна (это отдельное окно рабочего стола), поэтому
 * достать до состояния плеера через композицию нельзя — они в разных деревьях.
 * Здесь лежит ровно то, что окошку нужно: откуда брать кадры, что играет и как
 * нажать «пауза».
 *
 * Кадры не копируются: [sink] — ТОТ ЖЕ сток, из которого рисует главное окно.
 * Свёрнутое окно просто не отрисовывается, а декодирование libVLC не прекращает,
 * так что картинка в окошке идёт без единого лишнего преобразования.
 */
internal object PipBridge {
    /** Сток кадров играющего сейчас плеера. null = играть нечего. */
    var sink: VideoFrameSink? by mutableStateOf(null)
        private set

    var playing: Boolean by mutableStateOf(false)
        private set

    var title: String by mutableStateOf("")
        private set

    /** Обрезка чёрной рамки и режим вписывания — те же, что в большом окне. */
    var fillMode: Boolean by mutableStateOf(false)
        private set

    private var toggle: (() -> Unit)? = null

    /** Окошко сейчас на экране и рисует кадры. */
    var showing: Boolean by mutableStateOf(false)

    /** Главное окно убрано с глаз: свёрнуто или спрятано в трей. */
    var mainHidden: Boolean by mutableStateOf(false)

    /**
     * Кадры кто-нибудь вообще показывает?
     *
     * Пока это было неизвестно, свёрнутое окно продолжало ПРЕВРАЩАТЬ кадры в
     * картинки Skia — по восемь мегабайт нативной памяти двадцать четыре раза в
     * секунду, и всё в никуда. Именно на такой аллокации приложение и умирает, когда
     * в системе кончается память: исключение прилетает из C++ и поймать его из
     * Kotlin нельзя в принципе. Меньше ненужных аллокаций — меньше поводов упасть.
     */
    val anyoneDrawing: Boolean get() = !mainHidden || showing

    /**
     * Пользователь закрыл окошко крестиком — сейчас больше не всплывать.
     *
     * Сбрасывается, когда главное окно возвращается на экран: «закрыл окошко» значит
     * «сейчас не надо», а не «выключил насовсем» — для насовсем есть галка в
     * настройках. Иначе крестик работал бы как незаметное отключение функции.
     */
    var dismissed: Boolean by mutableStateOf(false)

    fun attach(sink: VideoFrameSink, title: String, onTogglePlay: () -> Unit) {
        this.sink = sink
        this.title = title
        this.toggle = onTogglePlay
        dismissed = false
    }

    fun detach(sink: VideoFrameSink) {
        // Только свой сток: к этому моменту мог подключиться уже следующий плеер.
        if (this.sink !== sink) return
        this.sink = null
        this.toggle = null
        playing = false
    }

    fun update(playing: Boolean, fillMode: Boolean) {
        this.playing = playing
        this.fillMode = fillMode
    }

    fun togglePlay() = toggle?.invoke() ?: Unit
}

/**
 * Окошко «картинка в картинке»: всплывает поверх всех окон, когда главное свернули.
 *
 * Без рамки — иначе поверх игры или браузера висел бы заголовок Windows шире самого
 * видео. Двигается окошко перетаскиванием за картинку, размер тянется за края и углы
 * как у любого окна.
 *
 * Пропорции НЕ фиксируются. Сначала я держал 16:9 насильно, пересчитывая высоту из
 * ширины, — и это дерётся с человеком, когда он тянет за верхний или нижний край:
 * окно сопротивляется движению. Кадр и так вписывается в любую форму окна с полями
 * по краям, ровно как в системных проигрывателях, так что мешать незачем.
 */
@Composable
internal fun PictureInPictureWindow(
    sink: VideoFrameSink,
    enhance: VideoEnhance.Level,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    /** Прошлое положение «x,y,w,h» в dp (см. [pipBoundsOf]); пусто — по умолчанию. */
    initialBounds: String = "",
    onBoundsChange: (String) -> Unit = {},
) {
    // Окошко помнит, где его оставили: положение и размер пишутся в настройки при
    // каждом сдвиге, и в следующий раз оно всплывает там же, а не снова в углу.
    val remembered = remember(initialBounds) { parsePipBounds(initialBounds) }
    val state = rememberWindowState(
        width = (remembered?.get(2)?.toFloat() ?: DEFAULT_WIDTH).dp,
        height = (remembered?.get(3)?.toFloat() ?: (DEFAULT_WIDTH / ASPECT)).dp,
        // Правый нижний угол — там, где системные проигрыватели показывают то же
        // самое, и там же меньше всего шансов накрыть что-то важное.
        position = remembered?.let { WindowPosition.Absolute(it[0].dp, it[1].dp) } ?: WindowPosition.PlatformDefault,
        placement = WindowPlacement.Floating,
    )
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { pipBoundsOf(state) }
            .distinctUntilChanged()
            .collect { bounds -> if (bounds.isNotBlank()) onBoundsChange(bounds) }
    }
    Window(
        onCloseRequest = onClose,
        state = state,
        title = "AniBlaze",
        undecorated = true,
        alwaysOnTop = true,
        // Размер тянется ЗА КРАЯ И УГЛЫ ОКНА, как у любой программы.
        //
        // Здесь стояло `false`, и вместо краёв я приделал свой уголок в правом нижнем
        // углу. Получилась «странная кнопка, которая только уменьшает»: тянуть можно
        // было в одном-единственном месте, а считал он ширину в пикселях экрана и
        // задавал её в dp — на мониторе с масштабом больше 100 % окно от этого каждый
        // раз ужималось. Compose умеет тянуть края у окна без рамки сам, если ему
        // разрешить, — уголок убран, вся работа его.
        resizable = true,
        icon = com.aniblaze.desktop.AniBlazeWindowIcon,
    ) {
        // Тёмная подложка окна: без неё Windows на миг показывает белый буфер Swing
        // при появлении, а окошко всплывает часто.
        androidx.compose.runtime.LaunchedEffect(window) {
            window.background = java.awt.Color.BLACK
            window.contentPane.background = java.awt.Color.BLACK
            // Нижний предел — чтобы окошко нельзя было утянуть в точку.
            window.minimumSize = java.awt.Dimension(MIN_WIDTH.toInt(), (MIN_WIDTH / ASPECT).toInt())
        }
        // Пока окошко на экране — кадры нужны, даже если главное окно свёрнуто.
        androidx.compose.runtime.DisposableEffect(Unit) {
            PipBridge.showing = true
            onDispose { PipBridge.showing = false }
        }
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .border(1.dp, Color(0x33FFFFFF))
                .hoverable(interaction),
        ) {
            ComposeVideoSurface(
                sink = sink,
                fillMode = PipBridge.fillMode,
                zoom = 1f,
                enhance = enhance,
                modifier = Modifier.fillMaxSize(),
            )
            // Перетаскивание — за саму картинку, средствами самого Compose Desktop.
            //
            // Своя реализация «сдвинуть окно на сдвиг курсора» здесь была и дёргалась,
            // и уводила курсор — по трём причинам сразу, и все три снимаются готовым
            // WindowDraggableArea:
            //
            //  1. ЗАМКНУТЫЙ КРУГ. Compose отдаёт сдвиг ОТНОСИТЕЛЬНО ОКНА. Двигаешь
            //     окно — курсор внутри него смещается назад, и следующий сдвиг
            //     считается уже от нового места. Окно то догоняет курсор, то
            //     отстаёт: это и есть дёрганье. Здесь точка захвата берётся ОДИН РАЗ
            //     и дальше всё считается в координатах ЭКРАНА.
            //  2. ОТСТАВАНИЕ. state.position едет к окну через снимок композиции, и
            //     пока он доедет, приходит ещё несколько событий — они складываются
            //     со СТАРОЙ позицией, отсюда рывки и промахи. Готовый обработчик
            //     двигает окно напрямую.
            //  3. МАСШТАБ ЭКРАНА. Сдвиг приходит в пикселях, а WindowPosition — в dp,
            //     и на мониторе со 125 % окно уезжало не туда, куда курсор.
            //
            // Двойной клик разворачивает приложение обратно: так ведут себя системные
            // окошки, и искать кнопку не приходится.
            WindowDraggableArea(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().pointerInput(onRestore) {
                        // Двойной клик считается ВРУЧНУЮ и НИЧЕГО НЕ ПОТРЕБЛЯЕТ.
                        //
                        // Готовый detectTapGestures здесь не годится: он забирает
                        // нажатие себе, а WindowDraggableArea ждёт непотреблённого —
                        // и перетаскивание переставало работать вовсе. Поэтому просто
                        // смотрим на промежуток между нажатиями в проходе Initial и не
                        // трогаем событие: до обработчика перетаскивания оно доходит
                        // нетронутым.
                        var previousDownAt = 0L
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val elapsed = down.uptimeMillis - previousDownAt
                            if (previousDownAt != 0L && elapsed in 1..DOUBLE_CLICK_MS) {
                                previousDownAt = 0L
                                onRestore()
                            } else {
                                previousDownAt = down.uptimeMillis
                            }
                        }
                    },
                )
            }
            if (hovered) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            PipBridge.title,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        PipButton(Icons.Filled.OpenInFull, "Развернуть обратно", onRestore)
                        PipButton(Icons.Filled.Close, "Закрыть окошко", onClose)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Box(
                            Modifier.align(Alignment.Center).size(44.dp).clip(RoundedCornerShape(percent = 50))
                                .background(Color(0xCC000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            PipButton(
                                if (PipBridge.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (PipBridge.playing) "Пауза" else "Смотреть",
                                PipBridge::togglePlay,
                                size = 26.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 16.dp,
) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .androidxClickable(onClick)
            .padding(3.dp),
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size))
    }
}

/** Клик без ряби: окошко маленькое, и подсветка на весь угол выглядит грязно. */
private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) },
)

/** Промежуток, в который два нажатия считаются двойным кликом (как у Windows). */
private const val DOUBLE_CLICK_MS = 400L

/** 16:9 — пропорция, в которой приходит всё, что играет приложение. */
private const val ASPECT = 16f / 9f
private const val DEFAULT_WIDTH = 420f
private const val MIN_WIDTH = 220f

/** «x,y,w,h» в dp, целые; пусто — положение ещё не известно (PlatformDefault). */
internal fun pipBoundsOf(state: androidx.compose.ui.window.WindowState): String {
    val position = state.position
    if (!position.isSpecified) return ""
    return listOf(position.x.value, position.y.value, state.size.width.value, state.size.height.value)
        .joinToString(",") { it.toInt().toString() }
}

/** Разбор «x,y,w,h»; мусор или окно меньше минимума — null (окно встанет по умолчанию). */
internal fun parsePipBounds(raw: String): List<Int>? {
    val parts = raw.split(',').map { it.trim().toIntOrNull() ?: return null }
    if (parts.size != 4) return null
    if (parts[2] < MIN_WIDTH || parts[3] < MIN_WIDTH / ASPECT) return null
    return parts
}

package com.aniblaze.desktop.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Тянуть ленту мышью, зажав ЛЕВУЮ кнопку — как пальцем по телефону.
 *
 * Почему это вообще приходится писать руками: LazyRow умеет тянуться перетаскиванием,
 * но foundation намеренно не считает мышь источником такого жеста (в `scrollable`
 * стоит проверка `down.type != PointerType.Mouse`). Пальцем лента едет, мышью — нет,
 * и до дальних карточек мышью можно было добраться только стрелками по краям, по
 * одному экрану за клик.
 *
 * Как жест уживается с кликом по карточке: до порога [DragGate] нажатие НЕ
 * перехватывается и спокойно уходит вниз, поэтому обычный клик открывает тайтл. Как
 * только курсор уехал дальше порога, события начинают потребляться здесь — и Compose
 * сам гасит нажатие у ребёнка, если родитель что-то съел. Отсюда и требуемое
 * поведение: протянул — тайтл не открылся.
 *
 * Тач не трогаем вовсе: у пальца прокрутка LazyRow работает штатно, со своей
 * инерцией, и подменять её нечем и незачем.
 *
 * Правая кнопка теперь свободна (раньше тянула ленту она) — значит в каруселях снова
 * работает контекстное меню постера, и `contextMenu = false` там больше не нужен.
 */
fun Modifier.dragScroll(state: ScrollableState): Modifier = composed {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }

    pointerInput(state) {
        awaitEachGesture {
            // Initial: карточки под лентой слушают то же нажатие, и увидеть его нужно
            // раньше них. Увидеть — но не забрать: ниже нет ни одного consume() до
            // того, как порог протяжки пройден.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) {
                return@awaitEachGesture
            }
            val gate = DragGate()
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    velocity.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) {
                        // Отпускание забираем, только если жест был нашим: иначе это
                        // конец обычного клика, и карточка обязана его получить.
                        if (gate.engaged) change.consume()
                        break
                    }
                    val scroll = gate.push(change.positionChange().x)
                    if (!gate.engaged) continue
                    dragging = true
                    change.consume()
                    // dispatchRawDelta, а не scrollBy: он синхронный, поэтому лента
                    // двигается в том же кадре, что и курсор. Через корутину события
                    // успевали перемешаться, и ход выходил рваным.
                    if (scroll != 0f) state.dispatchRawDelta(-scroll)
                }
            } finally {
                dragging = false
            }
            // Инерция — только после настоящей протяжки. Резкий клик тоже даёт
            // ненулевую скорость на паре пикселей дрожания, и лента уезжала бы от
            // простого нажатия.
            if (gate.engaged) {
                val fling = velocity.calculateVelocity().x
                if (abs(fling) > MIN_FLING_VELOCITY) {
                    scope.launch { state.animateScrollBy(-fling * FLING_DISTANCE_FACTOR) }
                }
            }
        }
    }
        // Курсор говорит, что ленту можно тянуть, ещё до того как её потянули.
        .pointerHoverIcon(if (dragging) PointerIcon.Crosshair else PointerIcon.Hand)
}

/**
 * Точка невозврата жеста: где нажатие перестаёт быть кликом и становится протяжкой.
 *
 * Копится СМЕЩЕНИЕ со знаком, а не пройденный путь: дрожание вправо-влево на пару
 * пикселей само себя гасит и кликом быть не перестаёт, тогда как по сумме модулей оно
 * рано или поздно набрало бы порог и клик пропал бы «на ровном месте».
 */
internal class DragGate(private val slopPx: Float = DRAG_SLOP_PX) {

    /** Порог пройден: с этого момента жест целиком наш и обратно не отдаётся. */
    var engaged: Boolean = false
        private set

    private var pending = 0f

    /** Насколько сдвинуть ленту на этом шаге курсора; 0 — пока это ещё клик. */
    fun push(dx: Float): Float {
        if (engaged) return dx
        pending += dx
        if (abs(pending) < slopPx) return 0f
        engaged = true
        // Накопленное отдаём целиком, а не выбрасываем: иначе первые восемь точек
        // лента стоит на месте и трогается уже с отставанием от курсора.
        return pending.also { pending = 0f }
    }
}

/**
 * Восемь точек — курсор явно ведут, а не целятся в карточку.
 *
 * Мерка мышиная, а не пальцевая: рука на кнопке дрожит на единицы пикселей, тогда как
 * системный порог прокрутки рассчитан на палец и куда крупнее.
 */
internal const val DRAG_SLOP_PX = 8f

/** Ниже этого рывок считаем дрожанием руки, а не броском. */
private const val MIN_FLING_VELOCITY = 120f

/** Насколько далеко доезжает лента после броска, в долях замеренной скорости. */
private const val FLING_DISTANCE_FACTOR = 0.22f

/** Ordinary vertical wheel also scrolls the tab strip; consume before LazyRow to avoid double scroll. */
fun Modifier.horizontalMouseWheel(state: ScrollableState): Modifier = composed {
    val step = 48f * LocalDensity.current.density
    pointerInput(state, step) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) continue
                val delta = change.scrollDelta.let { if (it.x != 0f) it.x else it.y }
                if (delta != 0f) {
                    state.dispatchRawDelta(delta * step)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}

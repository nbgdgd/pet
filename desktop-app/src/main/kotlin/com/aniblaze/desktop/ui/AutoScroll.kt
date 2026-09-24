package com.aniblaze.desktop.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.sign

/**
 * Chrome/Edge/Firefox-style middle-button autoscroll.
 *
 * Press the middle mouse button anywhere over the scrollable to drop an anchor and
 * enter autoscroll mode: an indicator appears at the click point, and moving the
 * cursor above/below the anchor scrolls the list — speed ramps with the cursor's
 * distance from the anchor, so a small nudge creeps and a big one races. Any click,
 * a second middle-click, or a wheel notch exits the mode. Motion is frame-timed, so
 * it's perfectly smooth and framerate-independent.
 */
fun Modifier.browserAutoScroll(state: ScrollableState): Modifier = composed {
    var anchor by remember { mutableStateOf<Offset?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val active = anchor != null
    val density = LocalDensity.current

    // Frame-timed scroller: runs only while a mode is active, scrolling by
    // velocity·dt each frame so speed is smooth and independent of monitor Hz.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                val a = anchor ?: break
                val dt = (now - last) / 1_000_000_000f
                val v = autoScrollVelocity(pointer.y - a.y)
                if (v != 0f) runCatching { state.scrollBy(v * dt) }
            }
            last = now
        }
    }

    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.lastOrNull()
                    if (change != null) pointer = change.position
                    // Read `anchor` live here (not the composition-time `active`
                    // snapshot) — this pointer loop is created once, so a captured
                    // flag would go stale and the exit branch would never fire.
                    when (event.type) {
                        PointerEventType.Press -> {
                            val b = event.buttons
                            if (anchor == null && b.isTertiaryPressed) {
                                // Enter mode, anchored at the cursor.
                                anchor = change?.position ?: pointer
                                event.changes.forEach { it.consume() }
                            } else if (anchor != null) {
                                // Any button press (incl. a second middle click) exits.
                                anchor = null
                                event.changes.forEach { it.consume() }
                            }
                        }
                        PointerEventType.Move -> {
                            if (anchor != null) event.changes.forEach { it.consume() }
                        }
                        PointerEventType.Scroll -> {
                            if (anchor != null) { anchor = null; event.changes.forEach { it.consume() } }
                        }
                        else -> {}
                    }
                }
            }
        }
        .then(if (active) Modifier.pointerHoverIcon(MoveCursor) else Modifier)
        .drawWithContent {
            drawContent()
            val a = anchor ?: return@drawWithContent
            val r = with(density) { 17.dp.toPx() }
            val dir = sign(pointer.y - a.y).toInt()
            // Glassy hub
            drawCircle(Color(0xE6101018), radius = r, center = a)
            drawCircle(Color(0x33FFFFFF), radius = r, center = a, style = Stroke(width = 1.2f))
            drawCircle(AccentOrange.copy(alpha = 0.9f), radius = 2.6f, center = a)
            // Up / down chevrons — the one matching the current scroll direction lights up.
            val up = if (dir < 0) AccentOrange else Color(0x66FFFFFF)
            val down = if (dir > 0) AccentOrange else Color(0x66FFFFFF)
            drawChevron(a, r * 0.62f, pointingUp = true, color = up)
            drawChevron(a, r * 0.62f, pointingUp = false, color = down)
        }
}

/** Signed velocity in px/sec for a given cursor-to-anchor Y offset (dead-zone + quadratic ramp). */
private fun autoScrollVelocity(dy: Float): Float {
    val dead = 16f
    val m = abs(dy) - dead
    if (m <= 0f) return 0f
    // Quadratic ramp gives the browser's "gentle near the anchor, fast far away" feel.
    val speed = (m * m) * 0.65f
    return sign(dy) * speed.coerceAtMost(5200f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChevron(
    center: Offset,
    reach: Float,
    pointingUp: Boolean,
    color: Color,
) {
    val s = 5f
    val yTip = if (pointingUp) center.y - reach else center.y + reach
    val yBase = if (pointingUp) center.y - reach + s else center.y + reach - s
    val path = Path().apply {
        moveTo(center.x - s, yBase)
        lineTo(center.x, yTip)
        lineTo(center.x + s, yBase)
    }
    drawPath(path, color = color, style = Stroke(width = 1.8f))
}

private val MoveCursor = PointerIcon(Cursor(Cursor.MOVE_CURSOR))

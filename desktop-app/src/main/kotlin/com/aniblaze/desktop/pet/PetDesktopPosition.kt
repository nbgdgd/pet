package com.aniblaze.desktop.pet

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Position belongs to the application, not a page. Only the sprite handles gestures. */
@Composable
internal fun PetDesktopPosition(xFraction: Float, yFraction: Float, onMove: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier, (Offset) -> Unit, () -> Unit) -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        val width = maxWidth.value * density
        val height = maxHeight.value * density
        var size by remember { mutableStateOf(IntSize.Zero) }
        var x by remember(xFraction) { mutableStateOf(xFraction) }
        var y by remember(yFraction) { mutableStateOf(yFraction) }
        val left = petDesktopCoordinate(x, width, size.width.toFloat())
        val top = petDesktopCoordinate(y, height, size.height.toFloat())
        content(Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }.onSizeChanged { size = it },
            { delta ->
                if (width > 0) x = ((left + size.width + delta.x) / width).coerceIn(0f, 1f)
                if (height > 0) y = ((top + size.height + delta.y) / height).coerceIn(0f, 1f)
            }, { onMove(x, y) })
    }
}

internal fun petDesktopCoordinate(fraction: Float, extent: Float, size: Float): Float =
    ((fraction.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f) * extent - size)
        .coerceIn(0f, (extent - size).coerceAtLeast(0f))

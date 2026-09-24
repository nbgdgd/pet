package com.aniblaze.desktop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The app / window / taskbar icon, drawn programmatically so no binary asset is
 * needed: the brand's ember→magenta rounded tile with a white "play" mark.
 */
val AniBlazeWindowIcon: Painter = object : Painter() {
    override val intrinsicSize = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val s = size.minDimension
        // Rounded brand tile.
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFF7A3D), Color(0xFFFF3D9D)),
                start = Offset(0f, 0f),
                end = Offset(s, s),
            ),
            size = Size(s, s),
            cornerRadius = CornerRadius(s * 0.24f, s * 0.24f),
        )
        // Soft top highlight.
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x00FFFFFF))),
            size = Size(s, s * 0.5f),
            cornerRadius = CornerRadius(s * 0.24f, s * 0.24f),
        )
        // White play triangle, optically centred.
        val cx = s * 0.42f
        val h = s * 0.30f
        val w = s * 0.26f
        val play = Path().apply {
            moveTo(cx, s / 2f - h / 2f)
            lineTo(cx + w, s / 2f)
            lineTo(cx, s / 2f + h / 2f)
            close()
        }
        drawPath(play, Color.White)
    }
}

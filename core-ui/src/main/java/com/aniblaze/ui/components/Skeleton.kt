package com.aniblaze.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Animated shimmer placeholder shown while content loads. */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF15151A),
            Color(0xFF26262E),
            Color(0xFF15151A),
        ),
        start = Offset(shimmer - 300f, 0f),
        end = Offset(shimmer, 0f),
    )
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(brush))
    }
}

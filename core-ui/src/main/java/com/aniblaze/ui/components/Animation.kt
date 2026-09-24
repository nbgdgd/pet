package com.aniblaze.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer

/** Spring-based bounce scale for press feedback (0.97 on press). */
fun Modifier.bouncePress(): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "pressScale",
    )
    this.scale(scale)
}

/** Stagger entrance animation — call with index * delayMs offset. */
@Composable
fun Modifier.staggerIn(index: Int, delayMs: Int = 80): Modifier {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index * delayMs).toLong())
        visible = true
    }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "stagger",
    )
    return this.graphicsLayer(alpha = progress, translationY = 20f * (1f - progress))
}

/** Reusable fade + slide-up animation spec for screen transitions. */
object AniBlazeTransitions {
    val fadeSlideIn: androidx.compose.animation.EnterTransition
        get() = fadeIn(animationSpec = tween(350)) +
                slideInVertically(animationSpec = tween(350)) { it / 8 }
}

package com.aniblaze.ui.components

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aniblaze.ui.theme.AccentOrange

/** True on Android TV (leanback) devices — D-pad is the primary input there. */
@Composable
fun isTvDevice(): Boolean {
    val uiMode = LocalContext.current.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * Screen-level initial focus for TV: returns a [FocusRequester] (and requests
 * focus once [ready]) only on television devices, null elsewhere so touch
 * screens are completely unaffected.
 */
@Composable
fun rememberTvInitialFocus(ready: Boolean): androidx.compose.ui.focus.FocusRequester? {
    val tv = isTvDevice()
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(tv, ready) {
        if (tv && ready) {
            try {
                requester.requestFocus()
            } catch (_: IllegalStateException) {
                // Focusable not attached yet (list still settling) — the user
                // can still move with the D-pad; no crash, no stuck navigation.
            }
        }
    }
    return if (tv) requester else null
}

/**
 * Visible remote-control focus ring for any focusable element (Material3
 * buttons, custom clickables, list rows): orange border + slight scale-up
 * while focused via D-pad, dimmer border on mouse hover.
 *
 * Focus itself is tracked with [onFocusChanged], so it works regardless of
 * which interaction source the wrapped clickable uses internally.
 */
fun Modifier.tvFocusRing(corner: Dp = 16.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    this
        .onFocusChanged { focused = it.hasFocus }
        .hoverable(hoverSource)
        .scale(if (focused) 1.03f else 1f)
        .border(
            width = when {
                focused -> 2.5.dp
                hovered -> 1.5.dp
                else -> 0.dp
            },
            color = when {
                focused -> AccentOrange
                hovered -> AccentOrange.copy(alpha = 0.55f)
                else -> Color.Transparent
            },
            shape = RoundedCornerShape(corner),
        )
}

/**
 * Clickable with a built-in TV focus ring — for custom boxes/rows/chips that
 * don't use Material components. Mouse clicks and hover work through the
 * same modifier, so touch / mouse / D-pad share one target.
 */
fun Modifier.tvClickable(
    corner: Dp = 16.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    this
        .tvFocusRing(corner)
        .clickable(enabled = enabled, onClick = onClick)
}

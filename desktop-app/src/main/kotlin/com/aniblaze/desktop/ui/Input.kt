package com.aniblaze.desktop.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Browser-style mouse side buttons: button 4 (back) and button 5 (forward)
 * navigate history — the PC-native gesture every desktop browser and file
 * manager supports.
 *
 * Handled on the Main pass, so it fires only after the element under the cursor
 * had its chance (nothing in the UI claims the side buttons, so it always reaches
 * here). Placed on the app root, so it works no matter where the cursor is.
 */
fun Modifier.mouseBackForward(onBack: () -> Unit, onForward: () -> Unit = {}): Modifier =
    pointerInput(onBack, onForward) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press) continue
                val buttons = event.buttons
                when {
                    buttons.isBackPressed -> { onBack(); event.changes.forEach { it.consume() } }
                    buttons.isForwardPressed -> { onForward(); event.changes.forEach { it.consume() } }
                }
            }
        }
    }

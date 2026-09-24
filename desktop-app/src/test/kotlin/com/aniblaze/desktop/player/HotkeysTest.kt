package com.aniblaze.desktop.player

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Привязки клавиш: умолчания, своя клавиша заменяет умолчания, одна клавиша — одно действие. */
class HotkeysTest {
    @Test fun `умолчания - пробел и K пауза, стрелки перемотка, Esc не действие`() {
        assertEquals(PlayerAction.TOGGLE_PLAY, Hotkeys.actionOf(Key.Spacebar, emptyMap()))
        assertEquals(PlayerAction.TOGGLE_PLAY, Hotkeys.actionOf(Key.K, emptyMap()))
        assertEquals(PlayerAction.SEEK_BACK, Hotkeys.actionOf(Key.DirectionLeft, emptyMap()))
        assertEquals(PlayerAction.SCREENSHOT, Hotkeys.actionOf(Key.S, emptyMap()))
        assertNull(Hotkeys.actionOf(Key.Escape, emptyMap()))
        assertNull(Hotkeys.actionOf(Key.Z, emptyMap()))
        assertEquals("Пробел / K", Hotkeys.label(PlayerAction.TOGGLE_PLAY, emptyMap()))
    }

    @Test fun `своя клавиша заменяет умолчания действия и перекрывает чужое умолчание`() {
        val custom = mapOf(PlayerAction.TOGGLE_PLAY to Key.P.keyCode, PlayerAction.MUTE to Key.S.keyCode)
        assertEquals(PlayerAction.TOGGLE_PLAY, Hotkeys.actionOf(Key.P, custom))
        assertNull(Hotkeys.actionOf(Key.Spacebar, custom), "пробел паузой быть перестал")
        assertEquals(PlayerAction.MUTE, Hotkeys.actionOf(Key.S, custom), "своя S сильнее умолчания «снимок»")
        assertNull(Hotkeys.actionOf(Key.M, custom))
        assertEquals("P", Hotkeys.label(PlayerAction.TOGGLE_PLAY, custom))
    }

    @Test fun `настройки - одна клавиша снимается с прежнего действия, null возвращает умолчание`() {
        val s = com.aniblaze.desktop.AppSettings(java.nio.file.Files.createTempDirectory("aniblaze-hotkeys").resolve("state.json").toFile())
        s.setHotkey("play", Key.P.keyCode)
        s.setHotkey("mute", Key.P.keyCode)
        assertEquals(mapOf("mute" to Key.P.keyCode), s.state.value.hotkeys)
        s.setHotkey("mute", null)
        assertTrue(s.state.value.hotkeys.isEmpty())
        assertFalse(Hotkeys.assignable(Key.Escape))
        assertTrue(Hotkeys.assignable(Key.P))
    }
}

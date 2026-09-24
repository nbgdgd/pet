package com.aniblaze.desktop.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode

/**
 * Действия плеера, которые можно повесить на клавиши.
 *
 * У каждого — клавиши по умолчанию (как в браузерных плеерах: пробел, стрелки, M,
 * F) и своя, назначенная в настройках. Esc в списке нет намеренно: выход из
 * полного экрана обязан работать всегда и одинаково, переназначать его незачем.
 */
enum class PlayerAction(val key: String, val label: String, val defaults: List<Key>) {
    TOGGLE_PLAY("play", "Пауза и продолжить", listOf(Key.Spacebar, Key.K)),
    SEEK_BACK("back", "На 5 секунд назад", listOf(Key.DirectionLeft)),
    SEEK_FORWARD("forward", "На 5 секунд вперёд", listOf(Key.DirectionRight)),
    VOLUME_UP("volUp", "Громче", listOf(Key.DirectionUp)),
    VOLUME_DOWN("volDown", "Тише", listOf(Key.DirectionDown)),
    MUTE("mute", "Звук выключить и включить", listOf(Key.M)),
    FULLSCREEN("fullscreen", "Во весь экран и обратно", listOf(Key.F)),
    SUBTITLES("subtitles", "Субтитры включить и выключить", listOf(Key.C)),
    SCREENSHOT("screenshot", "Снимок кадра", listOf(Key.S)),
    NEXT_EPISODE("next", "Следующая серия", listOf(Key.N));

    companion object {
        fun byKey(key: String): PlayerAction? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Привязки клавиш: свои поверх умолчаний. Хранятся в настройках как
 * «действие → код клавиши» (Long, см. [Key.keyCode]); здесь — снимок для плеера,
 * чтобы обработчик клавиш не ходил в настройки на каждое нажатие.
 *
 * Своя клавиша ЗАМЕНЯЕТ умолчания действия целиком (назначил на паузу P — пробел
 * паузой быть перестал), и одна клавиша не может висеть на двух действиях: при
 * назначении она снимается с прежнего (см. [AppSettings.setHotkey]).
 */
object Hotkeys {
    @Volatile var custom: Map<PlayerAction, Long> = emptyMap()

    /** Какое действие на клавише; null — никакого. */
    fun actionOf(key: Key, custom: Map<PlayerAction, Long> = this.custom): PlayerAction? {
        custom.entries.firstOrNull { it.value == key.keyCode }?.let { return it.key }
        return PlayerAction.entries.firstOrNull { action -> action !in custom && key in action.defaults }
    }

    /** Клавиши действия для справки: «Пробел / K». */
    fun keysOf(action: PlayerAction, custom: Map<PlayerAction, Long> = this.custom): List<Key> =
        custom[action]?.let { listOf(Key(it)) } ?: action.defaults

    fun label(action: PlayerAction, custom: Map<PlayerAction, Long> = this.custom): String =
        keysOf(action, custom).joinToString(" / ") { keyName(it) }

    /** Человеческое имя клавиши: «Пробел», «←», «F»; неизвестное — по коду AWT. */
    fun keyName(key: Key): String = when (key) {
        Key.Spacebar -> "Пробел"
        Key.DirectionLeft -> "←"
        Key.DirectionRight -> "→"
        Key.DirectionUp -> "↑"
        Key.DirectionDown -> "↓"
        Key.Enter -> "Enter"
        Key.Tab -> "Tab"
        Key.Backspace -> "Backspace"
        else -> runCatching { java.awt.event.KeyEvent.getKeyText(key.nativeKeyCode) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith("Unknown") } ?: key.toString().removePrefix("Key: ")
    }

    /** Клавиши, которые назначать нельзя: Esc — выход из полного экрана, модификаторы. */
    fun assignable(key: Key): Boolean = key !in listOf(
        Key.Escape, Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight,
        Key.MetaLeft, Key.MetaRight, Key.Unknown,
    )
}

package com.sleepguard.tv

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * Способ 2 (fallback) — управление плеером через медиа-клавиши.
 * Используется, если MediaSession недоступен или не поддерживает seek.
 * Точность перемотки ниже — отправляет KEYCODE_MEDIA_REWIND
 * с настраиваемой длительностью удержания.
 */
class MediaKeyFallbackController(
    private val context: Context,
    private val audioManager: AudioManager
) {

    companion object {
        const val TAG = "MediaKeyFallback"
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Перемотка назад через удержание REWIND и последующая пауза.
     *
     * @param minutesBack целевое количество минут для перемотки
     *                     (используется как приблизительное значение)
     * @param holdDurationMs длительность удержания кнопки REWIND в миллисекундах
     */
    fun rewindAndPause(minutesBack: Int, holdDurationMs: Long) {
        Log.d(TAG, "Запуск перемотки через медиа-кнопки: ~$minutesBack мин, удержание $holdDurationMs мс")

        // Начинаем удержание кнопки REWIND
        sendKeyDown(KeyEvent.KEYCODE_MEDIA_REWIND)

        // Через указанное время отпускаем кнопку
        handler.postDelayed({
            sendKeyUp(KeyEvent.KEYCODE_MEDIA_REWIND)
            Log.d(TAG, "Кнопка REWIND отпущена")

            // Небольшая пауза перед отправкой PAUSE
            handler.postDelayed({
                sendPause()
            }, 300L)

        }, holdDurationMs)
    }

    /**
     * Отправка нажатия клавиши (keydown)
     */
    private fun sendKeyDown(keyCode: Int) {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        audioManager.dispatchMediaKeyEvent(event)
        Log.d(TAG, "Отправлено нажатие: keyCode=$keyCode")
    }

    /**
     * Отправка отпускания клавиши (keyup)
     */
    private fun sendKeyUp(keyCode: Int) {
        val event = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(event)
        Log.d(TAG, "Отправлено отпускание: keyCode=$keyCode")
    }

    /**
     * Отправка команды паузы.
     * Сначала пробуем KEYCODE_MEDIA_PAUSE, если не сработает —
     * KEYCODE_MEDIA_PLAY_PAUSE.
     */
    private fun sendPause() {
        // Пробуем чистую паузу
        val pauseEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        audioManager.dispatchMediaKeyEvent(pauseEvent)
        val pauseUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
        audioManager.dispatchMediaKeyEvent(pauseUp)
        Log.d(TAG, "Отправлен KEYCODE_MEDIA_PAUSE")
    }

    /**
     * Принудительная остановка всех отправленных событий
     */
    fun cancelPendingActions() {
        handler.removeCallbacksAndMessages(null)
    }
}

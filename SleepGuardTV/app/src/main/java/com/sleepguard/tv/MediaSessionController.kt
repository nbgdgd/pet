package com.sleepguard.tv

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

/**
 * Способ 1 (основной) — управление плеером через MediaSession API.
 * Требует разрешения NotificationListenerService.
 * Обеспечивает точную перемотку и надёжную паузу.
 */
class MediaSessionController(private val context: Context) {

    companion object {
        const val TAG = "MediaSessionController"
    }

    /**
     * Проверка доступности MediaSession для управления
     */
    fun isAvailable(): Boolean {
        return try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(context, NotificationListenerService::class.java)
            val sessions = sessionManager.getActiveSessions(componentName)

            // Ищем сессию в состоянии воспроизведения
            val playingSession = findPlayingSession(sessions)
            if (playingSession != null) {
                // Проверяем, поддерживает ли сессия seekTo
                val state = playingSession.playbackState
                val supportsSeek = state?.actions?.and(PlaybackState.ACTION_SEEK_TO.toLong()) != 0L
                Log.d(TAG, "Найдена активная сессия, seek поддерживается: $supportsSeek")
                true
            } else {
                Log.d(TAG, "Нет активных сессий в состоянии PLAYING")
                false
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Нет разрешения NotificationListener: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки MediaSession: ${e.message}")
            false
        }
    }

    /**
     * Поиск контроллера в состоянии воспроизведения
     */
    private fun findPlayingSession(sessions: List<MediaController>): MediaController? {
        return sessions.find { controller ->
            val state = controller.playbackState
            state?.state == PlaybackState.STATE_PLAYING
        }
    }

    /**
     * Перемотка назад на指定 количество минут и постановка на паузу
     */
    fun seekBackAndPause(minutesBack: Int) {
        try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(context, NotificationListenerService::class.java)
            val sessions = sessionManager.getActiveSessions(componentName)
            val controller = findPlayingSession(sessions) ?: run {
                Log.w(TAG, "Нет активной сессии для управления")
                return
            }

            val state = controller.playbackState
            if (state == null) {
                Log.w(TAG, "PlaybackState отсутствует")
                return
            }

            // Проверяем поддержку seekTo
            val supportsSeek = state.actions?.and(PlaybackState.ACTION_SEEK_TO.toLong()) != 0L

            if (supportsSeek) {
                // Вычисляем текущую позицию с учётом времени обновления
                val currentPosition = calculateCurrentPosition(state)
                val seekToMs = (currentPosition - minutesBack * 60_000L).coerceAtLeast(0L)

                Log.d(TAG, "Текущая позиция: ${currentPosition}ms, перемотка на $minutesBack мин -> $seekToMs ms")

                controller.transportControls.seekTo(seekToMs)
            } else {
                Log.w(TAG, "Сессия не поддерживает seekTo, пропускаем перемотку")
            }

            // Ставим на паузу
            controller.transportControls.pause()
            Log.d(TAG, "Пауза выполнена")

        } catch (e: SecurityException) {
            Log.e(TAG, "Ошибка безопасности: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при seekBackAndPause: ${e.message}")
        }
    }

    /**
     * Вычисление текущей позиции воспроизведения с учётом времени
     * последнего обновления состояния.
     */
    private fun calculateCurrentPosition(state: PlaybackState): Long {
        val position = state.position
        val lastUpdate = state.lastPositionUpdateTime

        return if (state.state == PlaybackState.STATE_PLAYING && lastUpdate > 0) {
            val elapsed = System.currentTimeMillis() - lastUpdate
            position + elapsed
        } else {
            position
        }
    }
}

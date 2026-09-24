package com.sleepguard.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Основной Foreground Service для отслеживания сна пользователя.
 * Работает в фоне, периодически показывает overlay-окно с проверкой.
 */
class SleepGuardService : Service() {

    companion object {
        const val TAG = "SleepGuardService"
        const val ACTION_START = "com.sleepguard.tv.START"
        const val ACTION_STOP = "com.sleepguard.tv.STOP"
        const val CHANNEL_ID = "sleep_guard_channel"
        const val NOTIFICATION_ID = 1001
    }

    private lateinit var handler: Handler
    private lateinit var overlayManager: OverlayManager
    private lateinit var mediaSessionController: MediaSessionController
    private lateinit var mediaKeyFallbackController: MediaKeyFallbackController
    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null

    // Таймеры
    private var checkTimer: Runnable? = null
    private var responseTimer: Runnable? = null

    // Настройки (загружаются из SharedPreferences)
    private var checkIntervalMinutes: Int = 60
    private var responseTimeoutSeconds: Int = 60
    private var rewindMinutes: Int = 40
    private var rewindHoldMs: Long = 1000L
    private var monitoringEnabled: Boolean = false

    // Текущий способ управления
    private var activeMethod: String = "Не определён"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        overlayManager = OverlayManager(this)
        mediaSessionController = MediaSessionController(this)
        mediaKeyFallbackController = MediaKeyFallbackController(this, audioManager)

        loadSettings()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                monitoringEnabled = true
                saveMonitoringState(true)
                startForeground(NOTIFICATION_ID, buildNotification("Слежу за сном"))
                acquireWakeLock()
                startCheckTimer()
                Log.d(TAG, "Сервис запущен")
            }
            ACTION_STOP -> {
                monitoringEnabled = false
                saveMonitoringState(false)
                cancelTimers()
                overlayManager.hideOverlay()
                releaseWakeLock()
                stopForeground(true)
                stopSelf()
                Log.d(TAG, "Сервис остановлен")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTimers()
        overlayManager.hideOverlay()
        releaseWakeLock()
    }

    /**
     * Загрузка настроек из SharedPreferences
     */
    private fun loadSettings() {
        val prefs = getSharedPreferences("sleep_guard_prefs", MODE_PRIVATE)
        checkIntervalMinutes = prefs.getInt("check_interval_minutes", 60)
        responseTimeoutSeconds = prefs.getInt("response_timeout_seconds", 60)
        rewindMinutes = prefs.getInt("rewind_minutes", 40)
        rewindHoldMs = prefs.getLong("rewind_hold_ms", 1000L)
        monitoringEnabled = prefs.getBoolean("monitoring_enabled", false)
    }

    /**
     * Сохранение состояния мониторинга
     */
    private fun saveMonitoringState(enabled: Boolean) {
        getSharedPreferences("sleep_guard_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("monitoring_enabled", enabled)
            .apply()
    }

    /**
     * Запуск таймера до следующей проверки
     */
    private fun startCheckTimer() {
        cancelTimers()

        checkTimer = Runnable {
            if (!monitoringEnabled) return@Runnable

            // Проверяем, есть ли активное воспроизведение
            if (isMediaPlaying()) {
                showOverlayCheck()
            } else {
                // Если плеер не играет — ждём, не показывая окно
                handler.postDelayed({ startCheckTimer() }, 10_000L) // Проверка каждые 10 сек
            }
        }

        handler.postDelayed(checkTimer!!, checkIntervalMinutes * 60_000L)
        Log.d(TAG, "Таймер проверки запущен: $checkIntervalMinutes мин")
    }

    /**
     * Проверка, воспроизводится ли сейчас медиа
     */
    private fun isMediaPlaying(): Boolean {
        return try {
            val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = android.content.ComponentName(this, NotificationListenerService::class.java)
            val sessions = sessionManager.getActiveSessions(componentName)

            sessions.any { controller ->
                val state = controller.playbackState
                state?.state == PlaybackState.STATE_PLAYING
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось проверить MediaSession: ${e.message}")
            // Если не можем проверить — считаем, что воспроизведение есть
            true
        }
    }

    /**
     * Показать overlay-окно проверки
     */
    private fun showOverlayCheck() {
        // Определяем доступный способ управления
        activeMethod = determineActiveMethod()
        updateNotification()

        overlayManager.showOverlay(
            onUserResponded = {
                // Пользователь нажал "Я не сплю" — сбрасываем таймер
                cancelTimers()
                startCheckTimer()
                Log.d(TAG, "Пользователь ответил — таймер сброшен")
            }
        )

        // Запускаем таймер ожидания ответа
        responseTimer = Runnable {
            if (overlayManager.isShowing()) {
                overlayManager.hideOverlay()
                performRewindAndPause()
            }
        }
        handler.postDelayed(responseTimer!!, responseTimeoutSeconds * 1000L)

        Log.d(TAG, "Overlay показан, способ: $activeMethod, таймаут: $responseTimeoutSeconds сек")
    }

    /**
     * Определение доступного способа управления плеером
     */
    private fun determineActiveMethod(): String {
        return if (mediaSessionController.isAvailable()) {
            "MediaSession"
        } else {
            "Медиа-кнопки"
        }
    }

    /**
     * Выполнение перемотки назад и постановки на паузу
     */
    private fun performRewindAndPause() {
        handler.post {
            try {
                if (mediaSessionController.isAvailable()) {
                    // Способ 1: MediaSession (точный)
                    mediaSessionController.seekBackAndPause(rewindMinutes)
                    activeMethod = "MediaSession"
                    Log.d(TAG, "Выполнено через MediaSession: перемотка на $rewindMinutes мин")
                } else {
                    // Способ 2: Медиа-кнопки (fallback)
                    mediaKeyFallbackController.rewindAndPause(rewindMinutes, rewindHoldMs)
                    activeMethod = "Медиа-кнопки"
                    Log.d(TAG, "Выполнено через медиа-кнопки: перемотка на $rewindMinutes мин")
                }

                saveActiveMethod(activeMethod)
                updateNotification()

                // Перезапускаем таймер проверки
                cancelTimers()
                startCheckTimer()

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при перемотке/паузе: ${e.message}")
            }
        }
    }

    /**
     * Сохранение текущего способа в настройки
     */
    private fun saveActiveMethod(method: String) {
        getSharedPreferences("sleep_guard_prefs", MODE_PRIVATE)
            .edit()
            .putString("active_method", method)
            .apply()
    }

    /**
     * Создание канала уведомлений (Android 8+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SleepGuard мониторинг",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянное уведомление службы слежения за сном"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Построение foreground-уведомления
     */
    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SleepGuard TV")
            .setContentText("Слежу за сном — способ: $activeMethod")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Обновление уведомления с текущим статусом
     */
    private fun updateNotification() {
        val notification = buildNotification("Слежу за сном")
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Отмена всех активных таймеров
     */
    private fun cancelTimers() {
        checkTimer?.let { handler.removeCallbacks(it) }
        responseTimer?.let { handler.removeCallbacks(it) }
        checkTimer = null
        responseTimer = null
    }

    /**
     * Получение WakeLock для предотвращения засыпания
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SleepGuard::ServiceWakeLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L) // 24 часа максимум
        }
    }

    /**
     * Освобождение WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}

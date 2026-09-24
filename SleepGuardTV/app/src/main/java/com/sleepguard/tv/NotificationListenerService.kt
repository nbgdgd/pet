package com.sleepguard.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log

/**
 * NotificationListenerService — необходим для доступа к MediaSession
 * других приложений. Система требует явного разрешения пользователя
 * через настройки.
 */
class NotificationListenerService : android.service.notification.NotificationListenerService() {

    companion object {
        const val TAG = "NotificationListener"
        const val CHANNEL_ID = "sleep_guard_listener"
        const val NOTIFICATION_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NotificationListenerService создан")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NotificationListenerService уничтожен")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener подключён — доступ к MediaSession получен")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener отключён")
    }
}

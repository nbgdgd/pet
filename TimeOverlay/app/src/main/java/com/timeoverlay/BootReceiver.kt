package com.timeoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Поднимает сервис после перезагрузки, если пользователь этого просил. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.enabled && prefs.autostart && PermissionHelper.hasRequired(context)) {
            OverlayService.start(context)
        }
    }
}

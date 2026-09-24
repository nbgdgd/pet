package com.sleepguard.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/**
 * Главный экран приложения — точка входа для Android TV.
 * Отображает текущий статус и кнопки запуска/настроек.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSettings = findViewById(R.id.btnSettings)

        // Кнопка запуска сервиса
        btnStart.setOnClickListener {
            val intent = Intent(this, SleepGuardService::class.java)
            intent.action = SleepGuardService.ACTION_START
            startForegroundService(intent)
            updateStatus("Служба запущена")
        }

        // Кнопка остановки сервиса
        btnStop.setOnClickListener {
            val intent = Intent(this, SleepGuardService::class.java)
            intent.action = SleepGuardService.ACTION_STOP
            startService(intent)
            updateStatus("Служба остановлена")
        }

        // Кнопка настроек
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Автофокус на кнопке запуска при появлении экрана
        btnStart.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // Восстанавливаем фокус при возврате
        if (!btnStart.hasFocus() && !btnStop.hasFocus() && !btnSettings.hasFocus()) {
            btnStart.requestFocus()
        }
    }

    /**
     * Обновление отображения статуса службы
     */
    private fun updateStatus(message: String? = null) {
        if (message != null) {
            statusText.text = message
            return
        }

        val prefs = getSharedPreferences("sleep_guard_prefs", MODE_PRIVATE)
        val enabled = prefs.getBoolean("monitoring_enabled", false)
        val method = prefs.getString("active_method", "Не определён")

        statusText.text = if (enabled) {
            "Активно — способ: $method"
        } else {
            "Служба не активна"
        }
    }
}

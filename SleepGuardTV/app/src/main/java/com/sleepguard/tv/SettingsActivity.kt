package com.sleepguard.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Экран настроек, управляемый пультом ДУ (D-pad).
 * Все элементы интерфейса крупные и удобные для навигации с дивана.
 */
class SettingsActivity : Activity() {

    private lateinit var seekInterval: SeekBar
    private lateinit var textInterval: TextView
    private lateinit var seekTimeout: SeekBar
    private lateinit var textTimeout: TextView
    private lateinit var seekRewind: SeekBar
    private lateinit var textRewind: TextView
    private lateinit var seekHoldDuration: SeekBar
    private lateinit var textHoldDuration: TextView
    private lateinit var switchEnabled: Switch
    private lateinit var textStatus: TextView
    private lateinit var btnPermissions: Button

    // Значения по умолчанию
    private var intervalMinutes = 60
    private var timeoutSeconds = 60
    private var rewindMinutes = 40
    private var holdDurationMs = 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Привязка элементов интерфейса
        seekInterval = findViewById(R.id.seekInterval)
        textInterval = findViewById(R.id.textInterval)
        seekTimeout = findViewById(R.id.seekTimeout)
        textTimeout = findViewById(R.id.textTimeout)
        seekRewind = findViewById(R.id.seekRewind)
        textRewind = findViewById(R.id.textRewind)
        seekHoldDuration = findViewById(R.id.seekHoldDuration)
        textHoldDuration = findViewById(R.id.textHoldDuration)
        switchEnabled = findViewById(R.id.switchEnabled)
        textStatus = findViewById(R.id.textStatus)
        btnPermissions = findViewById(R.id.btnPermissions)

        loadSettings()
        setupSeekBars()
        setupSwitch()
        setupPermissionsButton()
        updateStatus()

        // Автофокус на первом элементе
        switchEnabled.requestFocus()
    }

    /**
     * Загрузка сохранённых настроек
     */
    private fun loadSettings() {
        val prefs = getSharedPreferences("sleep_guard_prefs", MODE_PRIVATE)
        intervalMinutes = prefs.getInt("check_interval_minutes", 60)
        timeoutSeconds = prefs.getInt("response_timeout_seconds", 60)
        rewindMinutes = prefs.getInt("rewind_minutes", 40)
        holdDurationMs = prefs.getLong("rewind_hold_ms", 1000L)

        // Установка позиций SeekBar
        seekInterval.progress = intervalMinutes - 5 // Минимум 5 минут
        seekTimeout.progress = timeoutSeconds - 10  // Минимум 10 секунд
        seekRewind.progress = rewindMinutes - 5     // Минимум 5 минут
        seekHoldDuration.progress = ((holdDurationMs - 500) / 500).toInt() // Шаг 500мс, минимум 500мс

        updateTexts()
    }

    /**
     * Настройка SeekBar для интервала проверки (5-120 минут)
     */
    private fun setupSeekBars() {
        // Интервал проверки: 5-120 минут
        seekInterval.max = 115 // 120 - 5
        seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                intervalMinutes = progress + 5
                textInterval.text = "Интервал проверки: $intervalMinutes мин"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                saveSettings()
            }
        })

        // Таймаут ответа: 10-180 секунд
        seekTimeout.max = 170 // 180 - 10
        seekTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                timeoutSeconds = progress + 10
                textTimeout.text = "Время ожидания: $timeoutSeconds сек"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                saveSettings()
            }
        })

        // Перемотка назад: 5-60 минут
        seekRewind.max = 55 // 60 - 5
        seekRewind.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                rewindMinutes = progress + 5
                textRewind.text = "Перемотка назад: $rewindMinutes мин"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                saveSettings()
            }
        })

        // Длительность удержания REWIND: 500-10000 мс
        seekHoldDuration.max = 19 // (10000 - 500) / 500
        seekHoldDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                holdDurationMs = (progress.toLong() * 500) + 500
                textHoldDuration.text = "Удержание REWIND: ${holdDurationMs} мс"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                saveSettings()
            }
        })
    }

    /**
     * Настройка переключателя включения/выключения мониторинга
     */
    private fun setupSwitch() {
        switchEnabled.isChecked = getSharedPreferences("sleep_guard_prefs", MODE_PRIVATE)
            .getBoolean("monitoring_enabled", false)

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("sleep_guard_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("monitoring_enabled", isChecked)
                .apply()

            if (isChecked) {
                // Проверяем разрешения перед запуском
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Сначала выдайте разрешение \"Поверх других приложений\"", Toast.LENGTH_LONG).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    switchEnabled.isChecked = false
                    return@setOnCheckedChangeListener
                }

                val intent = Intent(this, SleepGuardService::class.java)
                intent.action = SleepGuardService.ACTION_START
                startForegroundService(intent)
                Toast.makeText(this, "Мониторинг включён", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, SleepGuardService::class.java)
                intent.action = SleepGuardService.ACTION_STOP
                startService(intent)
                Toast.makeText(this, "Мониторинг выключен", Toast.LENGTH_SHORT).show()
            }

            updateStatus()
        }
    }

    /**
     * Кнопка перехода к разрешениям
     */
    private fun setupPermissionsButton() {
        btnPermissions.setOnClickListener {
            // Открываем настройки разрешений overlay
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    /**
     * Обновление текстовых меток
     */
    private fun updateTexts() {
        textInterval.text = "Интервал проверки: $intervalMinutes мин"
        textTimeout.text = "Время ожидания: $timeoutSeconds сек"
        textRewind.text = "Перемотка назад: $rewindMinutes мин"
        textHoldDuration.text = "Удержание REWIND: ${holdDurationMs} мс"
    }

    /**
     * Обновление индикатора статуса
     */
    private fun updateStatus() {
        val prefs = getSharedPreferences("sleep_guard_prefs", MODE_PRIVATE)
        val method = prefs.getString("active_method", "Не определён")
        val enabled = prefs.getBoolean("monitoring_enabled", false)
        val hasOverlay = Settings.canDrawOverlays(this)

        val overlayStatus = if (hasOverlay) "OK" else "нет разрешения"
        textStatus.text = if (enabled) {
            "Статус: Активно\nСпособ: $method\nOverlay: $overlayStatus"
        } else {
            "Статус: Выключено\nOverlay: $overlayStatus"
        }
    }

    /**
     * Сохранение всех настроек
     */
    private fun saveSettings() {
        getSharedPreferences("sleep_guard_prefs", MODE_PRIVATE)
            .edit()
            .putInt("check_interval_minutes", intervalMinutes)
            .putInt("response_timeout_seconds", timeoutSeconds)
            .putInt("rewind_minutes", rewindMinutes)
            .putLong("rewind_hold_ms", holdDurationMs)
            .apply()

        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}

package com.sleepguard.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button

/**
 * Управление overlay-окном поверх других приложений.
 * Показывает окно "Ты спишь?" с кнопкой "Я не сплю".
 * Окно фокусируется для D-pad и реагирует на нажатие OK.
 */
class OverlayManager(private val context: Context) {

    companion object {
        const val TAG = "OverlayManager"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var btnRespond: Button? = null
    private var isOverlayShowing = false
    private var onUserResponded: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Проверка, показано ли сейчас overlay-окно
     */
    fun isShowing(): Boolean = isOverlayShowing

    /**
     * Показать overlay-окно с кнопкой "Я не сплю"
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(
        onUserResponded: () -> Unit
    ) {
        if (isOverlayShowing) {
            Log.d(TAG, "Overlay уже показан, пропускаем")
            return
        }

        this.onUserResponded = onUserResponded

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Параметры окна — КЛЮЧЕВОЕ: без FLAG_NOT_FOCUSABLE, чтобы D-pad работал
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            getWindowType(),
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            title = "SleepGuard Overlay"
        }

        // Надуваем layout overlay
        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_sleep_check, null)
        btnRespond = overlayView!!.findViewById(R.id.btnRespond)

        // Корневой FrameLayout перехватывает все D-pad нажатия
        val rootFrame = overlayView!!
        rootFrame.isFocusable = true
        rootFrame.isFocusableInTouchMode = true
        rootFrame.setOnKeyListener { _, keyCode, event ->
            handleKeyEvent(keyCode, event)
        }

        // Кнопка "Я не сплю"
        btnRespond!!.setOnClickListener {
            Log.d(TAG, "Пользователь нажал 'Я не сплю'")
            dismissAndRespond()
        }

        // Кнопка тоже перехватывает D-pad
        btnRespond!!.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        Log.d(TAG, "D-pad OK нажат на кнопке")
                        dismissAndRespond()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        try {
            windowManager!!.addView(overlayView, layoutParams)
            isOverlayShowing = true

            // Устанавливаем фокус на кнопку с задержкой для надёжности
            handler.postDelayed({
                btnRespond?.requestFocus()
                btnRespond?.requestFocusFromTouch()
                Log.d(TAG, "Фокус установлен на кнопку")
            }, 100)

            Log.d(TAG, "Overlay-окно показано")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка показа overlay: ${e.message}")
            isOverlayShowing = false
        }
    }

    /**
     * Обработка нажатий D-pad клавиш
     */
    private fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                Log.d(TAG, "D-pad OK нажат на корневом элементе")
                dismissAndRespond()
                true
            }
            else -> false
        }
    }

    /**
     * Закрытие окна и уведомление о ответе пользователя
     */
    private fun dismissAndRespond() {
        hideOverlay()
        onUserResponded?.invoke()
    }

    /**
     * Скрытие overlay-окна
     */
    fun hideOverlay() {
        if (!isOverlayShowing) return

        try {
            windowManager?.removeView(overlayView)
            overlayView = null
            btnRespond = null
            isOverlayShowing = false
            Log.d(TAG, "Overlay-окно скрыто")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка скрытия overlay: ${e.message}")
            isOverlayShowing = false
        }
    }

    /**
     * Получение типа окна в зависимости от версии Android
     */
    private fun getWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            LayoutParams.TYPE_SYSTEM_ALERT
        }
    }
}

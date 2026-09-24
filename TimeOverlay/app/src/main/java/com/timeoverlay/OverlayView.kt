package com.timeoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Сам оверлей: скруглённый прямоугольник с моноширинным временем по центру.
 * Тот же класс используется как живое превью на экране настроек.
 */
class OverlayView(context: Context) : View(context) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val rect = RectF()

    private var text = "0:00"
    private var cornerPercent = 100

    fun setStyle(
        textSizeSp: Float,
        cornerPercent: Int,
        bgColor: Int,
        textColor: Int,
        opacityPercent: Int
    ) {
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textSizeSp,
            resources.displayMetrics
        )
        textPaint.color = textColor
        backgroundPaint.color = bgColor
        this.cornerPercent = cornerPercent.coerceIn(0, 100)
        alpha = opacityPercent.coerceIn(0, 100) / 100f
        requestLayout()
        invalidate()
    }

    fun setTime(value: String) {
        if (value == text) return
        val widthChanged = value.length != text.length
        text = value
        if (widthChanged) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val padH = (textPaint.textSize * PAD_H_RATIO).roundToInt()
        val padV = (textPaint.textSize * PAD_V_RATIO).roundToInt()
        val metrics = textPaint.fontMetrics
        val width = textPaint.measureText(text).roundToInt() + padH * 2
        val height = (metrics.descent - metrics.ascent).roundToInt() + padV * 2
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = min(width, height) / 2f * cornerPercent / 100f
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)

        val metrics = textPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, (width - textPaint.measureText(text)) / 2f, baseline, textPaint)
    }

    private companion object {
        const val PAD_H_RATIO = 0.55f
        const val PAD_V_RATIO = 0.28f
    }
}

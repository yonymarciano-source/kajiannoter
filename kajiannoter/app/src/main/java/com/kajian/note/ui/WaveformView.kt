package com.kajian.note.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
    }
    private val barCount = 28
    private val bars = FloatArray(barCount) { 0.08f }
    private var level = 0f
    private val rect = RectF()

    fun setLevel(rms: Float) {
        level = (rms / 25f).coerceIn(0.05f, 1f)
        shift()
        invalidate()
    }

    private fun shift() {
        for (i in 0 until barCount - 1) bars[i] = bars[i + 1]
        bars[barCount - 1] = (level * (0.6f + Random.nextFloat() * 0.4f)).coerceIn(0.05f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val totalSpacing = w * 0.35f
        val bw = (w - totalSpacing) / barCount
        val sp = totalSpacing / (barCount + 1)
        val cy = h / 2f

        for (i in 0 until barCount) {
            val bh = bars[i] * h * 0.85f
            val l = sp + i * (bw + sp)
            val alpha = (80 + bars[i] * 175).toInt().coerceIn(80, 255)
            paint.alpha = alpha
            rect.set(l, cy - bh / 2, l + bw, cy + bh / 2)
            canvas.drawRoundRect(rect, bw / 2, bw / 2, paint)
        }
    }
}

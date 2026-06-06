package com.kajian.note.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Custom view overlay di atas SeekBar untuk tampilkan titik kuning di posisi bookmark.
 * Letakkan di FrameLayout bersama SeekBar dengan match_parent width.
 */
class BookmarkOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5C400")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60F5C400")
        style = Paint.Style.FILL
    }

    var bookmarks: List<Int> = emptyList()   // timestamp dalam ms
    var totalDurationMs: Int = 0
    var currentPositionMs: Int = 0           // untuk highlight bookmark aktif

    // Padding kiri-kanan SeekBar (thumb radius ~14dp)
    private val trackPadding get() = (14 * resources.displayMetrics.density).toInt()

    fun update(bookmarks: List<Int>, totalMs: Int, currentMs: Int) {
        this.bookmarks = bookmarks
        this.totalDurationMs = totalMs
        this.currentPositionMs = currentMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totalDurationMs <= 0 || bookmarks.isEmpty()) return

        val trackWidth = width - trackPadding * 2f
        val cy = height / 2f
        val radius = (5 * resources.displayMetrics.density)

        bookmarks.forEach { ms ->
            val fraction = ms.toFloat() / totalDurationMs
            val cx = trackPadding + fraction * trackWidth

            val isNear = Math.abs(ms - currentPositionMs) <= 3000
            if (isNear) {
                // Ring glow untuk bookmark aktif
                canvas.drawCircle(cx, cy, radius * 2.2f, ringPaint)
            }
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }
}

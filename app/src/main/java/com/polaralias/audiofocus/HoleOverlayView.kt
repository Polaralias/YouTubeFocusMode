package com.polaralias.audiofocus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class HoleOverlayView(ctx: Context) : View(ctx) {
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private var hole: RectF? = null
    private var maskEnabled: Boolean = true

    fun setHole(r: RectF?) { hole = r; invalidate() }
    fun setMaskEnabled(enabled: Boolean) { maskEnabled = enabled; invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!maskEnabled) {
            return false
        }
        val holeRect = hole
        if (holeRect == null) {
            return true
        }
        val insideHole = holeRect.contains(event.x, event.y)
        return if (insideHole) {
            false
        } else {
            true
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!maskEnabled && hole == null) return
        val sc = canvas.saveLayer(null, null)
        if (maskEnabled) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        hole?.let { canvas.drawRoundRect(it, 24f, 24f, clearPaint) }
        canvas.restoreToCount(sc)
    }
}

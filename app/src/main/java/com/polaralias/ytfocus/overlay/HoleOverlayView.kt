package com.polaralias.ytfocus.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.polaralias.ytfocus.R

class HoleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.black_mask)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private var holeRect: RectF? = null
    private val cornerRadius = 16f * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
    }

    fun setHole(rect: RectF?) {
        holeRect = rect
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        val rect = holeRect
        if (rect != null) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, clearPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = holeRect
        if (rect != null && rect.contains(event.x, event.y)) {
            return false
        }
        return true
    }
}

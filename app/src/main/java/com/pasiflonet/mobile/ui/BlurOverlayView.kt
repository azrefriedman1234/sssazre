package com.pasiflonet.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class BlurOverlayView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        alpha = 220
    }

    private var drawEnabled = false
    private var startX = 0f
    private var startY = 0f
    private var current: RectF? = null
    private val rects = mutableListOf<RectF>()

    fun setDrawEnabled(enabled: Boolean) {
        drawEnabled = enabled
        visibility = if (enabled) VISIBLE else GONE
        if (!enabled) current = null
        invalidate()
    }

    fun toggleDraw(): Boolean {
        setDrawEnabled(!drawEnabled)
        return drawEnabled
    }

    fun clearRects() {
        rects.clear()
        current = null
        invalidate()
    }

    fun exportRectsNormalized(): List<RectF> {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        return rects.map { r ->
            RectF(
                (r.left / w).coerceIn(0f, 1f),
                (r.top / h).coerceIn(0f, 1f),
                (r.right / w).coerceIn(0f, 1f),
                (r.bottom / h).coerceIn(0f, 1f)
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rects.forEach { canvas.drawRect(it, paint) }
        current?.let { canvas.drawRect(it, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                current = RectF(startX, startY, startX, startY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val r = current ?: return true
                r.left = minOf(startX, event.x)
                r.top = minOf(startY, event.y)
                r.right = maxOf(startX, event.x)
                r.bottom = maxOf(startY, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { r ->
                    if ((r.right - r.left) > 10 && (r.bottom - r.top) > 10) {
                        rects.add(RectF(r))
                    }
                }
                current = null
                invalidate()
                return true
            }
        }
        return false
    }
}

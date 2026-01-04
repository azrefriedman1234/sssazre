package com.pasiflonet.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class BlurOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var blurMode: Boolean = false
        set(value) {
            field = value
            if (!value) current = null
            invalidate()
        }

    var allowRectangles: Boolean = true

    private val rects = ArrayList<RectF>() // normalized 0..1
    private var current: RectF? = null
    private var startX = 0f
    private var startY = 0f

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 60
    }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2f
        alpha = 220
    }

    fun clearAll() {
        rects.clear()
        current = null
        invalidate()
    }

    fun exportRectsNormalized(): List<RectF> {
        return rects.map { RectF(it) }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!allowRectangles || !blurMode) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                val nx = normX(startX)
                val ny = normY(startY)
                current = RectF(nx, ny, nx, ny)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val cx = ev.x
                val cy = ev.y
                val l = min(normX(startX), normX(cx))
                val t = min(normY(startY), normY(cy))
                val r = max(normX(startX), normX(cx))
                val b = max(normY(startY), normY(cy))
                current?.set(l, t, r, b)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = current
                current = null
                if (r != null && r.width() > 0.02f && r.height() > 0.02f) {
                    rects.add(r)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!blurMode) return

        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        fun drawNorm(r: RectF) {
            val px = RectF(r.left * w, r.top * h, r.right * w, r.bottom * h)
            canvas.drawRect(px, paintFill)
            canvas.drawRect(px, paintStroke)
        }

        rects.forEach { drawNorm(it) }
        current?.let { drawNorm(it) }
    }

    private fun normX(x: Float): Float = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
    private fun normY(y: Float): Float = (y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
}

package com.pasiflonet.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BlurOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var blurMode: Boolean = false
    var enabledForImage: Boolean = false
        private set

    var allowRectangles: Boolean = true

    private val rects = mutableListOf<RectF>()
    private var downX = 0f
    private var downY = 0f
    private var curRect: RectF? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(220, 0, 200, 255)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 0, 200, 255)
    }

    fun setEnabledForImage(v: Boolean) {
        enabledForImage = v
        // לא מנקים rects כדי לאפשר לוידאו להשתמש במלבנים שנצייר
        invalidate()
    }

    fun exportRectsNormalized(): List<RectF> {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        return rects.map {
            RectF(
                (it.left / w).coerceIn(0f, 1f),
                (it.top / h).coerceIn(0f, 1f),
                (it.right / w).coerceIn(0f, 1f),
                (it.bottom / h).coerceIn(0f, 1f)
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (r in rects) {
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, strokePaint)
        }
        curRect?.let {
            canvas.drawRect(it, fillPaint)
            canvas.drawRect(it, strokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!allowRectangles) return false
        if (!blurMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                curRect = RectF(downX, downY, downX, downY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val r = curRect ?: return true
                r.left = min(downX, event.x)
                r.top = min(downY, event.y)
                r.right = max(downX, event.x)
                r.bottom = max(downY, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = curRect
                curRect = null
                if (r != null && abs(r.width()) > 10 && abs(r.height()) > 10) {
                    rects.add(r)
                }
                invalidate()
                return true
            }
        }
        return false
    }
}

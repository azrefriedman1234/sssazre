package com.pasiflonet.mobile.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class BlurOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** האם מותר לעבוד (למשל רק לתמונות) */
    var enabledForImage: Boolean = false
        private set

    /** מצב ציור מלבני טשטוש */
    var blurMode: Boolean = false

    /** רשימת מלבנים (ביחידות של ה-View) */
    private val rects = mutableListOf<RectF>()

    private var downX = 0f
    private var downY = 0f
    private var curRect: RectF? = null

    /** Callback כאשר המשתמש סיים מלבן */
    var onRectFinalized: ((RectF) -> Unit)? = null

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
        if (!v) {
            blurMode = false
            rects.clear()
            curRect = null
            invalidate()
        }
    }

    fun clearRects() {
        rects.clear()
        curRect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (r in rects) {
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, strokePaint)
        }
        curRect?.let { r ->
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, strokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledForImage || !blurMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                curRect = RectF(downX, downY, downX, downY)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.x
                val y = event.y
                curRect = RectF(
                    min(downX, x),
                    min(downY, y),
                    max(downX, x),
                    max(downY, y)
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = curRect
                curRect = null
                if (r != null && r.width() > 12f && r.height() > 12f) {
                    rects.add(r)
                    onRectFinalized?.invoke(r)
                }
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }
}

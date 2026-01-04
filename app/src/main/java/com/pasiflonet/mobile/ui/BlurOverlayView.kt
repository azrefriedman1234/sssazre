package com.pasiflonet.mobile.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BlurOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** תואם לקוד שכבר כתבת ב-DetailsActivity */
    var blurMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** אם false – לא ניתן לצייר מלבנים */
    var allowRectangles: Boolean = true

    private val rects = mutableListOf<RectF>()
    private var downX = 0f
    private var downY = 0f
    private var drawing = false
    private var current: RectF? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55FFFFFF
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xAA00AAFF.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!blurMode) return

        for (r in rects) {
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, strokePaint)
        }
        current?.let {
            canvas.drawRect(it, fillPaint)
            canvas.drawRect(it, strokePaint)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!blurMode || !allowRectangles) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                drawing = true
                current = RectF(downX, downY, downX, downY)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return false
                val x = ev.x
                val y = ev.y
                current = RectF(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!drawing) return false
                drawing = false
                val r = current
                current = null
                if (r != null && r.width() > 12 && r.height() > 12) rects.add(r)
                invalidate()
                return true
            }
        }
        return false
    }

    fun clearRects() {
        rects.clear()
        current = null
        invalidate()
    }

    /** מחזיר מלבנים מנורמלים 0..1 לשליחה ל-FFmpeg */
    fun exportRectsNormalized(): List<RectF> {
        val w = max(1f, width.toFloat())
        val h = max(1f, height.toFloat())
        return rects.map { r ->
            RectF(
                (r.left / w).coerceIn(0f, 1f),
                (r.top / h).coerceIn(0f, 1f),
                (r.right / w).coerceIn(0f, 1f),
                (r.bottom / h).coerceIn(0f, 1f)
            )
        }
    }
}

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
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    data class RectN(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private val rectsPx = mutableListOf<RectF>()
    private var cur: RectF? = null
    private var drawEnabled = false

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FF0000  // אדום שקוף קל כדי לראות
    }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0xAAFF0000.toInt()
    }

    fun setDrawEnabled(enabled: Boolean) {
        drawEnabled = enabled
        if (!enabled) cur = null
        invalidate()
    }

    fun isDrawEnabled(): Boolean = drawEnabled

    fun clearRects() {
        rectsPx.clear()
        cur = null
        invalidate()
    }

    fun exportRectsNormalized(): List<RectN> {
        val w = max(width, 1).toFloat()
        val h = max(height, 1).toFloat()
        return rectsPx.mapNotNull { r ->
            val l = (min(r.left, r.right) / w).coerceIn(0f, 1f)
            val t = (min(r.top, r.bottom) / h).coerceIn(0f, 1f)
            val rr = (max(r.left, r.right) / w).coerceIn(0f, 1f)
            val bb = (max(r.top, r.bottom) / h).coerceIn(0f, 1f)
            if (rr - l < 0.01f || bb - t < 0.01f) null else RectN(l, t, rr, bb)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!drawEnabled) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cur = RectF(ev.x, ev.y, ev.x, ev.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                cur?.let {
                    it.right = ev.x
                    it.bottom = ev.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cur?.let {
                    // להימנע ממלבנים זעירים
                    if (abs(it.right - it.left) > 15 && abs(it.bottom - it.top) > 15) {
                        rectsPx.add(RectF(it))
                    }
                }
                cur = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // מצייר מלבנים קיימים + מלבן נוכחי
        for (r in rectsPx) {
            canvas.drawRect(r, paintFill)
            canvas.drawRect(r, paintStroke)
        }
        cur?.let {
            canvas.drawRect(it, paintFill)
            canvas.drawRect(it, paintStroke)
        }
    }
}

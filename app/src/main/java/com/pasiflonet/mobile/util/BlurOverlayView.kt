package com.pasiflonet.mobile.util

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * שכבת ציור פשוטה: גוררים כדי ליצור מלבן טשטוש.
 * שומר מלבנים כיחס (0..1) כדי שנוכל להפוך לפיקסלים ב-ffmpeg.
 */
class BlurOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    data class RectN(val l: Float, val t: Float, val r: Float, val b: Float)

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.FILL
    }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    var enabledDraw: Boolean = false
        set(v) { field = v; invalidate() }

    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    private val rects = mutableListOf<RectN>()

    fun getRects(): List<RectN> = rects.toList()

    fun clear() { rects.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // existing
        for (rn in rects) {
            val r = RectF(rn.l * width, rn.t * height, rn.r * width, rn.b * height)
            canvas.drawRect(r, paintFill)
            canvas.drawRect(r, paintStroke)
        }

        // current drag
        if (enabledDraw && dragging) {
            val l = min(downX, curX)
            val t = min(downY, curY)
            val r = max(downX, curX)
            val b = max(downY, curY)
            val rr = RectF(l, t, r, b)
            canvas.drawRect(rr, paintFill)
            canvas.drawRect(rr, paintStroke)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!enabledDraw) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                curX = downX
                curY = downY
                dragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                curX = ev.x
                curY = ev.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    val l = min(downX, curX)
                    val t = min(downY, curY)
                    val r = max(downX, curX)
                    val b = max(downY, curY)
                    if (abs(r - l) > 20 && abs(b - t) > 20) {
                        rects += RectN(l / width, t / height, r / width, b / height)
                    }
                    invalidate()
                }
                return true
            }
        }
        return false
    }
}

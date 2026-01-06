package com.pasiflonet.mobile.ui.editor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class OverlayEditorView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) // normalized 0..1

    private val rects = ArrayList<NRect>()
    private var drawing = false
    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f

    var blurMode: Boolean = true
        set(v) { field = v; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(255, 0, 200, 255)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 0, 200, 255)
    }

    fun getBlurRectsNormalized(): List<NRect> = rects.toList()

    fun undoLast() {
        if (rects.isNotEmpty()) {
            rects.removeAt(rects.lastIndex)
            invalidate()
        }
    }

    fun clearAll() {
        rects.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // draw saved rects
        for (nr in rects) {
            val rf = RectF(nr.l * width, nr.t * height, nr.r * width, nr.b * height).apply { sort() }
            canvas.drawRect(rf, fill)
            canvas.drawRect(rf, stroke)
        }

        // draw current rect while dragging
        if (drawing && blurMode) {
            val rf = RectF(startX, startY, curX, curY).apply { sort() }
            canvas.drawRect(rf, fill)
            canvas.drawRect(rf, stroke)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!blurMode) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawing = true
                startX = ev.x
                startY = ev.y
                curX = ev.x
                curY = ev.y
                parent?.requestDisallowInterceptTouchEvent(true)
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
                curX = ev.x
                curY = ev.y
                drawing = false
                parent?.requestDisallowInterceptTouchEvent(false)

                val dx = abs(curX - startX)
                val dy = abs(curY - startY)
                if (dx > 20 && dy > 20) {
                    val l = (minOf(startX, curX) / width).coerceIn(0f, 1f)
                    val t = (minOf(startY, curY) / height).coerceIn(0f, 1f)
                    val r = (maxOf(startX, curX) / width).coerceIn(0f, 1f)
                    val b = (maxOf(startY, curY) / height).coerceIn(0f, 1f)
                    rects.add(NRect(l, t, r, b))
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun RectF.sort() {
        val l0 = left
        val t0 = top
        val r0 = right
        val b0 = bottom
        left = minOf(l0, r0)
        right = maxOf(l0, r0)
        top = minOf(t0, b0)
        bottom = maxOf(t0, b0)
    }
}

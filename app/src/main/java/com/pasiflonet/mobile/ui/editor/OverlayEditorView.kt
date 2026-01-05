package com.pasiflonet.mobile.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Overlay view that lets user draw BLUR rectangles.
 * Stores rectangles normalized (0..1) so they survive size changes and can be sent to FFmpeg worker.
 */
class OverlayEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // normalized rectangles (0..1)
    private val blurNRects: MutableList<RectF> = mutableListOf()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 220
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 60
    }

    var blurDrawEnabled: Boolean = true

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var currentViewRect: RectF? = null

    init {
        // Ensure we actually draw
        setWillNotDraw(false)
        isClickable = true
    }

    /** Export normalized rects as "l,t,r,b;l,t,r,b" */
    fun exportBlurRectsString(): String =
        blurNRects.joinToString(";") { r ->
            "${r.left},${r.top},${r.right},${r.bottom}"
        }

    /** Import normalized rects from string */
    fun importBlurRectsString(s: String) {
        blurNRects.clear()
        val t = s.trim()
        if (t.isEmpty()) {
            invalidate()
            return
        }
        t.split(";").forEach { part ->
            val p = part.trim()
            if (p.isEmpty()) return@forEach
            val nums = p.split(",")
            if (nums.size != 4) return@forEach
            try {
                val l = nums[0].toFloat()
                val tt = nums[1].toFloat()
                val r = nums[2].toFloat()
                val b = nums[3].toFloat()
                blurNRects.add(RectF(l, tt, r, b).normalized01())
            } catch (_: Throwable) {}
        }
        invalidate()
    }

    fun clearBlurRects() {
        blurNRects.clear()
        currentViewRect = null
        invalidate()
    }

    fun getBlurRectsNormalized(): List<RectF> = blurNRects.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // draw saved rects
        for (nr in blurNRects) {
            val vr = normToView(nr)
            canvas.drawRect(vr, fillPaint)
            canvas.drawRect(vr, strokePaint)
        }

        // draw current dragging rect
        currentViewRect?.let { vr ->
            canvas.drawRect(vr, fillPaint)
            canvas.drawRect(vr, strokePaint)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!blurDrawEnabled) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = true
                currentViewRect = RectF(downX, downY, downX, downY)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val x = ev.x
                val y = ev.y
                currentViewRect = RectF(
                    min(downX, x),
                    min(downY, y),
                    max(downX, x),
                    max(downY, y)
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)

                val vr = currentViewRect
                currentViewRect = null

                if (vr != null) {
                    val w = width.toFloat().coerceAtLeast(1f)
                    val h = height.toFloat().coerceAtLeast(1f)
                    val minSizePx = 12f

                    if (abs(vr.width()) >= minSizePx && abs(vr.height()) >= minSizePx) {
                        val nr = RectF(
                            (vr.left / w),
                            (vr.top / h),
                            (vr.right / w),
                            (vr.bottom / h)
                        ).normalized01()
                        blurNRects.add(nr)
                    }
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun normToView(n: RectF): RectF {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        return RectF(n.left * w, n.top * h, n.right * w, n.bottom * h)
    }

    private fun RectF.normalized01(): RectF {
        val l = min(1f, max(0f, min(left, right)))
        val r = min(1f, max(0f, max(left, right)))
        val t = min(1f, max(0f, min(top, bottom)))
        val b = min(1f, max(0f, max(top, bottom)))
        return RectF(l, t, r, b)
    }
}

package com.pasiflonet.mobile.ui.editor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal stable OverlayEditorView:
 * - Draw watermark bitmap preview (scaled יחסית למסך)
 * - Draw blur rectangles preview (רק ויזואלי)
 * - Exposes blur rects + watermark normalized coords for SendWorker
 *
 * NOTE: blur rects stored NORMALIZED (0..1) relative to view size.
 */
class OverlayEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { WATERMARK, BLUR }
    var mode: Mode = Mode.WATERMARK

    // Watermark
    var watermarkBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    // normalized [0..1]
    var wmX: Float = 0.82f
    var wmY: Float = 0.82f
    var wmW: Float = 0.22f // watermark width as fraction of view width

    // Blur rectangles normalized
    private val blurRectsN = mutableListOf<RectF>()

    private val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blurPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val blurFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 40
    }

    // temp drawing
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var drawingRect: RectF? = null
    private var draggingWm = false

    fun clearBlurRects() {
        blurRectsN.clear()
        drawingRect = null
        invalidate()
    }

    /** returns "l,t,r,b;l,t,r,b" normalized */
    fun blurRectsString(): String {
        return blurRectsN.joinToString(";") { r ->
            "${r.left.coerceIn(0f,1f)},${r.top.coerceIn(0f,1f)},${r.right.coerceIn(0f,1f)},${r.bottom.coerceIn(0f,1f)}"
        }
    }

    fun setBlurRectsFromString(s: String) {
        blurRectsN.clear()
        val parts = s.trim().split(";").map { it.trim() }.filter { it.isNotEmpty() }
        for (p in parts) {
            val nums = p.split(",").map { it.trim() }
            if (nums.size == 4) {
                val l = nums[0].toFloatOrNull() ?: continue
                val t = nums[1].toFloatOrNull() ?: continue
                val r = nums[2].toFloatOrNull() ?: continue
                val b = nums[3].toFloatOrNull() ?: continue
                blurRectsN.add(normRect(l, t, r, b))
            }
        }
        invalidate()
    }

    private fun normRect(l: Float, t: Float, r: Float, b: Float): RectF {
        val left = min(l, r).coerceIn(0f, 1f)
        val right = max(l, r).coerceIn(0f, 1f)
        val top = min(t, b).coerceIn(0f, 1f)
        val bottom = max(t, b).coerceIn(0f, 1f)
        return RectF(left, top, right, bottom)
    }

    private fun toViewRect(n: RectF): RectF {
        val w = max(1f, width.toFloat())
        val h = max(1f, height.toFloat())
        return RectF(n.left * w, n.top * h, n.right * w, n.bottom * h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw blur rect previews
        for (rN in blurRectsN) {
            val r = toViewRect(rN)
            canvas.drawRect(r, blurFillPaint)
            canvas.drawRect(r, blurPreviewPaint)
        }
        drawingRect?.let {
            canvas.drawRect(it, blurFillPaint)
            canvas.drawRect(it, blurPreviewPaint)
        }

        // Draw watermark preview
        val bm = watermarkBitmap
        if (bm != null && width > 0 && height > 0) {
            val w = width.toFloat()
            val h = height.toFloat()

            val targetW = (w * wmW).coerceAtLeast(24f)
            val scale = targetW / bm.width.toFloat()
            val targetH = bm.height.toFloat() * scale

            val cx = (wmX.coerceIn(0f, 1f) * w)
            val cy = (wmY.coerceIn(0f, 1f) * h)

            val left = (cx - targetW / 2f).coerceIn(0f, w - targetW)
            val top = (cy - targetH / 2f).coerceIn(0f, h - targetH)

            val dst = RectF(left, top, left + targetW, top + targetH)
            canvas.drawBitmap(bm, null, dst, wmPaint)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = ev.x
                dragStartY = ev.y

                if (mode == Mode.WATERMARK) {
                    // start dragging if tap near wm center
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val cx = wmX * w
                    val cy = wmY * h
                    draggingWm = (abs(ev.x - cx) < 120f && abs(ev.y - cy) < 120f)
                    return true
                } else {
                    // start drawing blur rect
                    drawingRect = RectF(ev.x, ev.y, ev.x, ev.y)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.WATERMARK) {
                    if (!draggingWm) return true
                    wmX = (ev.x / width.toFloat()).coerceIn(0f, 1f)
                    wmY = (ev.y / height.toFloat()).coerceIn(0f, 1f)
                    invalidate()
                    return true
                } else {
                    val r = drawingRect ?: return true
                    r.right = ev.x
                    r.bottom = ev.y
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.WATERMARK) {
                    draggingWm = false
                    invalidate()
                    return true
                } else {
                    val r = drawingRect
                    drawingRect = null
                    if (r != null) {
                        val w = width.toFloat()
                        val h = height.toFloat()
                        val n = normRect(r.left / w, r.top / h, r.right / w, r.bottom / h)
                        // ignore tiny rects
                        if ((n.right - n.left) > 0.02f && (n.bottom - n.top) > 0.02f) {
                            blurRectsN.add(n)
                        }
                    }
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}

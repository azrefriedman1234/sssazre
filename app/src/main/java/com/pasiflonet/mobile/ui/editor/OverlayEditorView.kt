package com.pasiflonet.mobile.ui.editor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // --- Watermark ---
    private var watermarkBitmap: Bitmap? = null

    // center position normalized [0..1]
    private var wmCxN: Float = 0.5f
    private var wmCyN: Float = 0.5f

    // watermark scale relative to view min dimension
    private var wmScale: Float = 0.25f

    // --- Blur rects stored normalized [0..1] in RectF ---
    private val blurRectsN: MutableList<RectF> = mutableListOf()

    // Current drawing rect (normalized)
    private var activeRectN: RectF? = null
    private var drawingBlur: Boolean = false
    private var draggingWm: Boolean = false

    private var downXN: Float = 0f
    private var downYN: Float = 0f

    private val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Always draw blur previews
    private val blurPreviewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 0, 0, 0)
    }
    private val blurPreviewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(220, 255, 0, 0)
    }

    // ---------- Public API ----------
    fun setWatermarkBitmap(bm: Bitmap?) {
        watermarkBitmap = bm
        invalidate()
    }

    fun clearWatermark() {
        watermarkBitmap = null
        invalidate()
    }

    fun setWatermarkCenterNormalized(x: Float, y: Float) {
        wmCxN = clamp01(x)
        wmCyN = clamp01(y)
        invalidate()
    }

    fun getWatermarkCenterNormalized(): Pair<Float, Float> = Pair(wmCxN, wmCyN)

    fun setWatermarkScaleRelative(scale: Float) {
        wmScale = clamp(scale, 0.05f, 0.9f)
        invalidate()
    }

    /** Normalized RectF list (0..1). */
    fun getBlurRectsNormalized(): List<RectF> = blurRectsN.map { RectF(it) }

    fun setBlurRectsNormalized(list: List<RectF>) {
        blurRectsN.clear()
        list.forEach { r ->
            val rr = RectF(
                clamp01(min(r.left, r.right)),
                clamp01(min(r.top, r.bottom)),
                clamp01(max(r.left, r.right)),
                clamp01(max(r.top, r.bottom))
            )
            if (!isTiny(rr)) blurRectsN.add(rr)
        }
        invalidate()
    }

    fun clearBlurRects() {
        blurRectsN.clear()
        invalidate()
    }

    // Persist as: "l,t,r,b; l,t,r,b; ..."
    fun exportBlurRectsString(): String =
        blurRectsN.joinToString(";") { r ->
            "${fmt(r.left)},${fmt(r.top)},${fmt(r.right)},${fmt(r.bottom)}"
        }

    fun importBlurRectsString(s: String) {
        blurRectsN.clear()
        val raw = s.trim()
        if (raw.isBlank()) {
            invalidate()
            return
        }
        raw.split(";").forEach { part ->
            val p = part.trim()
            if (p.isBlank()) return@forEach
            val nums = p.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (nums.size == 4) {
                val l = clamp01(min(nums[0], nums[2]))
                val t = clamp01(min(nums[1], nums[3]))
                val r = clamp01(max(nums[0], nums[2]))
                val b = clamp01(max(nums[1], nums[3]))
                val rr = RectF(l, t, r, b)
                if (!isTiny(rr)) blurRectsN.add(rr)
            }
        }
        invalidate()
    }

    // ---------- Drawing ----------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) blur preview rectangles (existing)
        drawBlurRects(canvas, blurRectsN)

        // 2) active drawing rect (if any)
        activeRectN?.let { r ->
            drawBlurRects(canvas, listOf(r))
        }

        // 3) watermark on top
        watermarkBitmap?.let { bm ->
            val dst = watermarkDestRectPx(bm)
            canvas.drawBitmap(bm, null, dst, wmPaint)

            // outline watermark bounds (helps user see it)
            canvas.drawRect(dst, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.argb(180, 0, 170, 255)
            })
        }
    }

    private fun drawBlurRects(canvas: Canvas, rectsN: List<RectF>) {
        if (width <= 0 || height <= 0) return
        for (rn in rectsN) {
            val px = normToPx(rn)
            // fill then stroke
            canvas.drawRect(px, blurPreviewFillPaint)
            canvas.drawRect(px, blurPreviewStrokePaint)
        }
    }

    // ---------- Touch handling ----------
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false

        val xN = clamp01(ev.x / width.toFloat())
        val yN = clamp01(ev.y / height.toFloat())

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downXN = xN
                downYN = yN

                // Decide: drag watermark if touch inside it; otherwise draw blur rect
                draggingWm = watermarkBitmap?.let { bm ->
                    watermarkDestRectPx(bm).contains(ev.x, ev.y)
                } ?: false

                drawingBlur = !draggingWm
                if (drawingBlur) {
                    activeRectN = RectF(xN, yN, xN, yN)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingWm) {
                    // move watermark center with finger
                    wmCxN = xN
                    wmCyN = yN
                    invalidate()
                    return true
                }

                if (drawingBlur) {
                    activeRectN?.let { r ->
                        r.right = xN
                        r.bottom = yN
                        invalidate()
                    }
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)

                if (drawingBlur) {
                    val r = activeRectN
                    activeRectN = null
                    drawingBlur = false
                    if (r != null) {
                        val fixed = RectF(
                            clamp01(min(r.left, r.right)),
                            clamp01(min(r.top, r.bottom)),
                            clamp01(max(r.left, r.right)),
                            clamp01(max(r.top, r.bottom))
                        )
                        if (!isTiny(fixed)) blurRectsN.add(fixed)
                    }
                    invalidate()
                    return true
                }

                draggingWm = false
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(ev)
    }

    // ---------- Helpers ----------
    private fun watermarkDestRectPx(bm: Bitmap): RectF {
        val minDim = min(width.toFloat(), height.toFloat())
        val targetW = max(40f, minDim * wmScale)
        val aspect = bm.height.toFloat() / max(1f, bm.width.toFloat())
        val targetH = targetW * aspect

        val cx = wmCxN * width.toFloat()
        val cy = wmCyN * height.toFloat()

        val left = cx - targetW / 2f
        val top = cy - targetH / 2f
        val right = cx + targetW / 2f
        val bottom = cy + targetH / 2f
        return RectF(left, top, right, bottom)
    }

    private fun normToPx(rn: RectF): RectF {
        val l = rn.left * width.toFloat()
        val t = rn.top * height.toFloat()
        val r = rn.right * width.toFloat()
        val b = rn.bottom * height.toFloat()
        return RectF(l, t, r, b)
    }

    private fun isTiny(r: RectF): Boolean {
        val w = abs(r.right - r.left)
        val h = abs(r.bottom - r.top)
        return w < 0.01f || h < 0.01f
    }

    private fun clamp01(v: Float): Float = when {
        v < 0f -> 0f
        v > 1f -> 1f
        else -> v
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = when {
        v < lo -> lo
        v > hi -> hi
        else -> v
    }

    private fun fmt(v: Float): String = "%.4f".format(v)
}

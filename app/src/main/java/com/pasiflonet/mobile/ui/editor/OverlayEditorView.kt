package com.pasiflonet.mobile.ui.editor

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stable overlay editor:
 * - Drag watermark (wmX/wmY normalized 0..1)
 * - Draw blur rectangles (normalized 0..1) with visible preview (fill+stroke)
 */
class OverlayEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun norm(): NRect {
            val ll = min(l, r).coerceIn(0f, 1f)
            val rr = max(l, r).coerceIn(0f, 1f)
            val tt = min(t, b).coerceIn(0f, 1f)
            val bb = max(t, b).coerceIn(0f, 1f)
            return NRect(ll, tt, rr, bb)
        }
    }

    data class OverlayState(
        val wmX: Float,
        val wmY: Float,
        val blurRects: List<NRect>
    )

    // normalized
    private var wmX: Float = 0.75f
    private var wmY: Float = 0.85f

    private var watermarkBitmap: Bitmap? = null

    // preview dst rect inside view (we default to full view)
    private val dst = RectF()

    // blur rects normalized
    private val blurRects: MutableList<NRect> = mutableListOf()

    // interaction
    private enum class Mode { NONE, DRAG_WM, DRAW_BLUR }
    private var mode: Mode = Mode.NONE

    private var downX = 0f
    private var downY = 0f

    private var wmHitOffsetX = 0f
    private var wmHitOffsetY = 0f

    private var curRect: RectF? = null

    // paints
    private val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val blurFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(55, 255, 0, 0) // visible transparent red
    }
    private val blurStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(220, 255, 0, 0)
    }

    private val hudStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(180, 0, 200, 255) // cyan for watermark frame
    }

    fun setWatermarkBitmap(bmp: Bitmap?) {
        watermarkBitmap = bmp
        invalidate()
    }

    fun setWatermarkUri(uri: Uri?) {
        // optional convenience (safe)
        runCatching {
            if (uri == null) {
                watermarkBitmap = null
            } else {
                val ins = context.contentResolver.openInputStream(uri)
                watermarkBitmap = ins?.use { BitmapFactory.decodeStream(it) }
            }
        }
        invalidate()
    }

    fun clearBlurRects() {
        blurRects.clear()
        curRect = null
        invalidate()
    }

    fun setBlurRectsNorm(list: List<NRect>) {
        blurRects.clear()
        blurRects.addAll(list.map { it.norm() })
        invalidate()
    }

    fun getBlurRectsNorm(): List<NRect> = blurRects.toList()

    fun setOverlayState(st: OverlayState) {
        wmX = st.wmX.coerceIn(0f, 1f)
        wmY = st.wmY.coerceIn(0f, 1f)
        setBlurRectsNorm(st.blurRects)
        invalidate()
    }

    fun getOverlayState(): OverlayState = OverlayState(
        wmX = wmX.coerceIn(0f, 1f),
        wmY = wmY.coerceIn(0f, 1f),
        blurRects = blurRects.toList()
    )

    /** export watermark pos normalized */
    fun exportWatermarkPosNorm(): Pair<Float, Float> = Pair(wmX.coerceIn(0f, 1f), wmY.coerceIn(0f, 1f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // dst is full view
        dst.set(0f, 0f, width.toFloat(), height.toFloat())

        // 1) draw blur previews (existing)
        for (nr in blurRects) {
            val r = nr.norm()
            val rc = RectF(
                dst.left + r.l * dst.width(),
                dst.top + r.t * dst.height(),
                dst.left + r.r * dst.width(),
                dst.top + r.b * dst.height()
            )
            canvas.drawRect(rc, blurFill)
            canvas.drawRect(rc, blurStroke)
        }

        // 2) draw current rect while drawing
        curRect?.let { rc ->
            val n = rectPxToNorm(rc).norm()
            val px = RectF(
                dst.left + n.l * dst.width(),
                dst.top + n.t * dst.height(),
                dst.left + n.r * dst.width(),
                dst.top + n.b * dst.height()
            )
            canvas.drawRect(px, blurFill)
            canvas.drawRect(px, blurStroke)
        }

        // 3) draw watermark
        val wm = watermarkBitmap ?: return
        // watermark size relative to view
        val targetW = dst.width() * 0.18f
        val scale = targetW / max(1f, wm.width.toFloat())
        val targetH = wm.height.toFloat() * scale

        val x = dst.left + wmX * (dst.width() - targetW)
        val y = dst.top + wmY * (dst.height() - targetH)

        val rc = RectF(x, y, x + targetW, y + targetH)
        canvas.drawBitmap(wm, null, rc, wmPaint)
        canvas.drawRect(rc, hudStroke)
    





        // AUTO_BLUR_PREVIEW_REFLECTION
        runCatching {
            val stroke = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4f
                color = android.graphics.Color.argb(230, 255, 0, 0)
            }
            val fill = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.FILL
                color = android.graphics.Color.argb(60, 255, 0, 0)
            }

            val fieldNames = listOf("blurRects", "mBlurRects", "rects", "blurRectList")
            for (fn in fieldNames) {
                val f = runCatching {
                    this::class.java.getDeclaredField(fn).apply { isAccessible = true }
                }.getOrNull() ?: continue

                val v = runCatching { f.get(this) }.getOrNull()
                val list = v as? java.util.List<*> ?: continue

                for (it in list) {
                    if (it == null) continue

                    when (it) {
                        is android.graphics.RectF -> {
                            canvas.drawRect(it, fill)
                            canvas.drawRect(it, stroke)
                        }
                        is android.graphics.Rect -> {
                            val rf = android.graphics.RectF(it)
                            canvas.drawRect(rf, fill)
                            canvas.drawRect(rf, stroke)
                        }
                        else -> {
                            // NRect-like normalized l/t/r/b in 0..1
                            val c = it.javaClass
                            fun gf(n: String): Float? = runCatching {
                                val ff = c.getDeclaredField(n).apply { isAccessible = true }
                                (ff.get(it) as? Number)?.toFloat()
                            }.getOrNull()

                            val l = gf("l"); val t = gf("t"); val r = gf("r"); val b = gf("b")
                            if (l != null && t != null && r != null && b != null) {
                                val w = width.toFloat().coerceAtLeast(1f)
                                val h = height.toFloat().coerceAtLeast(1f)
                                val rc = android.graphics.RectF(l * w, t * h, r * w, b * h)
                                canvas.drawRect(rc, fill)
                                canvas.drawRect(rc, stroke)
                            }
                        }
                    }
                }
            }
        }




        // PAS_DRAW_BLUR_RECT_PREVIEW_V2
        runCatching {
            val stroke = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 5f
                color = android.graphics.Color.argb(235, 255, 0, 0)
                isAntiAlias = true
            }
            val fill = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.FILL
                color = android.graphics.Color.argb(70, 255, 0, 0)
                isAntiAlias = true
            }

            fun getFloat(o: Any, names: List<String>): Float? {
                for (n in names) {
                    runCatching {
                        val f = o.javaClass.getDeclaredField(n)
                        f.isAccessible = true
                        val v = f.get(o)
                        return when (v) {
                            is Float -> v
                            is Double -> v.toFloat()
                            is Int -> v.toFloat()
                            is Long -> v.toFloat()
                            else -> null
                        }
                    }
                }
                return null
            }

            val candidates = mutableListOf<Any>()
            for (f in this.javaClass.declaredFields) {
                if (!f.name.contains("blur", ignoreCase = true)) continue
                f.isAccessible = true
                val v = runCatching { f.get(this) }.getOrNull()
                if (v is List<*>) candidates.addAll(v.filterNotNull())
            }

            val vw = (width.takeIf { it > 0 } ?: 1).toFloat()
            val vh = (height.takeIf { it > 0 } ?: 1).toFloat()

            for (o in candidates) {
                val l = getFloat(o, listOf("l","left","x1")) ?: continue
                val t = getFloat(o, listOf("t","top","y1")) ?: continue
                val r = getFloat(o, listOf("r","right","x2")) ?: continue
                val b = getFloat(o, listOf("b","bottom","y2")) ?: continue

                val normalized = (l >= 0f && t >= 0f && r >= 0f && b >= 0f &&
                                  l <= 1.5f && t <= 1.5f && r <= 1.5f && b <= 1.5f)

                val nl = if (normalized) l * vw else l
                val nt = if (normalized) t * vh else t
                val nr = if (normalized) r * vw else r
                val nb = if (normalized) b * vh else b

                val rc = android.graphics.RectF(nl, nt, nr, nb)
                canvas.drawRect(rc, fill)
                canvas.drawRect(rc, stroke)
            }
        }


}

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y

                // if hit watermark => drag; else start drawing blur rect
                val hit = hitTestWatermark(x, y)
                if (hit != null) {
                    mode = Mode.DRAG_WM
                    wmHitOffsetX = x - hit.left
                    wmHitOffsetY = y - hit.top
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                mode = Mode.DRAW_BLUR
                curRect = RectF(x, y, x, y)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.DRAG_WM) {
                    val wm = watermarkBitmap
                    if (wm != null) {
                        val targetW = dst.width() * 0.18f
                        val scale = targetW / max(1f, wm.width.toFloat())
                        val targetH = wm.height.toFloat() * scale

                        val nx = ((x - wmHitOffsetX - dst.left) / max(1f, (dst.width() - targetW))).coerceIn(0f, 1f)
                        val ny = ((y - wmHitOffsetY - dst.top) / max(1f, (dst.height() - targetH))).coerceIn(0f, 1f)
                        wmX = nx
                        wmY = ny
                        invalidate()
                    }
                    return true
                }

                if (mode == Mode.DRAW_BLUR) {
                    curRect?.let {
                        it.right = x
                        it.bottom = y
                        invalidate()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAW_BLUR) {
                    val r = curRect
                    curRect = null
                    mode = Mode.NONE

                    if (r != null) {
                        // ignore tiny drags
                        if (abs(r.right - r.left) > 12f && abs(r.bottom - r.top) > 12f) {
                            val n = rectPxToNorm(r).norm()
                            blurRects.add(n)
                        }
                    }
                    invalidate()
                    return true
                }

                mode = Mode.NONE
                curRect = null
                invalidate()
                return true
            }
            else -> {}
        }


        return super.onTouchEvent(e)
    }

    private fun hitTestWatermark(x: Float, y: Float): RectF? {
        val wm = watermarkBitmap ?: return null
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        val targetW = dst.width() * 0.18f
        val scale = targetW / max(1f, wm.width.toFloat())
        val targetH = wm.height.toFloat() * scale

        val px = dst.left + wmX * (dst.width() - targetW)
        val py = dst.top + wmY * (dst.height() - targetH)
        val rc = RectF(px, py, px + targetW, py + targetH)

        return if (rc.contains(x, y)) rc else null
    }

    private fun rectPxToNorm(px: RectF): NRect {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        val l = ((min(px.left, px.right) - dst.left) / max(1f, dst.width())).coerceIn(0f, 1f)
        val r = ((max(px.left, px.right) - dst.left) / max(1f, dst.width())).coerceIn(0f, 1f)
        val t = ((min(px.top, px.bottom) - dst.top) / max(1f, dst.height())).coerceIn(0f, 1f)
        val b = ((max(px.top, px.bottom) - dst.top) / max(1f, dst.height())).coerceIn(0f, 1f)
        return NRect(l, t, r, b)
    }
}

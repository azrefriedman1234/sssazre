package com.pasiflonet.mobile.ui

import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.worker.SendWorker
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DetailsActivity : AppCompatActivity() {

    companion object {
        // Intents coming from table row -> Details
        const val EXTRA_SRC_CHAT_ID = "src_chat_id"
        const val EXTRA_SRC_MESSAGE_ID = "src_message_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_MEDIA_URI = "media_uri"     // optional: content://...
        const val EXTRA_MEDIA_MIME = "media_mime"   // optional
    }

    private lateinit var ivPreview: ImageView
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var tvMeta: TextView
    private lateinit var etCaption: com.google.android.material.textfield.TextInputEditText

    private var srcChatId: Long = 0L
    private var srcMsgId: Long = 0L

    private var mediaUri: Uri? = null
    private var mediaMime: String? = null

    // For images: edited bitmap pipeline
    private var originalBitmap: Bitmap? = null
    private var workingBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        ivPreview = findViewById(R.id.ivPreview)
        blurOverlay = findViewById(R.id.blurOverlay)
        tvMeta = findViewById(R.id.tvMeta)
        etCaption = findViewById(R.id.etCaption)

        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        mediaMime = intent.getStringExtra(EXTRA_MEDIA_MIME)
        mediaUri = intent.getStringExtra(EXTRA_MEDIA_URI)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        etCaption.setText(text)

        tvMeta.text = buildMetaString(srcChatId, srcMsgId, mediaUri, mediaMime)

        loadPreview()

        // Buttons
        findViewById<View>(R.id.btnWatermark).setOnClickListener { applyWatermarkIfPossible() }
        findViewById<View>(R.id.btnBlur).setOnClickListener { toggleBlurMode() }
        findViewById<View>(R.id.btnTranslate).setOnClickListener { translateStub() }
        findViewById<View>(R.id.btnSend).setOnClickListener { enqueueSend() }
    }

    private fun buildMetaString(chatId: Long, msgId: Long, uri: Uri?, mime: String?): String {
        val sb = StringBuilder()
        sb.append("chatId=").append(chatId).append(" | msgId=").append(msgId)
        if (uri != null) {
            sb.append("\nmedia=").append(uri.toString())
            if (!mime.isNullOrBlank()) sb.append(" (").append(mime).append(")")
        } else {
            sb.append("\nmedia=none")
        }
        sb.append("\nיעד: ").append(AppPrefs.getTargetUsername(this).ifBlank { "(לא הוגדר)" })
        return sb.toString()
    }

    private fun loadPreview() {
        // If media uri exists - try load image thumbnail.
        // If not exists - show placeholder.
        val uri = mediaUri
        if (uri == null) {
            ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
            blurOverlay.setEnabledForImage(false)
            return
        }

        // Try load as image bitmap (best-effort).
        val bmp = readBitmap(uri)
        if (bmp != null) {
            originalBitmap = bmp
            workingBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
            ivPreview.setImageBitmap(workingBitmap)
            blurOverlay.setEnabledForImage(true)
        } else {
            // Probably video or unknown. Show generic icon.
            ivPreview.setImageResource(android.R.drawable.ic_media_play)
            blurOverlay.setEnabledForImage(false)
        }
    }

    private fun readBitmap(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyWatermarkIfPossible() {
        val bmp = workingBitmap ?: run {
            Snackbar.make(ivPreview, "אין תמונה לעריכה (בווידאו זה יגיע בהמשך)", Snackbar.LENGTH_SHORT).show()
            return
        }

        val wmStr = AppPrefs.getWatermark(this).trim()
        if (wmStr.isBlank()) {
            Snackbar.make(ivPreview, "לא הוגדר לוגו/סימן מים בהגדרות", Snackbar.LENGTH_SHORT).show()
            return
        }

        val wmUri = runCatching { Uri.parse(wmStr) }.getOrNull()
        if (wmUri == null) {
            Snackbar.make(ivPreview, "URI של סימן מים לא תקין", Snackbar.LENGTH_SHORT).show()
            return
        }

        val wmBmp = readBitmap(wmUri)
        if (wmBmp == null) {
            Snackbar.make(ivPreview, "לא הצלחתי לקרוא את תמונת הלוגו/סימן מים", Snackbar.LENGTH_SHORT).show()
            return
        }

        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)

        // Scale watermark to ~20% width
        val targetW = (out.width * 0.22f).toInt().coerceAtLeast(48)
        val scale = targetW.toFloat() / wmBmp.width.toFloat()
        val targetH = (wmBmp.height * scale).toInt().coerceAtLeast(48)
        val wmScaled = Bitmap.createScaledBitmap(wmBmp, targetW, targetH, true)

        val pad = (out.width * 0.02f).toInt()
        val x = out.width - wmScaled.width - pad
        val y = out.height - wmScaled.height - pad

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 220 }
        c.drawBitmap(wmScaled, x.toFloat(), y.toFloat(), p)

        workingBitmap = out
        ivPreview.setImageBitmap(out)
        Snackbar.make(ivPreview, "✅ סימן מים הוחל (לתמונה)", Snackbar.LENGTH_SHORT).show()
    }

    private fun toggleBlurMode() {
        if (!blurOverlay.enabledForImage) {
            Snackbar.make(ivPreview, "טשטוש ידני עובד כרגע לתמונות. בווידאו נטפל אחר כך.", Snackbar.LENGTH_SHORT).show()
            return
        }
        blurOverlay.blurMode = !blurOverlay.blurMode
        if (blurOverlay.blurMode) {
            Snackbar.make(ivPreview, "מצב טשטוש: גרור מלבן על התצוגה", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(ivPreview, "מצב טשטוש: כבוי", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun translateStub() {
        // כדי לא לשבור קומפילציה בלי תלות חדשה:
        // כאן זה סטאב. אם תרצה MLKit אמיתי (חינם on-device) אני אתן לך תלות + קוד עובד.
        Snackbar.make(ivPreview, "תרגום אוטומטי on-device: אוסיף בשלב הבא בלי לשבור בנייה", Snackbar.LENGTH_SHORT).show()
    }

    private fun enqueueSend() {
        val target = AppPrefs.getTargetUsername(this).trim()
        if (target.isBlank()) {
            Snackbar.make(ivPreview, "❌ לא הוגדר @username יעד", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (srcChatId == 0L || srcMsgId == 0L) {
            Snackbar.make(ivPreview, "❌ חסרים מזהים של הודעה מקורית", Snackbar.LENGTH_SHORT).show()
            return
        }

        val text = etCaption.text?.toString().orEmpty()

        // כרגע: שולח טקסט בלבד יציב דרך SendWorker
        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, text)
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, false)
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)
        Snackbar.make(ivPreview, "✅ נשלח לתור שליחה (WorkManager). בדוק בערוץ יעד.", Snackbar.LENGTH_LONG).show()
    }

    /**
     * Overlay that allows drawing blur rectangles over the preview.
     * For images only: when a rectangle is finished, we apply blur on bitmap area.
     */
    class BlurOverlayView(context: android.content.Context, attrs: android.util.AttributeSet?) : View(context, attrs) {

        var blurMode: Boolean = false
        var enabledForImage: Boolean = false
            private set

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
            if (!v) {
                blurMode = false
                rects.clear()
                curRect = null
                invalidate()
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
            if (!blurMode || !enabledForImage) return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    curRect = RectF(downX, downY, downX, downY)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    curRect?.let {
                        it.right = event.x
                        it.bottom = event.y
                        normalize(it)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    curRect?.let {
                        normalize(it)
                        if (it.width() > 20 && it.height() > 20) rects.add(RectF(it))
                    }
                    curRect = null
                    invalidate()

                    // Apply blur on activity bitmap (best-effort via parent traversal)
                    // We call back to activity by walking up the context.
                    val act = context as? DetailsActivity
                    act?.applyBlurRects(rects.map { RectF(it) })

                    return true
                }
            }
            return false
        }

        private fun normalize(r: RectF) {
            val l = min(r.left, r.right)
            val t = min(r.top, r.bottom)
            val rr = max(r.left, r.right)
            val bb = max(r.top, r.bottom)
            r.set(l, t, rr, bb)
        }
    }

    private fun applyBlurRects(viewRects: List<RectF>) {
        val bmp = workingBitmap ?: return
        if (viewRects.isEmpty()) return

        // Map overlay rects (view coords) -> bitmap coords (ImageView fitCenter)
        val mapped = mapViewRectsToBitmap(ivPreview, bmp, viewRects)
        if (mapped.isEmpty()) return

        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        for (r in mapped) {
            boxBlurRect(out, r, radius = 12)
        }
        workingBitmap = out
        ivPreview.setImageBitmap(out)
        Snackbar.make(ivPreview, "✅ טשטוש הוחל (לתמונה)", Snackbar.LENGTH_SHORT).show()
    }

    private fun mapViewRectsToBitmap(iv: ImageView, bmp: Bitmap, viewRects: List<RectF>): List<Rect> {
        val d = iv.drawable ?: return emptyList()

        // Compute how drawable is fitted into ImageView (fitCenter)
        val ivW = iv.width.toFloat()
        val ivH = iv.height.toFloat()
        if (ivW <= 0f || ivH <= 0f) return emptyList()

        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return emptyList()

        val scale = min(ivW / dw, ivH / dh)
        val drawnW = dw * scale
        val drawnH = dh * scale
        val left = (ivW - drawnW) / 2f
        val top = (ivH - drawnH) / 2f

        fun toBmpX(x: Float): Int = ((x - left) / scale).toInt()
        fun toBmpY(y: Float): Int = ((y - top) / scale).toInt()

        val rects = mutableListOf<Rect>()
        for (vr in viewRects) {
            val x1 = toBmpX(vr.left)
            val y1 = toBmpY(vr.top)
            val x2 = toBmpX(vr.right)
            val y2 = toBmpY(vr.bottom)

            val l = x1.coerceIn(0, bmp.width - 1)
            val t = y1.coerceIn(0, bmp.height - 1)
            val r = x2.coerceIn(0, bmp.width)
            val b = y2.coerceIn(0, bmp.height)

            if (r > l && b > t) rects.add(Rect(l, t, r, b))
        }
        return rects
    }

    /**
     * Simple box blur on a rectangular area. CPU-only, safe, works on Bitmap ARGB_8888.
     */
    private fun boxBlurRect(bmp: Bitmap, rect: Rect, radius: Int) {
        val r = radius.coerceIn(2, 32)
        val w = rect.width()
        val h = rect.height()
        if (w < 2 || h < 2) return

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, rect.left, rect.top, w, h)

        // horizontal pass
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (x in -r..r) {
                val xx = x.coerceIn(0, w - 1)
                val c = pixels[y * w + xx]
                aSum += (c ushr 24) and 255
                rSum += (c ushr 16) and 255
                gSum += (c ushr 8) and 255
                bSum += (c) and 255
            }
            for (x in 0 until w) {
                val idx = y * w + x
                val div = (2 * r + 1)
                val a = aSum / div
                val rr = rSum / div
                val gg = gSum / div
                val bb = bSum / div
                tmp[idx] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb

                // slide window
                val xOut = (x - r).coerceIn(0, w - 1)
                val xIn = (x + r + 1).coerceIn(0, w - 1)
                val cOut = pixels[y * w + xOut]
                val cIn = pixels[y * w + xIn]
                aSum += ((cIn ushr 24) and 255) - ((cOut ushr 24) and 255)
                rSum += ((cIn ushr 16) and 255) - ((cOut ushr 16) and 255)
                gSum += ((cIn ushr 8) and 255) - ((cOut ushr 8) and 255)
                bSum += (cIn and 255) - (cOut and 255)
            }
        }

        // vertical pass
        val out = IntArray(w * h)
        for (x in 0 until w) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (y in -r..r) {
                val yy = y.coerceIn(0, h - 1)
                val c = tmp[yy * w + x]
                aSum += (c ushr 24) and 255
                rSum += (c ushr 16) and 255
                gSum += (c ushr 8) and 255
                bSum += (c) and 255
            }
            for (y in 0 until h) {
                val idx = y * w + x
                val div = (2 * r + 1)
                val a = aSum / div
                val rr = rSum / div
                val gg = gSum / div
                val bb = bSum / div
                out[idx] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb

                val yOut = (y - r).coerceIn(0, h - 1)
                val yIn = (y + r + 1).coerceIn(0, h - 1)
                val cOut = tmp[yOut * w + x]
                val cIn = tmp[yIn * w + x]
                aSum += ((cIn ushr 24) and 255) - ((cOut ushr 24) and 255)
                rSum += ((cIn ushr 16) and 255) - ((cOut ushr 16) and 255)
                gSum += ((cIn ushr 8) and 255) - ((cOut ushr 8) and 255)
                bSum += (cIn and 255) - (cOut and 255)
            }
        }

        bmp.setPixels(out, 0, w, rect.left, rect.top, w, h)
    }
}

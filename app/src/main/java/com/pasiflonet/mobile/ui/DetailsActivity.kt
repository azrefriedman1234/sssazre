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

        // Blur rectangles -> apply on bitmap (images only)
        blurOverlay.onRectFinalized = { viewRect ->
            applyBlurRect(viewRect)
        }


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

    private fun applyBlurRect(viewRect: android.graphics.RectF) {
        val bmp = workingBitmap ?: return
        if (!blurOverlay.enabledForImage) return

        // Map view rect -> bitmap rect using ImageView matrix
        val dr = ivPreview.drawable ?: return
        val imgRect = android.graphics.RectF(0f, 0f, dr.intrinsicWidth.toFloat(), dr.intrinsicHeight.toFloat())
        val m = android.graphics.Matrix(ivPreview.imageMatrix)
        val drawn = android.graphics.RectF(imgRect)
        m.mapRect(drawn)

        // Intersection (only what's on-screen)
        val inter = android.graphics.RectF()
        val ok = inter.setIntersect(drawn, viewRect)
        if (!ok) return

        // Convert to bitmap coords
        val inv = android.graphics.Matrix()
        if (!m.invert(inv)) return
        val bmpRect = android.graphics.RectF(inter)
        inv.mapRect(bmpRect)

        val l = bmpRect.left.toInt().coerceIn(0, bmp.width - 1)
        val t = bmpRect.top.toInt().coerceIn(0, bmp.height - 1)
        val r = bmpRect.right.toInt().coerceIn(l + 1, bmp.width)
        val b = bmpRect.bottom.toInt().coerceIn(t + 1, bmp.height)

        // Pixelate (fast + stable) instead of heavy blur
        pixelateRegion(bmp, l, t, r, b, block = 16)
        ivPreview.invalidate()
    }

    private fun pixelateRegion(bmp: android.graphics.Bitmap, l: Int, t: Int, r: Int, b: Int, block: Int) {
        val w = r - l
        val h = b - t
        if (w <= 2 || h <= 2) return

        val blockSize = block.coerceAtLeast(6)
        val src = android.graphics.Rect(l, t, r, b)

        // scale down then scale up -> pixelation
        val smallW = (w / blockSize).coerceAtLeast(1)
        val smallH = (h / blockSize).coerceAtLeast(1)

        val region = android.graphics.Bitmap.createBitmap(bmp, l, t, w, h)
        val small = android.graphics.Bitmap.createScaledBitmap(region, smallW, smallH, false)
        val big = android.graphics.Bitmap.createScaledBitmap(small, w, h, false)

        val c = android.graphics.Canvas(bmp)
        c.drawBitmap(big, null, src, null)

        region.recycle()
        small.recycle()
        big.recycle()
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
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, mediaUri != null)
              .putString(SendWorker.KEY_MEDIA_URI, mediaUri?.toString() ?: "")
              .putString(SendWorker.KEY_MEDIA_MIME, mediaMime ?: "")
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)
        Snackbar.make(ivPreview, "✅ נשלח לתור שליחה (WorkManager). בדוק בערוץ יעד.", Snackbar.LENGTH_LONG).show()
    }

    
package com.pasiflonet.mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.worker.SendWorker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SRC_CHAT_ID = "src_chat_id"
        const val EXTRA_SRC_MESSAGE_ID = "src_message_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MEDIA_MIME = "media_mime"
        const val EXTRA_MINITHUMB_B64 = "mini_thumb_b64"

        fun start(
            ctx: Context,
            chatId: Long,
            msgId: Long,
            text: String,
            mediaUri: String? = null,
            mediaMime: String? = null,
            miniThumbB64: String? = null
        ) {
            val i = Intent(ctx, DetailsActivity::class.java)
            i.putExtra(EXTRA_SRC_CHAT_ID, chatId)
            i.putExtra(EXTRA_SRC_MESSAGE_ID, msgId)
            i.putExtra(EXTRA_TEXT, text)
            if (!mediaUri.isNullOrBlank()) i.putExtra(EXTRA_MEDIA_URI, mediaUri)
            if (!mediaMime.isNullOrBlank()) i.putExtra(EXTRA_MEDIA_MIME, mediaMime)
            if (!miniThumbB64.isNullOrBlank()) i.putExtra(EXTRA_MINITHUMB_B64, miniThumbB64)
            ctx.startActivity(i)
        }
    }

    private lateinit var ivPreview: ImageView
    private lateinit var ivWatermarkOverlay: ImageView
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var tvMeta: TextView
    private lateinit var etCaption: com.google.android.material.textfield.TextInputEditText
    private lateinit var swSendWithMedia: SwitchMaterial

    private var srcChatId: Long = 0L
    private var srcMsgId: Long = 0L

    private var mediaUri: Uri? = null
    private var mediaMime: String? = null
    private var miniThumbB64: String? = null

    // for images
    private var workingBitmap: Bitmap? = null

    // watermark drag state
    private var wmDragging = false
    private var wmDx = 0f
    private var wmDy = 0f

    private val langId by lazy { LanguageIdentification.getClient() }
    private var translator: Translator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        ivPreview = findViewById(R.id.ivPreview)
        ivWatermarkOverlay = findViewById(R.id.ivWatermarkOverlay)
        blurOverlay = findViewById(R.id.blurOverlay)
        tvMeta = findViewById(R.id.tvMeta)
        etCaption = findViewById(R.id.etCaption)
        swSendWithMedia = findViewById(R.id.swSendWithMedia)

        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        mediaMime = intent.getStringExtra(EXTRA_MEDIA_MIME)
        miniThumbB64 = intent.getStringExtra(EXTRA_MINITHUMB_B64)
        mediaUri = intent.getStringExtra(EXTRA_MEDIA_URI)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        val hasMediaHint = (mediaUri != null) || (!miniThumbB64.isNullOrBlank())
        swSendWithMedia.isChecked = hasMediaHint
        swSendWithMedia.visibility = if (hasMediaHint) android.view.View.VISIBLE else android.view.View.GONE
        etCaption.setText(text)

        tvMeta.text = buildMetaString()

        setupWatermarkOverlayDrag()
        loadPreview()

        findViewById<View>(R.id.btnWatermark).setOnClickListener { onWatermarkClick() }
        findViewById<View>(R.id.btnBlur).setOnClickListener { toggleBlurMode() }
        findViewById<View>(R.id.btnTranslate).setOnClickListener { translateToHebrew() }
        findViewById<View>(R.id.btnSend).setOnClickListener { enqueueSend() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { langId.close() }
        runCatching { translator?.close() }
    }

    private fun buildMetaString(): String {
        val sb = StringBuilder()
        sb.append("chatId=").append(srcChatId).append(" | msgId=").append(srcMsgId)
        sb.append("\nיעד: ").append(AppPrefs.getTargetUsername(this).ifBlank { "(לא הוגדר)" })

        if (mediaUri != null) {
            sb.append("\nmedia=").append(mediaUri.toString())
            if (!mediaMime.isNullOrBlank()) sb.append(" (").append(mediaMime).append(")")
        } else {
            sb.append("\nmedia=none")
        }
        if (!miniThumbB64.isNullOrBlank()) sb.append("\nthumb=miniThumbB64")
        return sb.toString()
    }

    private fun loadPreview() {
        // 1) If we have mediaUri -> try image first
        mediaUri?.let { uri ->
            val bmp = readBitmap(uri)
            if (bmp != null) {
                workingBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
                ivPreview.setImageBitmap(workingBitmap)
                blurOverlay.setEnabledForImage(true)
                showWatermarkOverlayIfConfigured()
                return
            }

            // Not an image -> try video frame
            val frame = readVideoFrame(uri)
            if (frame != null) {
                ivPreview.setImageBitmap(frame)
                blurOverlay.setEnabledForImage(false) // video blur done in worker
                showWatermarkOverlayIfConfigured()
                return
            }

            ivPreview.setImageResource(android.R.drawable.ic_media_play)
            blurOverlay.setEnabledForImage(false)
            showWatermarkOverlayIfConfigured()
            return
        }

        // 2) No mediaUri -> fallback to miniThumb
        val thumb = decodeMiniThumb(miniThumbB64)
        if (thumb != null) {
            ivPreview.setImageBitmap(thumb)
        } else {
            ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
        }
        blurOverlay.setEnabledForImage(false)
        showWatermarkOverlayIfConfigured()
    }

    private fun decodeMiniThumb(b64: String?): Bitmap? {
        if (b64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
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

    private fun readVideoFrame(uri: Uri): Bitmap? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, uri)
            val b = r.getFrameAtTime(0)
            r.release()
            b
        } catch (_: Throwable) {
            null
        }
    }

    private fun showWatermarkOverlayIfConfigured() {
        val wmStr = AppPrefs.getWatermark(this).trim()
        if (wmStr.isBlank()) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }
        val wmUri = runCatching { Uri.parse(wmStr) }.getOrNull()
        if (wmUri == null) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }
        val wmBmp = readBitmap(wmUri)
        if (wmBmp == null) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }
        ivWatermarkOverlay.setImageBitmap(wmBmp)
        ivWatermarkOverlay.visibility = View.VISIBLE

        // default position bottom-right
        ivWatermarkOverlay.post {
            val pad = (ivWatermarkOverlay.rootView.width * 0.02f).toInt().coerceAtLeast(12)
            ivWatermarkOverlay.x = (ivPreview.width - ivWatermarkOverlay.width - pad).toFloat().coerceAtLeast(0f)
            ivWatermarkOverlay.y = (ivPreview.height - ivWatermarkOverlay.height - pad).toFloat().coerceAtLeast(0f)
        }
    }

    private fun setupWatermarkOverlayDrag() {
        ivWatermarkOverlay.setOnTouchListener { v, ev ->
            if (ivWatermarkOverlay.visibility != View.VISIBLE) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wmDragging = true
                    wmDx = v.x - ev.rawX
                    wmDy = v.y - ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!wmDragging) return@setOnTouchListener false
                    v.x = ev.rawX + wmDx
                    v.y = ev.rawY + wmDy
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    wmDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun onWatermarkClick() {
        val bmp = workingBitmap
        if (bmp == null) {
            Snackbar.make(ivPreview, "סימן מים לתמונות עובד עכשיו. לווידאו זה יוחל בשליחה (FFmpeg).", Snackbar.LENGTH_LONG).show()
            showWatermarkOverlayIfConfigured()
            return
        }

        val wmDrawable = ivWatermarkOverlay.drawable
        if (wmDrawable == null) {
            Snackbar.make(ivPreview, "לא הוגדר לוגו/סימן מים בהגדרות", Snackbar.LENGTH_SHORT).show()
            return
        }

        val wmBmp = drawableToBitmap(wmDrawable) ?: run {
            Snackbar.make(ivPreview, "לא הצלחתי לקרוא את תמונת הלוגו", Snackbar.LENGTH_SHORT).show()
            return
        }

        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)

        // map overlay view position -> bitmap coordinates (approx using scale ratios)
        val pos = overlayToBitmapXY(out, wmBmp)
        val x = pos.first
        val y = pos.second

        val targetW = (out.width * 0.22f).toInt().coerceAtLeast(48)
        val scale = targetW.toFloat() / wmBmp.width.toFloat()
        val targetH = (wmBmp.height * scale).toInt().coerceAtLeast(48)
        val wmScaled = Bitmap.createScaledBitmap(wmBmp, targetW, targetH, true)

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 220 }
        c.drawBitmap(wmScaled, x.toFloat(), y.toFloat(), p)

        workingBitmap = out
        ivPreview.setImageBitmap(out)
        Snackbar.make(ivPreview, "✅ סימן מים הוחל (לתמונה)", Snackbar.LENGTH_SHORT).show()
    }

    private fun overlayToBitmapXY(base: Bitmap, wmBmp: Bitmap): Pair<Int, Int> {
        // This is a pragmatic mapping: use view ratios within ivPreview bounds.
        val vx = ivWatermarkOverlay.x.coerceAtLeast(0f)
        val vy = ivWatermarkOverlay.y.coerceAtLeast(0f)
        val vw = ivPreview.width.coerceAtLeast(1)
        val vh = ivPreview.height.coerceAtLeast(1)

        val rx = (vx / vw.toFloat()).coerceIn(0f, 1f)
        val ry = (vy / vh.toFloat()).coerceIn(0f, 1f)

        val bx = (rx * base.width).toInt()
        val by = (ry * base.height).toInt()
        return Pair(bx, by)
    }

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable): Bitmap? {
        return try {
            val b = Bitmap.createBitmap(
                max(1, d.intrinsicWidth),
                max(1, d.intrinsicHeight),
                Bitmap.Config.ARGB_8888
            )
            val c = Canvas(b)
            d.setBounds(0, 0, c.width, c.height)
            d.draw(c)
            b
        } catch (_: Throwable) { null }
    }

    private fun toggleBlurMode() {
        if (workingBitmap == null) {
            Snackbar.make(ivPreview, "טשטוש ידני לתמונה עובד עכשיו. לווידאו — סמן מלבנים ואז בשליחה זה ייטשטש.", Snackbar.LENGTH_LONG).show()
            // allow marking even if video (for worker)
            blurOverlay.allowRectangles = true
            blurOverlay.blurMode = !blurOverlay.blurMode
            return
        }

        blurOverlay.setEnabledForImage(true)
        blurOverlay.blurMode = !blurOverlay.blurMode
        Snackbar.make(ivPreview, if (blurOverlay.blurMode) "מצב טשטוש: גרור מלבן" else "מצב טשטוש: כבוי", Snackbar.LENGTH_SHORT).show()
    }

    private fun translateToHebrew() {
        val src = etCaption.text?.toString().orEmpty().trim()
        if (src.isBlank()) {
            Snackbar.make(ivPreview, "אין טקסט לתרגום", Snackbar.LENGTH_SHORT).show()
            return
        }

        // detect language first
        Snackbar.make(ivPreview, "🔎 מזהה שפה...", Snackbar.LENGTH_SHORT).show()
        langId.identifyLanguage(src)
            .addOnSuccessListener { langCode ->
                val srcLang = TranslateLanguage.fromLanguageTag(langCode) ?: TranslateLanguage.ENGLISH
                val tgtLang = TranslateLanguage.HEBREW

                val opts = TranslatorOptions.Builder()
                    .setSourceLanguage(srcLang)
                    .setTargetLanguage(tgtLang)
                    .build()

                translator?.close()
                translator = Translation.getClient(opts)
                val tr = translator!!

                Snackbar.make(ivPreview, "⬇️ מוריד מודל תרגום (פעם ראשונה) ומתרגם...", Snackbar.LENGTH_LONG).show()
                tr.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        tr.translate(src)
                            .addOnSuccessListener { out ->
                                etCaption.setText(out)
                                Snackbar.make(ivPreview, "✅ תורגם לעברית", Snackbar.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { e ->
                                Snackbar.make(ivPreview, "❌ תרגום נכשל: ${e.message}", Snackbar.LENGTH_LONG).show()
                            }
                    }
                    .addOnFailureListener { e ->
                        Snackbar.make(ivPreview, "❌ הורדת מודל נכשלה: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener { e ->
                Snackbar.make(ivPreview, "❌ זיהוי שפה נכשל: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
    }

    private fun enqueueSend() {
        val target = AppPrefs.getTargetUsername(this).trim()
        if (target.isBlank()) {
            Snackbar.make(ivPreview, "❌ לא הוגדר @username יעד", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (srcChatId == 0L || srcMsgId == 0L) {
            Snackbar.make(ivPreview, "❌ חסרים מזהי מקור", Snackbar.LENGTH_SHORT).show()
            return
        }

        val text = etCaption.text?.toString().orEmpty()
        val wm = AppPrefs.getWatermark(this).trim()

        val rects = blurOverlay.exportRectsNormalized()
        val rectsStr = rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, text)
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, swSendWithMedia.isChecked)
            .putString(SendWorker.KEY_MEDIA_URI, mediaUri?.toString().orEmpty())
            .putString(SendWorker.KEY_MEDIA_MIME, mediaMime.orEmpty())
            .putString(SendWorker.KEY_WATERMARK_URI, wm)
            .putString(SendWorker.KEY_BLUR_RECTS, rectsStr)
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)
        Snackbar.make(ivPreview, "✅ נכנס לתור שליחה. בדוק בערוץ יעד.", Snackbar.LENGTH_LONG).show()
    }

    /**
     * Overlay rectangles:
     * - for images: can be applied later (אם תרצה ניישם גם על תמונה בפועל)
     * - for video: passed to worker as normalized rects for ffmpeg blur
     */
}

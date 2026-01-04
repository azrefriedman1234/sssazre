package com.pasiflonet.mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.worker.SendWorker
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

class DetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SRC_CHAT_ID = "src_chat_id"
        const val EXTRA_SRC_MESSAGE_ID = "src_message_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MEDIA_MIME = "media_mime"
        const val EXTRA_MINITHUMB_B64 = "mini_thumb_b64"
        const val EXTRA_HAS_MEDIA_HINT = "has_media_hint"

        fun start(
            ctx: Context,
            chatId: Long,
            msgId: Long,
            text: String,
            mediaUri: String? = null,
            mediaMime: String? = null,
            miniThumbB64: String? = null,
            hasMediaHint: Boolean = false
        ) {
            val i = Intent(ctx, DetailsActivity::class.java)
            i.putExtra(EXTRA_SRC_CHAT_ID, chatId)
            i.putExtra(EXTRA_SRC_MESSAGE_ID, msgId)
            i.putExtra(EXTRA_TEXT, text)
            if (!mediaUri.isNullOrBlank()) i.putExtra(EXTRA_MEDIA_URI, mediaUri)
            if (!mediaMime.isNullOrBlank()) i.putExtra(EXTRA_MEDIA_MIME, mediaMime)
            if (!miniThumbB64.isNullOrBlank()) i.putExtra(EXTRA_MINITHUMB_B64, miniThumbB64)
            i.putExtra(EXTRA_HAS_MEDIA_HINT, hasMediaHint)
            ctx.startActivity(i)
        }
    }

    private lateinit var ivPreview: ImageView
    private lateinit var ivWatermarkOverlay: ImageView
    private lateinit var blurOverlayView: View
    private lateinit var tvMeta: TextView
    private lateinit var etCaption: com.google.android.material.textfield.TextInputEditText
    private lateinit var swSendWithMedia: SwitchMaterial

    private var srcChatId: Long = 0L
    private var srcMsgId: Long = 0L

    private var mediaUri: Uri? = null
    private var mediaMime: String? = null
    private var miniThumbB64: String? = null
    private var hasMediaHint: Boolean = false

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
        blurOverlayView = findViewById(R.id.blurOverlay)
        tvMeta = findViewById(R.id.tvMeta)
        etCaption = findViewById(R.id.etCaption)
        swSendWithMedia = findViewById(R.id.swSendWithMedia)

        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        mediaMime = intent.getStringExtra(EXTRA_MEDIA_MIME)
        miniThumbB64 = intent.getStringExtra(EXTRA_MINITHUMB_B64)
        hasMediaHint = intent.getBooleanExtra(EXTRA_HAS_MEDIA_HINT, false)
        mediaUri = intent.getStringExtra(EXTRA_MEDIA_URI)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        etCaption.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())

        // ALWAYS show toggle; default ON to avoid "text only" by mistake
        swSendWithMedia.visibility = View.VISIBLE
        swSendWithMedia.isChecked = true
        swSendWithMedia.text = "שלח עם מדיה (יביא מהטלגרם אם צריך)"

        setupWatermarkOverlayDrag()
        loadPreviewFromIntent()

        // Fetch real message to: (1) decide if it has media (2) fetch mini-thumb if missing
        Thread {
            val msg = fetchMessageSync(srcChatId, srcMsgId)
            if (msg != null) {
                val (hasMedia, thumbB64) = extractMiniThumbB64FromMessage(msg)
                if (hasMediaHint == false) hasMediaHint = hasMedia
                if (miniThumbB64.isNullOrBlank() && !thumbB64.isNullOrBlank()) miniThumbB64 = thumbB64

                runOnUiThread {
                    swSendWithMedia.isChecked = hasMediaHint
                    // If no local mediaUri -> show thumb
                    if (mediaUri == null) {
                        decodeMiniThumbCompat(miniThumbB64)?.let { ivPreview.setImageBitmap(it) }
                    }
                    tvMeta.text = buildMetaString()
                }
            } else {
                runOnUiThread { tvMeta.text = buildMetaString() }
            }
        }.start()

        tvMeta.text = buildMetaString()

        findViewById<View>(R.id.btnWatermark).setOnClickListener {
            // overlay is already draggable on preview; just ensure visible if watermark exists in prefs
            showWatermarkOverlayIfConfigured()
            Snackbar.make(ivPreview, "✅ גרור את הלוגו על התצוגה. השליחה תיישם ב־FFmpeg.", Snackbar.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.btnBlur).setOnClickListener {
            toggleBlurMode()
        }

        findViewById<View>(R.id.btnTranslate).setOnClickListener {
            translateToHebrew()
        }

        findViewById<View>(R.id.btnSend).setOnClickListener {
            enqueueSend()
        }
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
        sb.append("\nmediaUri=").append(mediaUri?.toString() ?: "none (will fetch from Telegram)")
        if (!mediaMime.isNullOrBlank()) sb.append("\nmime=").append(mediaMime)
        sb.append("\nwithMedia=").append(swSendWithMedia.isChecked)
        sb.append("\nhasMediaHint=").append(hasMediaHint)
        sb.append("\nthumb=").append(if (miniThumbB64.isNullOrBlank()) "none" else "miniThumbB64")
        return sb.toString()
    }

    private fun loadPreviewFromIntent() {
        // 1) If local mediaUri exists -> image first, else video frame
        mediaUri?.let { uri ->
            readBitmap(uri)?.let { bmp ->
                ivPreview.setImageBitmap(bmp)
                setEnabledForImage(true)
                showWatermarkOverlayIfConfigured()
                return
            }
            readVideoFrame(uri)?.let { frame ->
                ivPreview.setImageBitmap(frame)
                setEnabledForImage(false)
                showWatermarkOverlayIfConfigured()
                return
            }
        }

        // 2) Fallback mini-thumb
        decodeMiniThumbCompat(miniThumbB64)?.let {
            ivPreview.setImageBitmap(it)
        } ?: run {
            ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
        }

        setEnabledForImage(false)
        showWatermarkOverlayIfConfigured()
    }

    private fun readBitmap(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Throwable) { null }
    }

    private fun readVideoFrame(uri: Uri): Bitmap? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, uri)
            val b = r.getFrameAtTime(0)
            r.release()
            b
        } catch (_: Throwable) { null }
    }

    private fun decodeMiniThumbCompat(b64: String?): Bitmap? {
        if (b64.isNullOrBlank()) return null
        return try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: run {
                // headerless JPEG -> prepend JFIF header
                val jfif = byteArrayOf(
                    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
                    0x00, 0x01, 0x00, 0x01, 0x00, 0x00
                )
                val full = jfif + raw
                BitmapFactory.decodeByteArray(full, 0, full.size)
            }
        } catch (_: Throwable) { null }
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

    private fun exportWatermarkPosNorm(): Pair<Float, Float> {
        if (ivWatermarkOverlay.visibility != View.VISIBLE) return Pair(-1f, -1f)
        val vw = ivPreview.width.coerceAtLeast(1).toFloat()
        val vh = ivPreview.height.coerceAtLeast(1).toFloat()
        val rx = (ivWatermarkOverlay.x / vw).coerceIn(0f, 1f)
        val ry = (ivWatermarkOverlay.y / vh).coerceIn(0f, 1f)
        return Pair(rx, ry)
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

        val bmp = readBitmap(wmUri)
        if (bmp == null) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }

        ivWatermarkOverlay.setImageBitmap(bmp)
        ivWatermarkOverlay.visibility = View.VISIBLE

        // default bottom-right
        ivWatermarkOverlay.post {
            val pad = (ivPreview.width * 0.02f).toInt().coerceAtLeast(12)
            ivWatermarkOverlay.x = (ivPreview.width - ivWatermarkOverlay.width - pad).toFloat().coerceAtLeast(0f)
            ivWatermarkOverlay.y = (ivPreview.height - ivWatermarkOverlay.height - pad).toFloat().coerceAtLeast(0f)
        }
    }

    // ---- Blur overlay reflection (so compilation won't break if BlurOverlayView changes) ----
    private fun toggleBlurMode() {
        val cur = getBooleanProp(blurOverlayView, "blurMode")
        val next = !cur
        setBooleanProp(blurOverlayView, "blurMode", next)
        setBooleanProp(blurOverlayView, "allowRectangles", true)
        Snackbar.make(ivPreview, if (next) "מצב טשטוש: גרור מלבן" else "מצב טשטוש: כבוי", Snackbar.LENGTH_SHORT).show()
    }

    private fun setEnabledForImage(enabled: Boolean) {
        // try method setEnabledForImage(Boolean)
        try {
            val m = blurOverlayView.javaClass.methods.firstOrNull { it.name == "setEnabledForImage" && it.parameterCount == 1 }
            m?.invoke(blurOverlayView, enabled)
        } catch (_: Throwable) { }
    }

    private fun exportBlurRectsStr(): String {
        return try {
            val m = blurOverlayView.javaClass.methods.firstOrNull { it.name == "exportRectsNormalized" && it.parameterCount == 0 }
                ?: return ""
            val v = m.invoke(blurOverlayView) as? List<*> ?: return ""
            val parts = ArrayList<String>()
            for (item in v) {
                if (item == null) continue
                // RectF
                if (item is android.graphics.RectF) {
                    if (item.right > item.left && item.bottom > item.top) {
                        parts += "${item.left},${item.top},${item.right},${item.bottom}"
                    }
                    continue
                }
                // data class with left/top/right/bottom
                fun f(name: String): Float? = try {
                    val fld = item.javaClass.getField(name)
                    fld.isAccessible = true
                    (fld.get(item) as? Number)?.toFloat()
                } catch (_: Throwable) { null }

                val l = f("left")
                val t = f("top")
                val r = f("right")
                val b = f("bottom")
                if (l != null && t != null && r != null && b != null && r > l && b > t) {
                    parts += "$l,$t,$r,$b"
                }
            }
            parts.joinToString(";")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun getBooleanProp(obj: Any, field: String): Boolean {
        return try {
            val f = obj.javaClass.getField(field)
            f.isAccessible = true
            (f.get(obj) as? Boolean) ?: false
        } catch (_: Throwable) { false }
    }

    private fun setBooleanProp(obj: Any, field: String, value: Boolean) {
        try {
            val f = obj.javaClass.getField(field)
            f.isAccessible = true
            f.set(obj, value)
        } catch (_: Throwable) { }
    }
    // --------------------------------------------------------------------

    private fun translateToHebrew() {
        val src = etCaption.text?.toString().orEmpty().trim()
        if (src.isBlank()) {
            Snackbar.make(ivPreview, "אין טקסט לתרגום", Snackbar.LENGTH_SHORT).show()
            return
        }

        val hasHeb = src.any { it in '\u0590'..'\u05FF' }
        if (hasHeb) {
            Snackbar.make(ivPreview, "הטקסט כבר בעברית", Snackbar.LENGTH_SHORT).show()
            return
        }

        Snackbar.make(ivPreview, "🔎 מזהה שפה...", Snackbar.LENGTH_SHORT).show()
        langId.identifyLanguage(src)
            .addOnSuccessListener { langCode ->
                val srcTag = if (langCode == "und") "en" else langCode
                val srcLang = TranslateLanguage.fromLanguageTag(srcTag) ?: TranslateLanguage.ENGLISH
                val tgtLang = TranslateLanguage.HEBREW

                val opts = TranslatorOptions.Builder()
                    .setSourceLanguage(srcLang)
                    .setTargetLanguage(tgtLang)
                    .build()

                translator?.close()
                translator = Translation.getClient(opts)
                val tr = translator!!

                Snackbar.make(ivPreview, "⬇️ מוריד מודל תרגום ומתרגם...", Snackbar.LENGTH_LONG).show()
                val cond = DownloadConditions.Builder().build()

                tr.downloadModelIfNeeded(cond)
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
                        Snackbar.make(ivPreview, "❌ הורדת מודל נכשלה (בדוק Google Play/אינטרנט): ${e.message}", Snackbar.LENGTH_LONG).show()
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
        val (wmX, wmY) = exportWatermarkPosNorm()
        val rectsStr = exportBlurRectsStr()

        // THIS is the critical fix: always take from the switch
        val sendWithMedia = swSendWithMedia.isChecked

        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, text)
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, sendWithMedia)
            .putString(SendWorker.KEY_MEDIA_URI, mediaUri?.toString().orEmpty()) // can be empty; worker will fetch from Telegram
            .putString(SendWorker.KEY_MEDIA_MIME, mediaMime.orEmpty())
            .putString(SendWorker.KEY_WATERMARK_URI, wm)
            .putString(SendWorker.KEY_BLUR_RECTS, rectsStr)
            .putFloat(SendWorker.KEY_WM_X, wmX)
            .putFloat(SendWorker.KEY_WM_Y, wmY)
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)
        Snackbar.make(ivPreview, "✅ נכנס לתור שליחה. (עם מדיה=${sendWithMedia})", Snackbar.LENGTH_LONG).show()
    }

    // ---- TDLib fetch (sync) for thumb/media detection ----
    private fun fetchMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        return try {
            TdLibManager.init(applicationContext)
            TdLibManager.ensureClient()
            val latch = CountDownLatch(1)
            var out: TdApi.Message? = null
            TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
                if (obj is TdApi.Message) out = obj
                latch.countDown()
            }
            latch.await(8, TimeUnit.SECONDS)
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractMiniThumbB64FromMessage(msg: TdApi.Message): Pair<Boolean, String?> {
        val c = msg.content ?: return Pair(false, null)
        val hasMedia = c.constructor != TdApi.MessageText.CONSTRUCTOR

        val carrier: Any? = when (c) {
            is TdApi.MessagePhoto -> c.photo
            is TdApi.MessageVideo -> c.video
            is TdApi.MessageAnimation -> c.animation
            is TdApi.MessageDocument -> c.document
            else -> null
        } ?: return Pair(hasMedia, null)

        fun getField(obj: Any, name: String): Any? = try {
            val f = obj.javaClass.getField(name)
            f.isAccessible = true
            f.get(obj)
        } catch (_: Throwable) { null }

        val mt = getField(carrier, "minithumbnail")
            ?: getField(carrier, "miniThumbnail")
            ?: getField(carrier, "minithumb")

        val data = (mt?.let { getField(it, "data") } as? ByteArray) ?: return Pair(hasMedia, null)
        if (data.isEmpty()) return Pair(hasMedia, null)

        return Pair(hasMedia, Base64.encodeToString(data, Base64.NO_WRAP))
    }
    // -----------------------------------------------------
}

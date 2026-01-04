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
import androidx.lifecycle.lifecycleScope
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
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.worker.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

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
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var tvMeta: TextView
    private lateinit var etCaption: com.google.android.material.textfield.TextInputEditText
    private lateinit var swSendWithMedia: SwitchMaterial

    private var srcChatId: Long = 0L
    private var srcMsgId: Long = 0L
    private var mediaUri: Uri? = null
    private var mediaMime: String? = null
    private var miniThumbB64: String? = null
    private var hasMediaHint: Boolean = false

    // images editing (optional)
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

        TdLibManager.init(this)
        TdLibManager.ensureClient()

        ivPreview = findViewById(R.id.ivPreview)
        ivWatermarkOverlay = findViewById(R.id.ivWatermarkOverlay)
        blurOverlay = findViewById(R.id.blurOverlay)
        tvMeta = findViewById(R.id.tvMeta)
        etCaption = findViewById(R.id.etCaption)
        swSendWithMedia = findViewById(R.id.swSendWithMedia)


        // Always show the toggle. Enable it only if we actually have media (or a hint that media exists).
        val canSendMedia =
            (mediaUri != null) ||
            hasMediaHint ||
            (!miniThumbB64.isNullOrBlank()) ||
            (!mediaMime.isNullOrBlank())

        swSendWithMedia.isEnabled = canSendMedia
        swSendWithMedia.isChecked = canSendMedia
        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        mediaMime = intent.getStringExtra(EXTRA_MEDIA_MIME)
        miniThumbB64 = intent.getStringExtra(EXTRA_MINITHUMB_B64)
        hasMediaHint = intent.getBooleanExtra(EXTRA_HAS_MEDIA_HINT, false)
        mediaUri = intent.getStringExtra(EXTRA_MEDIA_URI)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        etCaption.setText(text)
        tvMeta.text = buildMetaString()

        val hasMedia = (mediaUri != null) || hasMediaHint || !miniThumbB64.isNullOrBlank() || !mediaMime.isNullOrBlank()
        swSendWithMedia.isChecked = hasMedia

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
        if (mediaUri != null) sb.append("\nmediaUri=").append(mediaUri.toString())
        if (!mediaMime.isNullOrBlank()) sb.append("\nmediaMime=").append(mediaMime)
        if (!miniThumbB64.isNullOrBlank()) sb.append("\nminiThumb=present")
        if (hasMediaHint) sb.append("\nhasMediaHint=true")
        return sb.toString()
    }

    /**
     * Preview logic:
     * 1) If local mediaUri exists -> show image or first video frame
     * 2) Else show miniThumb (if exists) as placeholder
     * 3) Then fetch real Telegram thumbnail (sharp) via GetMessage + DownloadFile and display it
     */
    private fun loadPreview() {
        // Local media first
        mediaUri?.let { uri ->
            val bmp = readBitmap(uri)
            if (bmp != null) {
                workingBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
                ivPreview.setImageBitmap(workingBitmap)
                blurOverlay.setEnabledForImage(true)
                showWatermarkOverlayIfConfigured()
                return
            }
            val frame = readVideoFrame(uri)
            if (frame != null) {
                ivPreview.setImageBitmap(frame)
                blurOverlay.setEnabledForImage(false)
                showWatermarkOverlayIfConfigured()
                return
            }
        }

        // Placeholder: miniThumb if exists (low-res)
        val mini = decodeMiniThumb(miniThumbB64)
        if (mini != null) {
            ivPreview.setImageBitmap(mini)
        } else {
            ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
        }
        blurOverlay.setEnabledForImage(false)
        showWatermarkOverlayIfConfigured()

        // Fetch SHARP Telegram thumbnail async
        if (srcChatId != 0L && srcMsgId != 0L) {
            lifecycleScope.launch(Dispatchers.IO) {
                val sharp = fetchSharpTelegramThumb(srcChatId, srcMsgId)
                if (sharp != null) {
                    withContext(Dispatchers.Main) {
                        ivPreview.setImageBitmap(sharp)
                    }
                }
            }
        }
    }

    private fun fetchSharpTelegramThumb(chatId: Long, msgId: Long): Bitmap? {
        val msg = getMessageSync(chatId, msgId) ?: return null
        val c = msg.content ?: return null

        // Choose fileId of a real thumbnail
        val fileId: Int = when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                if (sizes.isEmpty()) return null
                // choose best <= 1280px for speed; otherwise biggest
                val best = sizes
                    .filter { it.width > 0 && it.height > 0 }
                    .sortedByDescending { it.width * it.height }
                    .firstOrNull { it.width <= 1280 } ?: sizes.maxByOrNull { it.width * it.height }
                best?.photo?.id ?: return null
            }

            is TdApi.MessageVideo -> {
                val t = c.video?.thumbnail
                t?.file?.id ?: return null
            }

            is TdApi.MessageAnimation -> {
                val t = c.animation?.thumbnail
                t?.file?.id ?: return null
            }

            is TdApi.MessageDocument -> {
                // some documents have thumbnail too
                val t = c.document?.thumbnail
                t?.file?.id ?: return null
            }

            else -> return null
        }

        val f = ensureFileDownloaded(fileId) ?: return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        return bmp
    }

    private fun getMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) msg = obj
            latch.countDown()
        }
        if (!latch.await(25, TimeUnit.SECONDS)) return null
        return msg
    }

    private fun ensureFileDownloaded(fileId: Int, timeoutSec: Int = 120): File? {
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { }

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var f: TdApi.File? = null
            TdLibManager.send(TdApi.GetFile(fileId)) { obj ->
                if (obj is TdApi.File) f = obj
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)

            val path = f?.local?.path
            val done = f?.local?.isDownloadingCompleted ?: false
            if (!path.isNullOrBlank() && done) {
                val ff = File(path)
                if (ff.exists() && ff.length() > 0) return ff
            }
            Thread.sleep(300)
        }
        return null
    }

    private fun decodeMiniThumb(b64: String?): Bitmap? {
        if (b64.isNullOrBlank()) return null
        return try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: run {
                // headerless jpeg sometimes
                val jfif = byteArrayOf(
                    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
                    0x00, 0x01, 0x00, 0x01, 0x00, 0x00
                )
                val full = jfif + raw
                BitmapFactory.decodeByteArray(full, 0, full.size)
            }
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

    private fun exportWatermarkPosNorm(): Pair<Float, Float> {
        if (ivWatermarkOverlay.visibility != View.VISIBLE) return Pair(-1f, -1f)
        val vw = ivPreview.width.coerceAtLeast(1).toFloat()
        val vh = ivPreview.height.coerceAtLeast(1).toFloat()
        val rx = (ivWatermarkOverlay.x / vw).coerceIn(0f, 1f)
        val ry = (ivWatermarkOverlay.y / vh).coerceIn(0f, 1f)
        return Pair(rx, ry)
    }

    private fun onWatermarkClick() {
        // For preview only: keep overlay; actual burn happens in worker
        showWatermarkOverlayIfConfigured()
        Snackbar.make(ivPreview, "✅ סימן מים יוחל בשליחה (והמיקום לפי הגרירה)", Snackbar.LENGTH_SHORT).show()
    }

    private fun toggleBlurMode() {
        // Blur overlay should show rectangles on preview; actual blur happens in worker
        blurOverlay.allowRectangles = true
        blurOverlay.blurMode = !blurOverlay.blurMode
        Snackbar.make(ivPreview, if (blurOverlay.blurMode) "מצב טשטוש: גרור מלבן" else "מצב טשטוש: כבוי", Snackbar.LENGTH_SHORT).show()
    }

    private fun translateToHebrew() {
        val src = etCaption.text?.toString().orEmpty().trim()
        if (src.isBlank()) {
            Snackbar.make(ivPreview, "אין טקסט לתרגום", Snackbar.LENGTH_SHORT).show()
            return
        }

        Snackbar.make(ivPreview, "⬇️ מוריד מודל ומתרגם...", Snackbar.LENGTH_SHORT).show()
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.HEBREW)
            .build()

        translator?.close()
        translator = Translation.getClient(opts)
        val tr = translator!!

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

        val (wmX, wmY) = exportWatermarkPosNorm()
        val sendWithMedia = (swSendWithMedia.visibility == View.VISIBLE && swSendWithMedia.isChecked)

        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, text)
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, sendWithMedia)
            .putString(SendWorker.KEY_MEDIA_URI, mediaUri?.toString().orEmpty())
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
        Snackbar.make(ivPreview, "✅ נכנס לתור שליחה. בדוק בערוץ יעד.", Snackbar.LENGTH_LONG).show()
    }
}

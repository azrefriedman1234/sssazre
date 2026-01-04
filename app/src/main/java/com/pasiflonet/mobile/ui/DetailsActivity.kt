package com.pasiflonet.mobile.ui

import android.content.ClipboardManager
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.worker.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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

    private lateinit var previewFrame: View
    private lateinit var ivPreview: ImageView
    private lateinit var ivWatermarkOverlay: ImageView
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var tvMeta: TextView
    private lateinit var tvCleanResult: TextView
    private lateinit var etCaption: TextInputEditText
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

    private var waitingForClipboardTranslate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        previewFrame = findViewById(R.id.previewFrame)
        ivPreview = findViewById(R.id.ivPreview)
        ivWatermarkOverlay = findViewById(R.id.ivWatermarkOverlay)
        blurOverlay = findViewById(R.id.blurOverlay)
        tvMeta = findViewById(R.id.tvMeta)
        tvCleanResult = findViewById(R.id.tvCleanResult)
        etCaption = findViewById(R.id.etCaption)
        swSendWithMedia = findViewById(R.id.swSendWithMedia)

        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        mediaMime = intent.getStringExtra(EXTRA_MEDIA_MIME)
        miniThumbB64 = intent.getStringExtra(EXTRA_MINITHUMB_B64)
        hasMediaHint = intent.getBooleanExtra(EXTRA_HAS_MEDIA_HINT, false)
        mediaUri = intent.getStringExtra(EXTRA_MEDIA_URI)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        etCaption.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())

        TdLibManager.init(this)
        TdLibManager.ensureClient()

        setupWatermarkOverlayDrag()
        showWatermarkOverlayIfConfigured()

        // תמיד רואים את המתג, אבל enable נקבע אחרי בדיקה מהטלגרם
        swSendWithMedia.visibility = View.VISIBLE
        swSendWithMedia.isEnabled = false
        swSendWithMedia.isChecked = false
        swSendWithMedia.text = "בודק מדיה..."

        loadPreview()

        findViewById<View>(R.id.btnWatermark).setOnClickListener {
            showWatermarkOverlayIfConfigured()
            Toast.makeText(this, "אפשר לגרור סימן מים בתצוגה", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnBlur).setOnClickListener {
            val on = blurOverlay.toggleDraw()
            Toast.makeText(this, if (on) "טשטוש: גרור מלבן" else "טשטוש: כבוי", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnTranslate).setOnClickListener { openTranslateOnline() }
        findViewById<View>(R.id.btnSend).setOnClickListener { enqueueSendAndClose() }

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCleanTmp).setOnClickListener { cleanTempFiles() }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForClipboardTranslate) {
            waitingForClipboardTranslate = false
            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val txt = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (txt.isNotBlank() && txt.any { it in '\u0590'..'\u05FF' }) {
                etCaption.setText(txt)
                Toast.makeText(this, "✅ הודבק תרגום מהקליפבורד", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadPreview() {
        tvMeta.text = "chatId=$srcChatId | msgId=$srcMsgId"

        // 1) אם יש URI מקומי – ננסה להציג ממנו
        mediaUri?.let { uri ->
            readBitmap(uri)?.let {
                ivPreview.setImageBitmap(it)
                checkHasMediaFromTelegramAsync()
                return
            }
            readVideoFrame(uri)?.let {
                ivPreview.setImageBitmap(it)
                checkHasMediaFromTelegramAsync()
                return
            }
        }

        // 2) fallback מהיר (miniThumb)
        decodeMiniThumb(miniThumbB64)?.let {
            ivPreview.setImageBitmap(it)
        } ?: ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)

        // 3) ואז נביא thumbnail חד מהטלגרם + נעדכן מתג
        checkHasMediaFromTelegramAsync()
        loadTelegramThumbnailAsync()
    }

    private fun checkHasMediaFromTelegramAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val msg = fetchMessageSync(srcChatId, srcMsgId)
            val hasMedia = when (msg?.content) {
                null -> hasMediaHint
                is TdApi.MessageText -> hasMediaHint
                else -> true
            }
            runOnUiThread {
                swSendWithMedia.isEnabled = hasMedia
                swSendWithMedia.isChecked = hasMedia
                swSendWithMedia.text = if (hasMedia) "שלח עם מדיה (עריכה תחול על השליחה)" else "אין מדיה בהודעה הזאת"
            }
        }
    }

    private fun loadTelegramThumbnailAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val msg = fetchMessageSync(srcChatId, srcMsgId) ?: return@launch
            val c = msg.content ?: return@launch

            val thumbFileId: Int? = when (c) {
                is TdApi.MessagePhoto -> {
                    val sizes = c.photo?.sizes ?: emptyArray()
                    val pick = sizes.minByOrNull { abs(it.width - 640) } ?: sizes.lastOrNull()
                    pick?.photo?.id
                }
                is TdApi.MessageVideo -> c.video?.thumbnail?.file?.id
                is TdApi.MessageAnimation -> c.animation?.thumbnail?.file?.id
                is TdApi.MessageDocument -> c.document?.thumbnail?.file?.id
                else -> null
            } ?: return@launch

            val f = ensureFileDownloaded(thumbFileId) ?: return@launch
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@launch
            runOnUiThread { ivPreview.setImageBitmap(bmp) }
        }
    }

    private fun fetchMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        if (chatId == 0L || msgId == 0L) return null
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) msg = obj
            latch.countDown()
        }
        if (!latch.await(20, TimeUnit.SECONDS)) return null
        return msg
    }

    private fun ensureFileDownloaded(fileId: Int, timeoutSec: Int = 60): File? {
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
            Thread.sleep(250)
        }
        return null
    }

    private fun decodeMiniThumb(b64: String?): Bitmap? {
        if (b64.isNullOrBlank()) return null
        return try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (_: Throwable) { null }
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


    private fun showWatermarkOverlayIfConfigured() {
        val wmStr = AppPrefs.getWatermark(this).trim()
        if (wmStr.isBlank()) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }
        val wmUri = runCatching { Uri.parse(wmStr) }.getOrNull() ?: run {
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

        ivWatermarkOverlay.post {
            val w = previewFrame.width.coerceAtLeast(1)
            val h = previewFrame.height.coerceAtLeast(1)
            val pad = (w * 0.02f).toInt().coerceAtLeast(12)
            ivWatermarkOverlay.x = (w - ivWatermarkOverlay.width - pad).toFloat().coerceAtLeast(0f)
            ivWatermarkOverlay.y = (h - ivWatermarkOverlay.height - pad).toFloat().coerceAtLeast(0f)
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
        val vw = previewFrame.width.coerceAtLeast(1).toFloat()
        val vh = previewFrame.height.coerceAtLeast(1).toFloat()
        val rx = (ivWatermarkOverlay.x / vw).coerceIn(0f, 1f)
        val ry = (ivWatermarkOverlay.y / vh).coerceIn(0f, 1f)
        return Pair(rx, ry)
    }

    private fun openTranslateOnline() {
        val src = etCaption.text?.toString().orEmpty().trim()
        if (src.isBlank()) {
            Toast.makeText(this, "אין טקסט לתרגום", Toast.LENGTH_SHORT).show()
            return
        }
        val enc = URLEncoder.encode(src, "UTF-8")
        val url = "https://translate.google.com/?sl=auto&tl=iw&text=$enc&op=translate"
        waitingForClipboardTranslate = true
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        Toast.makeText(this, "פתחתי תרגום אונליין. העתק את התוצאה וחזור לאפליקציה.", Toast.LENGTH_LONG).show()
    }

    private fun cleanTempFiles() {
        fun cleanDir(d: File?): Pair<Int, Long> {
            if (d == null || !d.exists() || !d.isDirectory) return 0 to 0L
            var count = 0
            var bytes = 0L
            d.listFiles()?.forEach { f ->
                if (f.isFile) {
                    bytes += f.length()
                    if (f.delete()) count++
                } else if (f.isDirectory) {
                    val (c2, b2) = cleanDir(f)
                    count += c2
                    bytes += b2
                    // מוחקים תיקיה רק אם ריקה
                    f.delete()
                }
            }
            return count to bytes
        }

        // חשוב: מנקים רק תיקיות זמניות שאנחנו יוצרים, לא TDLib ולא DB
        val dirs = listOf(
            File(cacheDir, "pasiflonet_tmp"),
            File(cacheDir, "pasiflonet_edits"),
            externalCacheDir?.let { File(it, "pasiflonet_tmp") },
            externalCacheDir?.let { File(it, "pasiflonet_edits") }
        ).filterNotNull()

        var totalC = 0
        var totalB = 0L
        dirs.forEach { d ->
            val (c, b) = cleanDir(d)
            totalC += c
            totalB += b
        }

        val msg = "נוקו $totalC קבצים (${totalB / 1024}KB)"
        tvCleanResult.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun enqueueSendAndClose() {
        val target = AppPrefs.getTargetUsername(this).trim()
        if (target.isBlank()) {
            Toast.makeText(this, "❌ לא הוגדר @username יעד", Toast.LENGTH_SHORT).show()
            return
        }
        if (srcChatId == 0L || srcMsgId == 0L) {
            Toast.makeText(this, "❌ חסרים מזהי מקור", Toast.LENGTH_SHORT).show()
            return
        }

        val sendWithMedia = swSendWithMedia.isEnabled && swSendWithMedia.isChecked
        val text = etCaption.text?.toString().orEmpty()
        val wmUri = AppPrefs.getWatermark(this).trim()

        val rects = blurOverlay.exportRectsNormalized()
        val rectsStr = rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

        val (wmX, wmY) = exportWatermarkPosNorm()

        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, text)
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, sendWithMedia)
            .putString(SendWorker.KEY_MEDIA_URI, mediaUri?.toString().orEmpty())
            .putString(SendWorker.KEY_MEDIA_MIME, mediaMime.orEmpty())
            .putString(SendWorker.KEY_WATERMARK_URI, wmUri)
            .putString(SendWorker.KEY_BLUR_RECTS, rectsStr)
            .putFloat(SendWorker.KEY_WM_X, wmX)
            .putFloat(SendWorker.KEY_WM_Y, wmY)
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)

        Toast.makeText(
            this,
            if (sendWithMedia) "✅ נשלח עם מדיה (מוריד מהטלגרם ומפעיל עריכה)" else "✅ נשלח כטקסט בלבד",
            Toast.LENGTH_LONG
        ).show()

        // חובה: חוזר למסך הטבלה הראשי
        finish()
    }
}

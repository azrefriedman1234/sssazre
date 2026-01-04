package com.pasiflonet.mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.worker.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

class DetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SRC_CHAT_ID = "src_chat_id"
        const val EXTRA_SRC_MESSAGE_ID = "src_message_id"
        const val EXTRA_TEXT = "text"

        fun start(ctx: Context, chatId: Long, msgId: Long, text: String) {
            val i = Intent(ctx, DetailsActivity::class.java)
            i.putExtra(EXTRA_SRC_CHAT_ID, chatId)
            i.putExtra(EXTRA_SRC_MESSAGE_ID, msgId)
            i.putExtra(EXTRA_TEXT, text)
            ctx.startActivity(i)
        }
    }

    private lateinit var ivPreview: ImageView
    private lateinit var ivWatermarkOverlay: ImageView
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var etCaption: com.google.android.material.textfield.TextInputEditText
    private lateinit var swSendWithMedia: SwitchMaterial

    private var srcChatId: Long = 0L
    private var srcMsgId: Long = 0L

    private var hasMedia: Boolean = false

    // watermark drag state
    private var wmDragging = false
    private var wmDx = 0f
    private var wmDy = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        ivPreview = findViewById(R.id.ivPreview)
        ivWatermarkOverlay = findViewById(R.id.ivWatermarkOverlay)
        blurOverlay = findViewById(R.id.blurOverlay)
        etCaption = findViewById(R.id.etCaption)
        swSendWithMedia = findViewById(R.id.swSendWithMedia)

        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        etCaption.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())

        // תמיד רואים את המתג, אבל אם אין מדיה הוא ינוטרל
        swSendWithMedia.visibility = View.VISIBLE
        swSendWithMedia.isEnabled = false
        swSendWithMedia.isChecked = false

        setupWatermarkOverlayDrag()

        findViewById<View>(R.id.btnBlur).setOnClickListener { toggleBlurMode() }
        findViewById<View>(R.id.btnWatermark).setOnClickListener { toggleWatermarkOverlay() }
        findViewById<View>(R.id.btnSend).setOnClickListener { enqueueSend() }

        TdLibManager.init(this)
        TdLibManager.ensureClient()

        // טוען thumbnail חד מהטלגרם + קובע האם יש מדיה
        loadTelegramPreview()
    }

    private fun loadTelegramPreview() {
        lifecycleScope.launch(Dispatchers.IO) {
            val msg = getMessageSync(srcChatId, srcMsgId)
            if (msg == null) {
                runOnUiThread {
                    ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
                    Snackbar.make(ivPreview, "❌ לא הצלחתי למשוך הודעה מהטלגרם", Snackbar.LENGTH_LONG).show()
                }
                return@launch
            }

            val info = extractMediaInfo(msg)
            hasMedia = info.hasMedia

            runOnUiThread {
                swSendWithMedia.isEnabled = hasMedia
                swSendWithMedia.isChecked = hasMedia
                if (!hasMedia) {
                    swSendWithMedia.text = "שלח עם מדיה (אין מדיה בהודעה)"
                } else {
                    swSendWithMedia.text = "שלח עם מדיה (${info.kindLabel})"
                }
            }

            // preview: קודם try thumbnail (חד), ואם אין—תמונה סמלית
            val fileId = info.thumbFileId ?: info.mediaFileId
            if (fileId == null) {
                runOnUiThread { ivPreview.setImageResource(android.R.drawable.ic_menu_report_image) }
                return@launch
            }

            val path = ensureDownloadedPath(fileId) ?: run {
                runOnUiThread { ivPreview.setImageResource(android.R.drawable.ic_menu_report_image) }
                return@launch
            }

            val bmp = BitmapFactory.decodeFile(path)
            runOnUiThread {
                if (bmp != null) ivPreview.setImageBitmap(bmp)
                else ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        }
    }

    private data class MediaInfo(
        val hasMedia: Boolean,
        val kindLabel: String,
        val thumbFileId: Int?,
        val mediaFileId: Int?
    )

    private fun extractMediaInfo(msg: TdApi.Message): MediaInfo {
        val c = msg.content ?: return MediaInfo(false, "TEXT", null, null)

        return when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height }
                val fileId = best?.photo?.id
                MediaInfo(true, "PHOTO", fileId, fileId)
            }
            is TdApi.MessageVideo -> {
                val thumb = c.video?.thumbnail?.file?.id
                val media = c.video?.video?.id
                MediaInfo(true, "VIDEO", thumb, media)
            }
            is TdApi.MessageAnimation -> {
                val thumb = c.animation?.thumbnail?.file?.id
                val media = c.animation?.animation?.id
                MediaInfo(true, "ANIMATION", thumb, media)
            }
            is TdApi.MessageDocument -> {
                val thumb = c.document?.thumbnail?.file?.id
                val media = c.document?.document?.id
                MediaInfo(true, "DOCUMENT", thumb, media)
            }
            else -> MediaInfo(false, "TEXT", null, null)
        }
    }

    private fun toggleBlurMode() {
        blurOverlay.allowRectangles = true
        blurOverlay.blurMode = !blurOverlay.blurMode
        Snackbar.make(
            ivPreview,
            if (blurOverlay.blurMode) "✅ מצב טשטוש פעיל: גרור מלבן על התמונה" else "מצב טשטוש כבוי",
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun toggleWatermarkOverlay() {
        val wmStr = AppPrefs.getWatermark(this).trim()
        if (wmStr.isBlank()) {
            Snackbar.make(ivPreview, "❌ לא הוגדר סימן מים בהגדרות", Snackbar.LENGTH_SHORT).show()
            return
        }

        val wmUri = runCatching { Uri.parse(wmStr) }.getOrNull()
        val bmp = try {
            contentResolver.openInputStream(wmUri!!)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) { null }

        if (bmp == null) {
            Snackbar.make(ivPreview, "❌ לא הצלחתי לקרוא את תמונת הסימן מים", Snackbar.LENGTH_SHORT).show()
            return
        }

        ivWatermarkOverlay.setImageBitmap(bmp)
        ivWatermarkOverlay.visibility =
            if (ivWatermarkOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE

        if (ivWatermarkOverlay.visibility == View.VISIBLE) {
            ivWatermarkOverlay.post {
                val pad = max(12, (ivPreview.width * 0.02f).toInt())
                ivWatermarkOverlay.x = (ivPreview.width - ivWatermarkOverlay.width - pad).toFloat().coerceAtLeast(0f)
                ivWatermarkOverlay.y = (ivPreview.height - ivWatermarkOverlay.height - pad).toFloat().coerceAtLeast(0f)
            }
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

        val sendWithMedia = swSendWithMedia.isEnabled && swSendWithMedia.isChecked
        val wm = AppPrefs.getWatermark(this).trim()

        val rects = blurOverlay.exportRectsNormalized()
        val rectsStr = rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

        val (wmX, wmY) = exportWatermarkPosNorm()

        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, etCaption.text?.toString().orEmpty())
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, sendWithMedia)
            .putString(SendWorker.KEY_WATERMARK_URI, wm)
            .putString(SendWorker.KEY_BLUR_RECTS, rectsStr)
            .putFloat(SendWorker.KEY_WM_X, wmX)
            .putFloat(SendWorker.KEY_WM_Y, wmY)
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)

        Snackbar.make(
            ivPreview,
            if (sendWithMedia) "✅ נכנס לתור שליחה עם מדיה" else "✅ נכנס לתור שליחה (טקסט בלבד)",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun getMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        if (chatId == 0L || msgId == 0L) return null
        val latch = CountDownLatch(1)
        var out: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) out = obj
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        return out
    }

    private fun ensureDownloadedPath(fileId: Int, timeoutSec: Int = 40): String? {
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { }
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var f: TdApi.File? = null
            TdLibManager.send(TdApi.GetFile(fileId)) { obj ->
                if (obj is TdApi.File) f = obj
                latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
            val done = f?.local?.isDownloadingCompleted ?: false
            val path = f?.local?.path
            if (done && !path.isNullOrBlank()) {
                val ff = File(path)
                if (ff.exists() && ff.length() > 0) return ff.absolutePath
            }
            Thread.sleep(250)
        }
        return null
    }
}

package com.pasiflonet.mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class DetailsActivity : AppCompatActivity() {

    // ---- Compat: older code may call blurOverlay.setDrawEnabled(...) ----
    // Stays compile-safe even if BlurOverlayView internals change.
    private fun BlurOverlayView.setDrawEnabled(enabled: Boolean) {
        this.isEnabled = enabled
        // best-effort via reflection to set common flags if exist:
        trySetBoolField(this, "allowRectangles", enabled)
        trySetBoolField(this, "blurMode", enabled)
        this.invalidate()
    }

    private fun trySetBoolField(obj: Any, name: String, value: Boolean) {
        // field
        try {
            val f = obj.javaClass.getField(name)
            f.isAccessible = true
            f.setBoolean(obj, value)
            return
        } catch (_: Throwable) {}

        // setter: setXxx(boolean)
        try {
            val setter = "set" + name.replaceFirstChar { it.uppercase() }
            val m = obj.javaClass.methods.firstOrNull {
                it.name == setter && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Boolean::class.java || it.parameterTypes[0] == java.lang.Boolean.TYPE)
            }
            m?.invoke(obj, value)
        } catch (_: Throwable) {}
    }
    // ---------------------------------------------------------------


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
    private lateinit var previewFrame: View
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var tvMeta: TextView
    private lateinit var etCaption: com.google.android.material.textfield.TextInputEditText
    private lateinit var swSendWithMedia: SwitchMaterial

    private var srcChatId: Long = 0L
    private var srcMsgId: Long = 0L

    private var hasMedia: Boolean = false
    private var mediaMime: String? = null

    // watermark normalized position (center) 0..1; -1 = default
    private var wmXNorm: Float = -1f
    private var wmYNorm: Float = -1f
    private val sp by lazy { getSharedPreferences("pasiflonet_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        ivPreview = findViewById(R.id.ivPreview)
        blurOverlay = findViewById(R.id.blurOverlay)
        tvMeta = findViewById(R.id.tvMeta)
        etCaption = findViewById(R.id.etCaption)
        swSendWithMedia = findViewById(R.id.swSendWithMedia)

        previewFrame = findViewById(R.id.previewFrame)
        ivWatermarkOverlay = findViewById(R.id.ivWatermarkOverlay)

        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        etCaption.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())

        // Close button MUST work
        val closeId = resources.getIdentifier("btnClose", "id", packageName)
        if (closeId != 0) {
            findViewById<View>(closeId).setOnClickListener { finish() }
        }

        // always visible toggle (enabled only if media exists)
        swSendWithMedia.visibility = View.VISIBLE
        swSendWithMedia.isEnabled = false
        swSendWithMedia.isChecked = false

        // keep overlay on top
        try { blurOverlay.bringToFront() } catch (_: Throwable) {}
        try { ivWatermarkOverlay.bringToFront() } catch (_: Throwable) {}

        findViewById<View>(R.id.btnBlur).setOnClickListener {
            // אם BlurOverlayView תומך במצב ציור, ננסה לקרוא לזה בצורה "בטוחה"
            runCatching { blurOverlay.setDrawEnabled(true) }
            runCatching { blurOverlay.bringToFront() }
            runCatching { blurOverlay.invalidate() }
            Snackbar.make(ivPreview, "גרור מלבן על התמונה לטשטוש.", Snackbar.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnWatermark).setOnClickListener {
            // מציג watermark ומאפשר גרירה
            ensureWatermarkPreview()
            Toast.makeText(this, "גרור את סימן המים למיקום הרצוי", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnTranslate).setOnClickListener {
            Snackbar.make(ivPreview, "ℹ️ תרגום לא נוגע כרגע כדי לא לשבור. קודם מייצבים מדיה/שליחה.", Snackbar.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.btnSend).setOnClickListener { enqueueSendAndReturn() }

        TdLibManager.init(this)
        TdLibManager.ensureClient()

        tvMeta.text = "chatId=$srcChatId | msgId=$srcMsgId\nיעד: ${AppPrefs.getTargetUsername(this).ifBlank { "(לא הוגדר)" }}"

        // load saved watermark position
        wmXNorm = sp.getFloat("wm_x_norm", -1f)
        wmYNorm = sp.getFloat("wm_y_norm", -1f)

        lifecycleScope.launch(Dispatchers.IO) {
            val msg = fetchMessageSync(srcChatId, srcMsgId)
            if (msg == null) {
                runOnUiThread {
                    ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
                    Snackbar.make(ivPreview, "❌ לא הצלחתי להביא הודעה מ-Telegram", Snackbar.LENGTH_LONG).show()
                }
                return@launch
            }

            val info = analyzeMessage(msg)
            hasMedia = info.hasMedia
            mediaMime = info.mime

            val bmp = loadBestPreviewBitmap(msg)

            runOnUiThread {
                swSendWithMedia.isEnabled = hasMedia
                swSendWithMedia.isChecked = hasMedia

                if (bmp != null) ivPreview.setImageBitmap(bmp)
                else ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)

                tvMeta.text = tvMeta.text.toString() +
                        "\nmedia=" + (if (hasMedia) "YES" else "NO") + (mediaMime?.let { " ($it)" } ?: "")

                // show watermark overlay immediately if configured
                ensureWatermarkPreview()
            }
        }
    }

    private data class MsgInfo(val hasMedia: Boolean, val mime: String?)

    private fun analyzeMessage(m: TdApi.Message): MsgInfo {
        val c = m.content ?: return MsgInfo(false, null)
        return when (c) {
            is TdApi.MessageText -> MsgInfo(false, null)
            is TdApi.MessagePhoto -> MsgInfo(true, "image/jpeg")
            is TdApi.MessageVideo -> MsgInfo(true, c.video?.mimeType)
            is TdApi.MessageAnimation -> MsgInfo(true, c.animation?.mimeType)
            is TdApi.MessageDocument -> MsgInfo(true, c.document?.mimeType)
            else -> MsgInfo(true, null)
        }
    }

    private fun fetchMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var out: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) out = obj
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
        return out
    }

    private fun loadBestPreviewBitmap(msg: TdApi.Message): Bitmap? {
        val c = msg.content ?: return null

        val thumbFileId: Int? = when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height }
                best?.photo?.id
            }
            is TdApi.MessageVideo -> c.video?.thumbnail?.file?.id
            is TdApi.MessageAnimation -> c.animation?.thumbnail?.file?.id
            is TdApi.MessageDocument -> c.document?.thumbnail?.file?.id
            else -> null
        }

        if (thumbFileId != null) {
            val f = ensureFileDownloaded(thumbFileId, timeoutSec = 45)
            if (f != null && f.exists() && f.length() > 0) {
                return BitmapFactory.decodeFile(f.absolutePath)
            }
        }
        return null
    }

    private fun ensureFileDownloaded(fileId: Int, timeoutSec: Int): File? {
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
            Thread.sleep(350)
        }
        return null
    }

    private fun ensureWatermarkPreview() {
        val wm = AppPrefs.getWatermark(this).trim()
        if (wm.isBlank()) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }

        val bmp = loadWatermarkBitmap(wm) ?: run {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }

        ivWatermarkOverlay.setImageBitmap(bmp)
        ivWatermarkOverlay.visibility = View.VISIBLE

        // size + position after layout
        previewFrame.post {
            val frameW = previewFrame.width
            val frameH = previewFrame.height
            if (frameW <= 0 || frameH <= 0) return@post

            val targetW = max((frameW * 0.22f).toInt(), 90)
            val lp = ivWatermarkOverlay.layoutParams
            lp.width = targetW
            lp.height = targetW
            ivWatermarkOverlay.layoutParams = lp

            // default position or saved
            if (wmXNorm in 0f..1f && wmYNorm in 0f..1f) {
                placeWatermarkByNorm(wmXNorm, wmYNorm)
            } else {
                // bottom-right default
                val x = (frameW - ivWatermarkOverlay.width - 16).toFloat().coerceAtLeast(0f)
                val y = (frameH - ivWatermarkOverlay.height - 16).toFloat().coerceAtLeast(0f)
                ivWatermarkOverlay.x = x
                ivWatermarkOverlay.y = y
                updateNormFromOverlay()
            }

            enableWatermarkDrag()
            ivWatermarkOverlay.bringToFront()
            blurOverlay.bringToFront() // blur needs to stay above preview too
        }
    }

    private fun loadWatermarkBitmap(wm: String): Bitmap? {
        return try {
            val uri = Uri.parse(wm)
            when (uri.scheme?.lowercase()) {
                "content" -> {
                    contentResolver.openInputStream(uri).use { ins ->
                        if (ins == null) return null
                        BitmapFactory.decodeStream(ins)
                    }
                }
                "file" -> {
                    val f = File(uri.path ?: return null)
                    if (!f.exists()) return null
                    BitmapFactory.decodeFile(f.absolutePath)
                }
                else -> {
                    val f = File(wm)
                    if (!f.exists()) return null
                    BitmapFactory.decodeFile(f.absolutePath)
                }
            }
        } catch (_: Throwable) {
            val f = File(wm)
            if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
        }
    }

    private fun enableWatermarkDrag() {
        var startX = 0f
        var startY = 0f
        var downRawX = 0f
        var downRawY = 0f

        ivWatermarkOverlay.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = v.x
                    startY = v.y
                    downRawX = e.rawX
                    downRawY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    val frameW = previewFrame.width.toFloat()
                    val frameH = previewFrame.height.toFloat()

                    val maxX = max(0f, frameW - v.width)
                    val maxY = max(0f, frameH - v.height)

                    v.x = min(max(0f, startX + dx), maxX)
                    v.y = min(max(0f, startY + dy), maxY)
                    updateNormFromOverlay()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // save
                    sp.edit().putFloat("wm_x_norm", wmXNorm).putFloat("wm_y_norm", wmYNorm).apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun placeWatermarkByNorm(nx: Float, ny: Float) {
        val frameW = previewFrame.width.toFloat()
        val frameH = previewFrame.height.toFloat()
        val w = ivWatermarkOverlay.width.toFloat()
        val h = ivWatermarkOverlay.height.toFloat()
        if (frameW <= 0 || frameH <= 0 || w <= 0 || h <= 0) return

        val cx = nx * frameW
        val cy = ny * frameH
        val x = (cx - w / 2f).coerceIn(0f, max(0f, frameW - w))
        val y = (cy - h / 2f).coerceIn(0f, max(0f, frameH - h))
        ivWatermarkOverlay.x = x
        ivWatermarkOverlay.y = y
        updateNormFromOverlay()
    }

    private fun updateNormFromOverlay() {
        val frameW = previewFrame.width.toFloat().takeIf { it > 0 } ?: return
        val frameH = previewFrame.height.toFloat().takeIf { it > 0 } ?: return
        val cx = ivWatermarkOverlay.x + ivWatermarkOverlay.width / 2f
        val cy = ivWatermarkOverlay.y + ivWatermarkOverlay.height / 2f
        wmXNorm = (cx / frameW).coerceIn(0f, 1f)
        wmYNorm = (cy / frameH).coerceIn(0f, 1f)
    }

    private fun enqueueSendAndReturn() {
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

        val rectsStr = try {
            val rects = blurOverlay.exportRectsNormalized()
            rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
        } catch (_: Throwable) {
            ""
        }

        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, etCaption.text?.toString().orEmpty())
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, sendWithMedia)
            .putString(SendWorker.KEY_MEDIA_URI, "") // worker fetches from Telegram
            .putString(SendWorker.KEY_MEDIA_MIME, mediaMime.orEmpty())
            .putString(SendWorker.KEY_WATERMARK_URI, AppPrefs.getWatermark(this).trim())
            .putString(SendWorker.KEY_BLUR_RECTS, rectsStr)
            .putFloat(SendWorker.KEY_WM_X, wmXNorm)
            .putFloat(SendWorker.KEY_WM_Y, wmYNorm)
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)
        Toast.makeText(this, "✅ נשלח. חוזר לטבלה…", Toast.LENGTH_SHORT).show()
        finish()
    }
}

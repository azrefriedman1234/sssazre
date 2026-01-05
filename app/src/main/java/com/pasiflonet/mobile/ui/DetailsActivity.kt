package com.pasiflonet.mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.pasiflonet.mobile.util.translateToHebrewCompat
import com.pasiflonet.mobile.util.TranslateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.concurrent.thread


class DetailsActivity : AppCompatActivity() {

    companion object {
        private const val TAG_SEND = "SEND"
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
            ctx.startActivity(i)
        }
    }

    private lateinit var ivPreview: ImageView
    private lateinit var ivWatermarkOverlay: ImageView
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var tvMeta: TextView
    private lateinit var etCaption: TextInputEditText
    private lateinit var swSendWithMedia: SwitchMaterial

    private var srcChatId = 0L
    private var srcMsgId = 0L
    private var mediaUri: Uri? = null
    private var mediaMime: String? = null
    private var miniThumbB64: String? = null

    private var wmDragging = false
    private var wmDx = 0f
    private var wmDy = 0f

    private val translateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                val out = res.data?.getStringExtra(TranslateActivity.EXTRA_TRANSLATED_TEXT).orEmpty().trim()
                if (out.isNotBlank()) etCaption.setText(out)
            }
        }

    private var blurEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("SEND_WORK")
            .observe(this) { list ->
                if (list.isNullOrEmpty()) return@observe
                val info = list.maxByOrNull { it.runAttemptCount } ?: list.last()

                val tail =
                    info.progress.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_TAIL)
                        ?: info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_TAIL)
                        ?: ""

                if (info.state == androidx.work.WorkInfo.State.FAILED) {
                    val err = info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_ERROR_MSG) ?: "Send failed"
                    val logFile = info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_FILE) ?: ""
                    val msg = "Send failed (see logs above)"
                    android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show()
                }
            }

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

        etCaption.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())

        TdLibManager.init(this)
        TdLibManager.ensureClient()

        tvMeta.text = "chatId=$srcChatId | msgId=$srcMsgId"

        // תמיד רואים מתג; הוא יכובה אם אין מדיה
        swSendWithMedia.visibility = View.VISIBLE
        swSendWithMedia.isEnabled = false
        swSendWithMedia.isChecked = false
        swSendWithMedia.text = "בודק מדיה"

        // תצוגת בסיס מהירה (miniThumb), ואז Thumb חד מהטלגרם
        decodeMiniThumb(miniThumbB64)?.let { ivPreview.setImageBitmap(it) }
        loadTelegramThumbAndMediaFlag()

        setupWatermarkOverlayDrag()
        showWatermarkOverlayIfConfigured()

        findViewById<View>(R.id.btnWatermark).setOnClickListener {
            showWatermarkOverlayIfConfigured()
            Toast.makeText(this, "אפשר לגרור את סימן המים על הפריוויו", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnBlur).setOnClickListener {
            blurEnabled = !blurEnabled
            blurOverlay.visibility = if (blurEnabled) View.VISIBLE else View.GONE
            if (blurEnabled) {
                Toast.makeText(this, "טשטוש: גרור מלבן על התצוגה", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "טשטוש: כבוי", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btnTranslate).setOnClickListener {
            val src = etCaption.text?.toString().orEmpty()
        }

        findViewById<View>(R.id.btnSend).setOnClickListener { enqueueSendAndClose() }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
    }

    private fun decodeMiniThumb(b64: String?): android.graphics.Bitmap? {
        if (b64.isNullOrBlank()) return null
        return try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (_: Throwable) { null }
    }

    private fun fetchMessageSync(): TdApi.Message? {
        if (srcChatId == 0L || srcMsgId == 0L) return null
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(srcChatId, srcMsgId)) { obj ->
            if (obj is TdApi.Message) msg = obj
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
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
            latch.await(8, TimeUnit.SECONDS)
            val done = f?.local?.isDownloadingCompleted ?: false
            val path = f?.local?.path
            if (done && !path.isNullOrBlank()) {
                val ff = File(path)
                if (ff.exists() && ff.length() > 0) return ff
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun loadTelegramThumbAndMediaFlag() {
        lifecycleScope.launch(Dispatchers.IO) {
            val msg = fetchMessageSync()
            val hasMedia = msg?.content != null && msg.content.constructor != TdApi.MessageText.CONSTRUCTOR

            // נסה להביא thumbnail חד
            val thumbId: Int? = when (val c = msg?.content) {
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

            val bmp = thumbId?.let { ensureFileDownloaded(it, 45) }?.let { f ->
                BitmapFactory.decodeFile(f.absolutePath)
            }

            runOnUiThread {
                swSendWithMedia.isEnabled = hasMedia
                swSendWithMedia.isChecked = hasMedia
                swSendWithMedia.text = if (hasMedia) "שלח עם מדיה (כולל עריכה)" else "אין מדיה בהודעה"
                if (bmp != null) ivPreview.setImageBitmap(bmp)
            }
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
        val bmp = try {
            contentResolver.openInputStream(wmUri).use { inp ->
                if (inp == null) null else BitmapFactory.decodeStream(inp)
            }
        } catch (_: Throwable) { null }

        if (bmp == null) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }
        ivWatermarkOverlay.setImageBitmap(bmp)
        ivWatermarkOverlay.visibility = View.VISIBLE

        ivWatermarkOverlay.post {
            val pad = (ivPreview.width * 0.02f).toInt().coerceAtLeast(12)
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
        val vw = max(1, ivPreview.width).toFloat()
        val vh = max(1, ivPreview.height).toFloat()
        val rx = (ivWatermarkOverlay.x / vw).coerceIn(0f, 1f)
        val ry = (ivWatermarkOverlay.y / vh).coerceIn(0f, 1f)
        return Pair(rx, ry)
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
        val wm = AppPrefs.getWatermark(this).trim()

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
            .putString(SendWorker.KEY_WATERMARK_URI, wm)
            .putString(SendWorker.KEY_BLUR_RECTS, rectsStr)
            .putFloat(SendWorker.KEY_WM_X, wmX)
            .putFloat(SendWorker.KEY_WM_Y, wmY)
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>().addTag("SEND_WORK")
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)

        // Live logs from SendWorker (progress/outputData)
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(req.id)
            .observe(this) { info ->
                if (info == null) return@observe

                val tail =
                    info.progress.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_TAIL)
                        ?: info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_TAIL)
                        ?: ""

                if (info.state == androidx.work.WorkInfo.State.FAILED) {
                    val err = info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_ERROR_MSG) ?: "Send failed"
                    val logFile = info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_FILE) ?: ""
                    android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show()
                }
            }


        Toast.makeText(
            this,
            if (sendWithMedia) "✅ נשלח עם מדיה + טקסט (מוריד מהטלגרם ומעריך)" else "✅ נשלח טקסט בלבד",
            Toast.LENGTH_LONG
        ).show()

        finish() // חוזר לטבלה
    }

    


    


    

    // === SEND_LOG_UI_BEGIN ===
    private var logDialog: androidx.appcompat.app.AlertDialog? = null
    private var logTextView: android.widget.TextView? = null

    private fun showOrUpdateLogDialog(text: String) {
        if (logDialog == null) {
            val tv = android.widget.TextView(this).apply {
                setPadding(32, 24, 32, 24)
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val scroll = android.widget.ScrollView(this).apply { addView(tv) }
            logTextView = tv
            logDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("FFmpeg / Send logs")
                .setView(scroll)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .create()
            logDialog!!.show()
        }
        logTextView?.text = text
    }

    private fun initSendWorkLogs() {
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("SEND_WORK")
            .observe(this) { list ->
                if (list.isNullOrEmpty()) return@observe
                val info = list.last()

                val tail =
                    info.progress.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_TAIL)
                        ?: info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_TAIL)
                        ?: ""

                if (tail.isNotBlank()) showOrUpdateLogDialog(tail)

                if (info.state == androidx.work.WorkInfo.State.FAILED) {
                    val err = info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_ERROR_MSG) ?: "Send failed"
                    val logFile = info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_FILE) ?: ""
                    val msg = "ERROR: " + err + "\n\n" + tail + "\n\nLOG FILE: " + logFile
                    showOrUpdateLogDialog(msg)
                    android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show()
                }
            }
    }
    // === SEND_LOG_UI_END ===


            runOnUiThread {
                val i = android.content.Intent(this, com.pasiflonet.mobile.ui.TranslateActivity::class.java)
                translateLauncher.launch(i)
            }


// PAS_TRANSLATE_ASYNC_BEGIN
    // Async translate: never block UI thread
    private fun pasTranslateAsync(src: String) {
    }
// PAS_TRANSLATE_ASYNC_END

}

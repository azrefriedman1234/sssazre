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
 
        pasInitSendLogsUi()
${logFile}")
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

    private 




    // === SEND_LOG_UI_BEGIN ===
    private var pasLogDialog: androidx.appcompat.app.AlertDialog? = null
    private var pasLogTextView: android.widget.TextView? = null

    private fun pasShowLogDialog(text: String) {
        if (pasLogDialog == null) {
            val tv = android.widget.TextView(this).apply {
                setPadding(32, 24, 32, 24)
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val scroll = android.widget.ScrollView(this).apply { addView(tv) }
            pasLogTextView = tv
            pasLogDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("FFmpeg / Send logs")
                .setView(scroll)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .create()
            pasLogDialog!!.show()
        }
        pasLogTextView?.text = text
    }

    private fun pasInitSendLogsUi() {
        // show live worker logs by tag
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("SEND_WORK")
            .observe(this) { list ->
                if (list.isNullOrEmpty()) return@observe
                val info = list.maxByOrNull { it.runAttemptCount } ?: list.last()

                val tail =
                    info.progress.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_TAIL)
                        ?: info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_TAIL)
                        ?: ""

                if (tail.isNotBlank()) pasShowLogDialog(tail)

                if (info.state == androidx.work.WorkInfo.State.FAILED) {
                    val err = info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_ERROR_MSG) ?: "Send failed"
                    val logFile = info.outputData.getString(com.pasiflonet.mobile.worker.SendWorker.KEY_LOG_FILE) ?: ""
                    val msg = "ERROR: " + err + "\n\n" + tail + "\n\nLOG FILE: " + logFile
                    pasShowLogDialog(msg)
                    android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show()
                }
            }

        // write UI crash log to device (best effort)
        val crashDir = java.io.File(getExternalFilesDir(null), "pasiflonet_logs").apply { mkdirs() }
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            runCatching {
                val f = java.io.File(crashDir, "ui_crash_${System.currentTimeMillis()}.log")
                f.writeText(android.util.Log.getStackTraceString(e))
            }
        }
    }
    // === SEND_LOG_UI_END ===

}
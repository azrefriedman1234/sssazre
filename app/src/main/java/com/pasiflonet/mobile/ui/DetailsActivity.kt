package com.pasiflonet.mobile.ui

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.ui.editor.OverlayEditorView
import com.pasiflonet.mobile.util.VideoEditPipeline

class DetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INPUT_URI = "input_uri"
        const val EXTRA_TARGET_CHANNEL = "target_channel"
    }

    private lateinit var videoView: VideoView
    private lateinit var overlay: OverlayEditorView
    private lateinit var etWatermark: TextInputEditText
    private lateinit var tvStatus: TextView

    private var inputUri: Uri? = null
    private var outFilePath: String? = null
    private var targetChannel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        val tb = findViewById<MaterialToolbar>(R.id.toolbarDetails)
        tb.setNavigationOnClickListener { finish() }

        videoView = findViewById(R.id.videoView)
        overlay = findViewById(R.id.overlay)
        etWatermark = findViewById(R.id.etWatermarkText)
        tvStatus = findViewById(R.id.tvDetailsStatus)

        targetChannel = intent?.getStringExtra(EXTRA_TARGET_CHANNEL)?.trim().orEmpty()
        val sUri = intent?.getStringExtra(EXTRA_INPUT_URI)?.trim().orEmpty()
        inputUri = if (sUri.isNotEmpty()) Uri.parse(sUri) else null

        if (inputUri == null) {
            tvStatus.text = "שגיאה: אין וידאו"
            return
        }

        videoView.setVideoURI(inputUri)
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            videoView.start()
        }

        findViewById<MaterialButton>(R.id.btnBlurMode).setOnClickListener {
            overlay.blurMode = !overlay.blurMode
            tvStatus.text = if (overlay.blurMode) "מצב: טשטוש פעיל (צייר מלבן)" else "מצב: טשטוש כבוי"
        }

        findViewById<MaterialButton>(R.id.btnUndo).setOnClickListener {
            overlay.undoLast()
        }

        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            exportEdited()
        }

        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener {
            // נקודת חיבור לשליחה
            val p = outFilePath
            if (p.isNullOrEmpty()) {
                tvStatus.text = "צריך קודם לייצא וידאו ערוך"
                return@setOnClickListener
            }
            tvStatus.text = "שליחה לערוץ: $targetChannel (חיבור TDLib יתווסף בשלב הבא)"
            // TelegramSender.sendVideoToChannel(...)
        }

        tvStatus.text = "מצב: טשטוש פעיל (צייר מלבן) ואז ייצוא"
    }

    private fun exportEdited() {
        val uri = inputUri ?: return
        tvStatus.text = "מייצא... (FFmpeg)"
        Thread {
            try {
                val res = VideoEditPipeline.editVideoBlocking(
                    ctx = applicationContext,
                    input = uri,
                    blurRects = overlay.getBlurRectsNormalized(),
                    watermarkText = etWatermark.text?.toString()
                )
                outFilePath = res.outFile.absolutePath
                runOnUiThread {
                    tvStatus.text = "הייצוא הצליח ✅\n${res.outFile.name}\n${res.outFile.absolutePath}"
                    // load edited for preview
                    videoView.setVideoPath(res.outFile.absolutePath)
                    videoView.start()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    tvStatus.text = "הייצוא נכשל ❌\n${t.message}"
                }
            }
        }.start()
    }
}

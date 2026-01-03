package com.pasiflonet.mobile.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import com.pasiflonet.mobile.worker.SendWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DetailsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailsBinding
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(this)

        val chatId = intent.getLongExtra("src_chat_id", 0L)
        val messageId = intent.getLongExtra("src_message_id", 0L)
        val text = intent.getStringExtra("src_text") ?: ""
        val typeLabel = intent.getStringExtra("src_type") ?: ""

        if (chatId == 0L || messageId == 0L) {
            Toast.makeText(this, "שגיאה: פרטי הודעה חסרים", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        b.etMessage.setText(text)
        b.tvMeta.text = typeLabel

        b.btnSend.setOnClickListener {
            lifecycleScope.launch {
                val targetUsername = prefs.targetUsernameFlow.first()
                if (targetUsername.isBlank()) {
                    Snackbar.make(b.root, "חובה להגדיר ערוץ יעד כ-@username במסך ההתחברות", Snackbar.LENGTH_LONG).show()
                    return@launch
                }

                val wmUri = prefs.watermarkUriFlow.first()
                val sendWithMedia = b.rbWithMedia.isChecked
                val planJson = com.pasiflonet.mobile.util.JsonUtil.toJson(b.overlay.getPlan())

                val req = OneTimeWorkRequestBuilder<SendWorker>()
                    .setInputData(
                        workDataOf(
                            SendWorker.KEY_SRC_CHAT_ID to chatId,
                            SendWorker.KEY_SRC_MESSAGE_ID to messageId,
                            SendWorker.KEY_TARGET_USERNAME to targetUsername,
                            SendWorker.KEY_TEXT to (b.etMessage.text?.toString() ?: ""),
                            SendWorker.KEY_TRANSLATION to (b.etTranslation.text?.toString() ?: ""),
                            SendWorker.KEY_SEND_WITH_MEDIA to sendWithMedia,
                            SendWorker.KEY_WATERMARK_URI to wmUri,
                            SendWorker.KEY_EDIT_PLAN_JSON to planJson
                        )
                    )
                    .build()

                WorkManager.getInstance(this@DetailsActivity).enqueue(req)
                finish()
            }
        }
    }
}

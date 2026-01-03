package com.pasiflonet.mobile.ui

import android.os.Bundle
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import com.pasiflonet.mobile.worker.SendWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DetailsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailsBinding
    private lateinit var prefs: AppPrefs

    private fun firstId(names: List<String>): Int {
        for (n in names) {
            val id = resources.getIdentifier(n, "id", packageName)
            if (id != 0) return id
        }
        return 0
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> findByNames(names: List<String>): T? {
        val id = firstId(names)
        if (id == 0) return null
        return findViewById(id)
    }

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

        // מנסים למצוא את השדות לפי שמות נפוצים
        val etMessage = findByNames<TextInputEditText>(
            listOf("etMessage", "etMessageText", "etMsg", "messageEdit", "editMessage")
        )
        val tvMeta = findByNames<TextView>(
            listOf("tvMeta", "tvType", "tvHeader", "tvInfo", "tvMessageMeta")
        )
        val etTranslation = findByNames<TextInputEditText>(
            listOf("etTranslation", "etTranslate", "translationEdit", "editTranslation")
        )
        val rbWithMedia = findByNames<RadioButton>(
            listOf("rbWithMedia", "rbSendWithMedia", "rbMedia")
        )
        val btnSend = findByNames<MaterialButton>(
            listOf("btnSend", "btnSendMessage", "btnSendNow")
        )

        if (etMessage == null || tvMeta == null || rbWithMedia == null || btnSend == null) {
            Toast.makeText(this, "שגיאה: ה-UI במסך פרטים לא תואם (חסר id)", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        etMessage.setText(text)
        tvMeta.text = typeLabel

        btnSend.setOnClickListener {
            lifecycleScope.launch {
                val targetUsername = prefs.targetUsernameFlow.first()
                if (targetUsername.isBlank()) {
                    Toast.makeText(this@DetailsActivity, "חובה להגדיר ערוץ יעד כ-@username במסך ההתחברות", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val wmUri = prefs.watermarkUriFlow.first()
                val sendWithMedia = rbWithMedia.isChecked

                // אם יש overlay view אצלך בביינדינג – נשמור, אם לא – שולחים plan ריק
                val planJson = try { com.pasiflonet.mobile.util.JsonUtil.toJson(b.overlay.getPlan()) } catch (_: Throwable) { "{}" }

                val req = OneTimeWorkRequestBuilder<SendWorker>()
                    .setInputData(
                        workDataOf(
                            SendWorker.KEY_SRC_CHAT_ID to chatId,
                            SendWorker.KEY_SRC_MESSAGE_ID to messageId,
                            SendWorker.KEY_TARGET_USERNAME to targetUsername,
                            SendWorker.KEY_TEXT to (etMessage.text?.toString() ?: ""),
                            SendWorker.KEY_TRANSLATION to (etTranslation?.text?.toString() ?: ""),
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

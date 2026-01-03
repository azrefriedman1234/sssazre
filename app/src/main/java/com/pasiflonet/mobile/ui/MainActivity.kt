package com.pasiflonet.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: MessagesAdapter

    private val mediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // שמור רפרנס כדי שנוכל להסיר ב-onDestroy
    private val newMsgListener: (TdApi.Object) -> Unit = { obj ->
        if (obj !is TdApi.UpdateNewMessage) return@newMsgListener
        val msg = obj.message

        val from = when (val s = msg.senderId) {
            is TdApi.MessageSenderUser -> "user:${s.userId}"
            is TdApi.MessageSenderChat -> "chat:${s.chatId}"
            else -> "?"
        }

        val text = when (val c = msg.content) {
            is TdApi.MessageText -> c.text?.text.orEmpty()
            is TdApi.MessagePhoto -> c.caption?.text?.takeIf { it.isNotBlank() } ?: "[PHOTO]"
            is TdApi.MessageVideo -> c.caption?.text?.takeIf { it.isNotBlank() } ?: "[VIDEO]"
            is TdApi.MessageDocument -> c.caption?.text?.takeIf { it.isNotBlank() } ?: "[DOCUMENT]"
            else -> "[${c?.javaClass?.simpleName?.uppercase(Locale.ROOT) ?: "UNKNOWN"}]"
        }

        val ui = UiMsg(
            chatId = msg.chatId,
            msgId = msg.id,
            dateSec = msg.date,
            from = from,
            text = text
        )

        runOnUiThread { adapter.prepend(ui) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.btnSettings)
            .setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        requestMediaPermissions()

        recycler = findViewById(R.id.recycler)
        adapter = MessagesAdapter { m ->
            // נשאר עם ה-start הקיים אצלך (לא שובר)
            DetailsActivity.start(this, m.chatId, m.msgId, m.text)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // שורת בדיקה כדי לראות מיד שהטבלה עובדת
        adapter.prepend(
            UiMsg(
                chatId = 0L,
                msgId = 0L,
                dateSec = (System.currentTimeMillis() / 1000L).toInt(),
                from = "system",
                text = "מוכן. מחכה להודעות בלייב…"
            )
        )

        TdLibManager.init(this)
        TdLibManager.ensureClient()
        TdLibManager.addUpdateListener(newMsgListener)

        // אם לא READY → לוגין
        lifecycleScope.launch {
            TdLibManager.authState.collect { st ->
                if (st == null) return@collect
                if (st.constructor != TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { TdLibManager.removeUpdateListener(newMsgListener) }
    }

    private fun requestMediaPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val need = perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need) mediaPermLauncher.launch(perms.toTypedArray())
    }
}

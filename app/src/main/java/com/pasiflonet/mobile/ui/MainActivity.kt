package com.pasiflonet.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.util.TdThumb
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var btnClearTemp: Button
    private lateinit var btnExit: Button
    private lateinit var adapter: MessagesAdapter

    private val mediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    // IMPORTANT: label תקין כדי שלא "יתפרק" ויעצור קבלת עדכונים
    private val updateListener: (TdApi.Object) -> Unit = update@{ obj ->
        if (obj.constructor != TdApi.UpdateNewMessage.CONSTRUCTOR) return@update
        val up = obj as TdApi.UpdateNewMessage
        val msg = up.message

        val from = when (val s = msg.senderId) {
            is TdApi.MessageSenderUser -> "user:${s.userId}"
            is TdApi.MessageSenderChat -> "chat:${s.chatId}"
            else -> "?"
        }

        val quad = extractUiFields(msg)
        val ui = UiMsg(
            chatId = msg.chatId,
            msgId = msg.id,
            dateSec = msg.date,
            from = from,
            text = quad.text,
            hasMedia = quad.hasMedia,
            mediaMime = quad.mime,
            miniThumbB64 = quad.miniThumbB64
        )

        runOnUiThread { adapter.prepend(ui) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.btnSettings)
            .setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        requestMediaPermissionsIfFirstRun()

        TdLibManager.init(this)
        TdLibManager.ensureClient()
        TdLibManager.addUpdateListener(updateListener)
        TdLibManager.send(TdApi.GetAuthorizationState()) { }

        recycler = findViewById(R.id.recycler)
        btnClearTemp = findViewById(R.id.btnClearTemp)
        btnExit = findViewById(R.id.btnExit)

        adapter = MessagesAdapter { m ->
            DetailsActivity.start(
                ctx = this,
                chatId = m.chatId,
                msgId = m.msgId,
                text = m.text,
                mediaUri = null,
                mediaMime = m.mediaMime,
                miniThumbB64 = m.miniThumbB64,
                hasMediaHint = m.hasMedia
            )
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnExit.setOnClickListener { finish() }
        btnClearTemp.setOnClickListener { }

        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest
                if (st.constructor != TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                    return@collectLatest
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // זה החלק שגורם ללייב לא להפסיק
        TdLibManager.setOnline(true)
    }

    override fun onStop() {
        super.onStop()
        // אפשר להשאיר true תמיד, אבל זה בטוח להפוך לרקע כשאתה יוצא מהמסך
        TdLibManager.setOnline(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { TdLibManager.removeUpdateListener(updateListener) }
    }

    private fun requestMediaPermissionsIfFirstRun() {
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

    private data class Quad(val text: String, val hasMedia: Boolean, val mime: String?, val miniThumbB64: String?)

    private fun extractUiFields(msg: TdApi.Message): Quad {
        val c = msg.content ?: return Quad("(empty)", false, null, null)

        var text = when (c) {
            is TdApi.MessageText -> c.text?.text.orEmpty()
            is TdApi.MessagePhoto -> (c.caption?.text?.takeIf { it.isNotBlank() } ?: "[PHOTO]")
            is TdApi.MessageVideo -> (c.caption?.text?.takeIf { it.isNotBlank() } ?: "[VIDEO]")
            is TdApi.MessageAnimation -> (c.caption?.text?.takeIf { it.isNotBlank() } ?: "[ANIMATION]")
            is TdApi.MessageDocument -> (c.caption?.text?.takeIf { it.isNotBlank() } ?: "[DOCUMENT]")
            else -> "[${c.javaClass.simpleName.uppercase(Locale.ROOT)}]"
        }

        if (text.length > 2000) text = text.take(2000) + "…"

        val hasMedia = c.constructor != TdApi.MessageText.CONSTRUCTOR
        val mime: String? = when (c) {
            is TdApi.MessageVideo -> c.video?.mimeType
            is TdApi.MessageAnimation -> c.animation?.mimeType
            is TdApi.MessageDocument -> c.document?.mimeType
            is TdApi.MessagePhoto -> "image/jpeg"
            else -> null
        }

        val carrier: Any? = when (c) {
            is TdApi.MessagePhoto -> c.photo
            is TdApi.MessageVideo -> c.video
            is TdApi.MessageAnimation -> c.animation
            is TdApi.MessageDocument -> c.document
            else -> null
        }

        val miniThumbB64 = TdThumb.extractMiniThumbB64(carrier)
        return Quad(text, hasMedia, mime, miniThumbB64)
    }
}

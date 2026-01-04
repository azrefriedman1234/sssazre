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
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var btnClearTemp: Button
    private lateinit var btnExit: Button
    private lateinit var adapter: MessagesAdapter

    private val mediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val updateListener: (TdApi.Object) -> Unit = { obj ->
        if (obj is TdApi.UpdateNewMessage) {
            val msg = obj.message
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.btnSettings)
            .setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        requestMediaPermissionsIfNeeded()

        TdLibManager.init(this)
        TdLibManager.ensureClient()
        TdLibManager.addUpdateListener(updateListener)

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
                miniThumbB64 = m.miniThumbB64
            )
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnExit.setOnClickListener { finishAffinity() }

        btnClearTemp.setOnClickListener {
            val n = clearTempFiles()
            Snackbar.make(recycler, "🧹 נוקו $n קבצים זמניים", Snackbar.LENGTH_SHORT).show()
        }

        // אם לא READY -> לוגין
        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest
                if (st.constructor != TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { TdLibManager.removeUpdateListener(updateListener) }
    }

    private fun requestMediaPermissionsIfNeeded() {
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

    private fun clearTempFiles(): Int {
        val dir: File = cacheDir
        val prefixes = listOf("send_", "edit_", "wm_", "tg_", "thumb_")
        var cnt = 0
        dir.listFiles()?.forEach { f ->
            val name = f.name
            if (prefixes.any { name.startsWith(it) }) {
                if (runCatching { f.delete() }.getOrDefault(false)) cnt++
            }
        }
        return cnt
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

        // minithumb יגיע דרך intent אם קיים; פה נשמור null כדי לא לשבור
        return Quad(text, hasMedia, mime, null)
    }
}

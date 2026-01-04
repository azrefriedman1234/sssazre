package com.pasiflonet.mobile.ui

import com.pasiflonet.mobile.td.TdForegroundService

import com.pasiflonet.mobile.td.TdUpdateBus

import android.util.Log

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

    private val newMsgListener: (TdApi.Object) -> Unit = fun(obj: TdApi.Object) {
        val up = obj as? TdApi.UpdateNewMessage ?: return
        val msg = up.message

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
        // ✅ Live updates: NEVER stop while this screen is open
        lifecycleScope.launch {
            TdUpdateBus.updates.collect { obj ->
                if (obj is TdApi.UpdateNewMessage) {
                    val msg = obj.message
                    Log.d("MainActivity", "UpdateNewMessage chat=${msg.chatId} id=${msg.id}")

                    // NOTE: keep your existing extractUiFields(...) / adapter.prepend(...) logic if present
                    // If you already have a function that converts TdApi.Message -> UiMsg, call it here.
                    try {
                        val m2 = extractUiFields(msg)
                        val ui = UiMsg(
                            chatId = msg.chatId,
                            msgId = msg.id,
                            dateSec = msg.date,
                            from = m2.from,
                            text = m2.text,
                            hasMedia = m2.hasMedia,
                            mediaMime = m2.mime,
                            miniThumbB64 = m2.thumb
                        )
                        runOnUiThread { adapter.prepend(ui) }
                    } catch (_: Throwable) {
                        // if your project doesn't have extractUiFields returning these fields,
                        // this block won't compile — you can adapt below.
                    }
                }
            }
        }


    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.btnSettings)
            .setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        requestMediaPermissions()

        recycler = findViewById(R.id.recycler)
        adapter = MessagesAdapter { m ->
            DetailsActivity.start(this, m.chatId, m.msgId, m.text)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // שורת בדיקה שתראה שהטבלה עובדת
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
        
        TdForegroundService.start(this)
TdLibManager.addUpdateListener(newMsgListener)

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

    private fun cleanupCacheTemps(): Int {
        var deleted = 0
        val files = cacheDir.listFiles() ?: return 0
        for (f in files) {
            val n = f.name
            if (n.startsWith("tg_") || n.startsWith("send_") || n.startsWith("edit_") || n.startsWith("wm_")) {
                try { if (f.exists() && f.delete()) deleted++ } catch (_: Throwable) {}
            }
        }
        return deleted
    }


    // ---- Compat: provide extractUiFields expected by live-update collector ----
    private data class Extracted(
        val from: String,
        val text: String,
        val hasMedia: Boolean,
        val mime: String?,
        val thumb: String?
    )

    private fun extractUiFields(msg: TdApi.Message): Extracted {
        val from = when (val s = msg.senderId) {
            is TdApi.MessageSenderUser -> "user:${'$'}{s.userId}"
            is TdApi.MessageSenderChat -> "chat:${'$'}{s.chatId}"
            else -> "?"
        }

        val c = msg.content
        val hasMedia = c != null && c.constructor != TdApi.MessageText.CONSTRUCTOR

        val text = when (c) {
            is TdApi.MessageText -> c.text?.text.orEmpty()
            is TdApi.MessagePhoto -> c.caption?.text?.takeIf { it.isNotBlank() } ?: "[PHOTO]"
            is TdApi.MessageVideo -> c.caption?.text?.takeIf { it.isNotBlank() } ?: "[VIDEO]"
            is TdApi.MessageAnimation -> c.caption?.text?.takeIf { it.isNotBlank() } ?: "[ANIMATION]"
            is TdApi.MessageDocument -> c.caption?.text?.takeIf { it.isNotBlank() } ?: "[DOCUMENT]"
            else -> "[${'$'}{c?.javaClass?.simpleName ?: "UNKNOWN"}]"
        }.let { if (it.length > 2000) it.take(2000) + "…" else it }

        val mime: String? = when (c) {
            is TdApi.MessageVideo -> c.video?.mimeType
            is TdApi.MessageAnimation -> c.animation?.mimeType
            is TdApi.MessageDocument -> c.document?.mimeType
            is TdApi.MessagePhoto -> "image/jpeg"
            else -> null
        }

        // thumb best-effort without hard dependency (avoid compile issues)
        val carrier: Any? = when (c) {
            is TdApi.MessagePhoto -> c.photo
            is TdApi.MessageVideo -> c.video
            is TdApi.MessageAnimation -> c.animation
            is TdApi.MessageDocument -> c.document
            else -> null
        }

        val thumb: String? = runCatching {
            val cls = Class.forName("com.pasiflonet.mobile.util.TdThumb")
            val m = cls.getMethod("extractMiniThumbB64", Any::class.java)
            m.invoke(null, carrier) as? String
        }.getOrNull()

        return Extracted(from, text, hasMedia, mime, thumb)
    }
    // -----------------------------------------------------------------------

}

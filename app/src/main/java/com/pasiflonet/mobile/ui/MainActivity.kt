package com.pasiflonet.mobile.ui

import android.view.MenuItem
import com.pasiflonet.mobile.util.TdThumb
import android.view.Menu
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    

    private val mediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // לא חייבים לעשות כלום; רק נרצה שהמשתמש אישר פעם אחת
            getSharedPreferences("pf_prefs", MODE_PRIVATE).edit()
                .putBoolean("media_perm_asked", true).apply()
        }

    private fun requestMediaPermissionsIfFirstRun() {
        val sp = getSharedPreferences("pf_prefs", MODE_PRIVATE)
        if (sp.getBoolean("media_perm_asked", false)) return

        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        mediaPermLauncher.launch(perms)
    }

private lateinit var recycler: RecyclerView
    private lateinit var btnClearTemp: Button
    private lateinit var btnExit: Button

    private lateinit var adapter: MessagesAdapter
    private val startSec: Int by lazy { (System.currentTimeMillis() / 1000L).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        
        
        findViewById<android.view.View>(com.pasiflonet.mobile.R.id.btnSettings)
            .setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
requestMediaPermissionsIfFirstRun()
TdLibManager.init(this)
        TdLibManager.ensureClient()
        TdLibManager.send(TdApi.GetAuthorizationState()) { }

        recycler = findViewById(R.id.recycler)
        btnClearTemp = findViewById(R.id.btnClearTemp)
        btnExit = findViewById(R.id.btnExit)

        adapter = MessagesAdapter { m ->
            DetailsActivity.start(this, m.chatId, m.msgId, m.text, m.mediaUri, m.mediaMime, m.miniThumbB64)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnExit.setOnClickListener { finish() }

        btnClearTemp.setOnClickListener {
            // בכוונה: לא נוגעים ב־tdlib DB כדי שלא ינתק אותך
            // כאן אתה יכול למחוק רק קבצי temp של האפליקציה שלך
        }

        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest
                if (st.constructor != TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    // אם אין READY — הולכים ללוגין
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
            }
        }

        lifecycleScope.launch {
            TdLibManager.updatesFlow.collect { obj ->
                if (obj == null) return@collectLatest
                if (obj.constructor != TdApi.UpdateNewMessage.CONSTRUCTOR) return@collectLatest

                val up = obj as TdApi.UpdateNewMessage
                val msg = up.message ?: return@collectLatest

                // רק הודעות שנכנסו אחרי פתיחת האפליקציה
                if (msg.date < startSec) return@collectLatest

                val text = extractText(msg)
                if (text.isBlank()) return@collectLatest

                val ui = UiMsg(
                    chatId = msg.chatId,
                    msgId = msg.id,
                    dateSec = msg.date,
                    from = "src",
                    text = text,
                        miniThumbB64 = TdThumb.miniThumbB64(msg))
                adapter.prepend(ui)
            }
        }
    }

    private fun extractText(m: TdApi.Message): String {
        val c = m.content ?: return ""
        return when (c.constructor) {
            TdApi.MessageText.CONSTRUCTOR -> (c as TdApi.MessageText).text?.text ?: ""
            TdApi.MessagePhoto.CONSTRUCTOR -> (c as TdApi.MessagePhoto).caption?.text ?: "📷 Photo"
            TdApi.MessageVideo.CONSTRUCTOR -> (c as TdApi.MessageVideo).caption?.text ?: "🎬 Video"
            TdApi.MessageDocument.CONSTRUCTOR -> (c as TdApi.MessageDocument).caption?.text ?: "📎 Document"
            else -> ""
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(com.pasiflonet.mobile.R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            com.pasiflonet.mobile.R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

}

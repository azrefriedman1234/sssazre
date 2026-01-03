package com.pasiflonet.mobile.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: MessagesAdapter
    private lateinit var btnSettings: Button
    private lateinit var btnExit: Button
    private lateinit var btnClearTemp: Button

    private val mediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recycler)
        btnSettings = findViewById(R.id.btnSettings)
        btnExit = findViewById(R.id.btnExit)
        btnClearTemp = findViewById(R.id.btnClearTemp)

        adapter = MessagesAdapter { m ->
            // פתיחת מסך פרטים (כבר קיים אצלך)
            DetailsActivity.start(this, m.chatId, m.msgId, m.text)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnExit.setOnClickListener { finish() }
        btnClearTemp.setOnClickListener {
            // לא נוגעים ב־TDLib DB כדי לא לנתק אותך.
            // כאן אפשר למחוק רק קבצי temp של האפליקציה אם תרצה להוסיף.
        }

        requestMediaPermissionsIfFirstRun()

        // TDLib
        TdLibManager.init(this)
        TdLibManager.ensureClient()
        TdLibManager.send(TdApi.GetAuthorizationState()) { }

        // 1) אם לא READY — הולכים ללוגין
        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest
                if (st.constructor != TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                    return@collectLatest
                }
                // READY -> נשארים במסך הראשי
            }
        }

        // 2) הודעות נכנסות -> טבלה
        lifecycleScope.launch {
            TdLibManager.updatesFlow.collectLatest { obj ->
                if (obj.constructor != TdApi.UpdateNewMessage.CONSTRUCTOR) return@collectLatest
                val up = obj as TdApi.UpdateNewMessage
                val ui = toUiMsg(up.message)
                adapter.prepend(ui)
                recycler.scrollToPosition(0)
            }
        }
    }

    private fun toUiMsg(msg: TdApi.Message): UiMsg {
        val from = when (val s = msg.senderId) {
            is TdApi.MessageSenderUser -> "user:" + s.userId.toString()
            is TdApi.MessageSenderChat -> "chat:" + s.chatId.toString()
            else -> "?"
        }

        val text = when (val c = msg.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> "📷 " + c.caption.text
            is TdApi.MessageVideo -> "🎬 " + c.caption.text
            is TdApi.MessageDocument -> "📎 " + c.caption.text
            else -> "• " + (c.javaClass.simpleName ?: "content")
        }

        return UiMsg(
            chatId = msg.chatId,
            msgId = msg.id,
            dateSec = msg.date,
            from = from,
            text = text
        )
    }

    private fun requestMediaPermissionsIfFirstRun() {
        val sp = getSharedPreferences("pasiflonet", MODE_PRIVATE)
        if (sp.getBoolean("media_perm_asked", false)) return
        sp.edit().putBoolean("media_perm_asked", true).apply()

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        mediaPermLauncher.launch(perms.toTypedArray())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // תפריט מינימלי בלי XML כדי לא לשבור קומפילציה
        menu.add(0, 1, 0, "הגדרות")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

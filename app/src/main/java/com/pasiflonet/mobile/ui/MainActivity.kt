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
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityMainBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.util.TempCleaner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: MessagesAdapter
    private lateinit var prefs: AppPrefs

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val ok = res.values.all { it }
            Snackbar.make(
                b.root,
                if (ok) "✅ ניתנה הרשאה למדיה" else "⚠️ בלי הרשאה למדיה חלק מהפעולות לא יעבדו",
                Snackbar.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(this)
        TdLibManager.init(applicationContext)

        // בקשת הרשאה פעם ראשונה בלבד
        lifecycleScope.launch {
            val asked = prefs.mediaPermsAskedFlow.first()
            if (!asked) {
                prefs.setMediaPermsAsked(true)
                requestMediaPermissionsIfNeeded()
            }
        }

        adapter = MessagesAdapter { row ->
            val i = Intent(this, DetailsActivity::class.java).apply {
                putExtra("src_chat_id", row.chatId)
                putExtra("src_message_id", row.messageId)
                putExtra("src_text", row.text)
                putExtra("src_type", row.typeLabel)
            }
            startActivity(i)
        }
        b.recycler.adapter = adapter

        // אם לא מחובר -> Login
        lifecycleScope.launch {
            val st = TdLibManager.authState.first()
            if (st == null || st.constructor != TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
                return@launch
            }
        }

        // ניקוי זמני בלי למחוק session של TDLib
        b.btnClearTemp.setOnClickListener {
            val n = TempCleaner.clean(applicationContext)
            Snackbar.make(b.root, "✅ נמחקו קבצים זמניים: $n", Snackbar.LENGTH_SHORT).show()
        }

        b.btnExit.setOnClickListener { finishAffinity() }
    }

    private fun requestMediaPermissionsIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
        }
    }
}

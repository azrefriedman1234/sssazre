package com.pasiflonet.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityLoginBinding
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var prefs: AppPrefs

    private var watermarkUri: String = ""

    private val pickWatermark = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            watermarkUri = uri.toString()
            b.tvWatermarkStatus.text = "✅ נטען סימן מים"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(this)

        // TDLib init
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()
        TdLibManager.send(TdApi.GetAuthorizationState()) { }

        // אם כבר READY -> ישר לטבלה (לא להציג שוב התחברות)
        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st != null && st.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            }
        }

        // UI initial
        showInitialUi()

        b.btnPickWatermark.setOnClickListener {
            pickWatermark.launch("image/*")
        }

        b.btnSendCode.setOnClickListener {
            val apiIdStr = b.etApiId.text?.toString()?.trim().orEmpty()
            val apiHash = b.etApiHash.text?.toString()?.trim().orEmpty()
            val phone = b.etPhone.text?.toString()?.trim().orEmpty()
            val targetUsername = b.etTargetUsername.text?.toString()?.trim().orEmpty()

            if (apiIdStr.isBlank() || apiHash.isBlank() || phone.isBlank() || targetUsername.isBlank()) {
                Snackbar.make(b.root, "חובה למלא API ID / API HASH / טלפון / ערוץ יעד (@username)", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!targetUsername.startsWith("@")) {
                Snackbar.make(b.root, "ערוץ יעד חייב להתחיל ב-@", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val apiId = apiIdStr.toIntOrNull()
            if (apiId == null) {
                Snackbar.make(b.root, "API ID חייב להיות מספר", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                // שמירה (suspend) בתוך coroutine
                prefs.saveApiId(apiIdStr)
                prefs.saveApiHash(apiHash)
                prefs.savePhone(phone)
                prefs.saveTargetUsername(targetUsername)
                if (watermarkUri.isNotBlank()) prefs.saveWatermark(watermarkUri)

                // הגדרת TDLib + שליחת קוד
                TdLibManager.send(
                    TdApi.SetTdlibParameters(
                        false,
                        filesDir.absolutePath + "/tdlib",
                        filesDir.absolutePath + "/tdlib",
                        ByteArray(0),
                        false, false, false, false,
                        apiId,
                        apiHash,
                        "he",
                        android.os.Build.MODEL ?: "Android",
                        android.os.Build.VERSION.RELEASE ?: "0",
                        "1.0"
                    ),
                        false, false, false, false,
                        apiId,
                        apiHash,
                        "Android",
                        android.os.Build.MODEL ?: "Android",
                        android.os.Build.VERSION.RELEASE ?: "0",
                        "1.0",
                        true
                    )
                ) { }

                TdLibManager.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { }

                showCodeUi()
                Snackbar.make(b.root, "✅ קוד אימות נשלח", Snackbar.LENGTH_SHORT).show()
            }
        }

        b.btnLogin.setOnClickListener {
            val code = b.etCode.text?.toString()?.trim().orEmpty()
            val pass = b.etPassword.text?.toString()?.trim().orEmpty()

            if (code.isBlank()) {
                Snackbar.make(b.root, "חובה להזין קוד אימות", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                TdLibManager.send(TdApi.CheckAuthenticationCode(code)) { }
                if (pass.isNotBlank()) {
                    TdLibManager.send(TdApi.CheckAuthenticationPassword(pass)) { }
                }
                Snackbar.make(b.root, "מתחבר…", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showInitialUi() {
        b.boxCode.visibility = View.GONE
        b.btnLogin.visibility = View.GONE
    }

    private fun showCodeUi() {
        b.boxCode.visibility = View.VISIBLE
        b.btnLogin.visibility = View.VISIBLE
    }
}

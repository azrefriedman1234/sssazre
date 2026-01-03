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

        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        // ✅ בכוונה: תמיד להציג אזור קוד + כפתור התחברות כדי שלא "ייעלם"
        b.boxCode.visibility = View.VISIBLE
        b.btnLogin.visibility = View.VISIBLE
        b.btnLogin.text = "התחברות"

        // אם כבר מחובר – לעבור ישר לטבלה
        lifecycleScope.launch {
            TdLibManager.send(TdApi.GetAuthorizationState()) { _ -> }
            TdLibManager.authState.collectLatest { st ->
                if (st != null && st.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            }
        }

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

            val apiIdInt = apiIdStr.toIntOrNull()
            if (apiIdInt == null) {
                Snackbar.make(b.root, "API ID חייב להיות מספר", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                // שמירה ל-AppPrefs (apiId נשמר כ-String אצלך)
                prefs.saveApiId(apiIdStr)
                prefs.saveApiHash(apiHash)
                prefs.savePhone(phone)
                prefs.saveTargetUsername(targetUsername)
                if (watermarkUri.isNotBlank()) prefs.saveWatermark(watermarkUri)

                // חתימה נכונה ל-TDLib 1.8.56 (14 פרמטרים)
                val params = TdApi.SetTdlibParameters(
                    false,
                    filesDir.absolutePath + "/tdlib",
                    filesDir.absolutePath + "/tdlib",
                    ByteArray(0),
                    false, false, false, false,
                    apiIdInt,
                    apiHash,
                    "he",
                    android.os.Build.MODEL ?: "Android",
                    android.os.Build.VERSION.RELEASE ?: "0",
                    "1.0"
                )

                TdLibManager.send(params) { _ -> }
                TdLibManager.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { _ -> }

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
                TdLibManager.send(TdApi.CheckAuthenticationCode(code)) { _ -> }
                if (pass.isNotBlank()) {
                    TdLibManager.send(TdApi.CheckAuthenticationPassword(pass)) { _ -> }
                }
                Snackbar.make(b.root, "מתחבר…", Snackbar.LENGTH_SHORT).show()
            }
        }
    }
}

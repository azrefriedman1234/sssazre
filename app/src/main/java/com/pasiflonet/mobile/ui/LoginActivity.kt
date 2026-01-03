package com.pasiflonet.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityLoginBinding
import com.pasiflonet.mobile.td.TdAuthController
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var prefs: AppPrefs
    private lateinit var auth: TdAuthController

    private val pickWatermark = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                prefs.saveWatermarkUri(uri.toString())
                Snackbar.make(b.root, "✅ סימן מים נשמר", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(this)
        TdLibManager.init(applicationContext)
        auth = TdAuthController(this, prefs, lifecycleScope)

        // Prefill
        lifecycleScope.launch {
            b.etApiId.setText(prefs.apiIdFlow.first())
            b.etApiHash.setText(prefs.apiHashFlow.first())
            b.etPhone.setText(prefs.phoneFlow.first())
            val tu = prefs.targetUsernameFlow.first()
            b.etTargetChatId.setText(if (tu.isNotBlank()) "@$tu" else "")
        }

        // אם כבר מחובר - קופץ ישר לראשי
        lifecycleScope.launch {
            TdLibManager.authState.collect { st ->
                updateUiByAuthState(st)
                if (st != null && st.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    prefs.setLoggedIn(true)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            }
        }

        auth.start()

        b.btnPickWatermark.setOnClickListener { pickWatermark.launch("image/*") }

        b.btnSaveSettings.setOnClickListener {
            lifecycleScope.launch {
                val apiId = b.etApiId.text?.toString()?.trim().orEmpty()
                val apiHash = b.etApiHash.text?.toString()?.trim().orEmpty()
                val phone = b.etPhone.text?.toString()?.trim().orEmpty()
                val targetUserRaw = b.etTargetChatId.text?.toString()?.trim().orEmpty()

                prefs.saveApiId(apiId)
                prefs.saveApiHash(apiHash)
                prefs.savePhone(phone)
                prefs.saveTargetUsername(cleanUsername(targetUserRaw))

                Snackbar.make(b.root, "✅ הגדרות נשמרו", Snackbar.LENGTH_SHORT).show()
            }
        }

        b.btnSendCode.setOnClickListener {
            lifecycleScope.launch {
                val apiIdStr = b.etApiId.text?.toString()?.trim().orEmpty()
                val apiHash = b.etApiHash.text?.toString()?.trim().orEmpty()
                val phone = b.etPhone.text?.toString()?.trim().orEmpty()

                val apiId = apiIdStr.toIntOrNull()
                if (apiId == null || apiHash.isBlank() || phone.isBlank()) {
                    Snackbar.make(b.root, "חובה למלא API ID, API HASH ומספר טלפון", Snackbar.LENGTH_LONG).show()
                    return@launch
                }

                // שמירה
                prefs.saveApiId(apiIdStr)
                prefs.saveApiHash(apiHash)
                prefs.savePhone(phone)

                // הגדרת TDLib + שליחת קוד
                auth.setTdLibParameters(apiId, apiHash)
                auth.setPhoneNumber(phone)

                Snackbar.make(b.root, "📨 נשלח קוד אימות", Snackbar.LENGTH_SHORT).show()
            }
        }

        // כפתור “התחבר / אמת” אחרי הזנת קוד (וגם 2FA אם צריך)
        b.btnLogin.setOnClickListener {
            val code = b.etCode.text?.toString()?.trim().orEmpty()
            val pass = b.etTwoFa.text?.toString()?.trim().orEmpty()

            if (code.isNotBlank()) auth.checkCode(code)
            if (pass.isNotBlank()) auth.checkPassword(pass)

            if (code.isBlank() && pass.isBlank()) {
                Snackbar.make(b.root, "הכנס קוד או סיסמת 2FA ואז לחץ התחבר", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUiByAuthState(st: TdApi.AuthorizationState?) {
        val text = when (st?.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> "מצב: הזן API ואז שלח קוד"
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> "מצב: שלח מספר טלפון"
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> "מצב: הזן קוד ולחץ התחבר"
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> "מצב: הזן סיסמת 2FA ולחץ התחבר"
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> "מצב: מחובר ✅"
            else -> "מצב: ממתין…"
        }
        b.tvStatus.text = text
    }

    private fun cleanUsername(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("https://t.me/").removePrefix("http://t.me/").removePrefix("t.me/")
        if (s.startsWith("@")) s = s.substring(1)
        return s.trim()
    }
}

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
import com.pasiflonet.mobile.td.TdAuthController
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var prefs: AppPrefs
    private lateinit var auth: TdAuthController
    private var watermarkUri: Uri? = null

    private val pickWatermark = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            watermarkUri = uri
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            Snackbar.make(b.root, "✅ סימן מים נבחר", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(this)
        auth = TdAuthController(this, prefs, lifecycleScope)

        lifecycleScope.launch {
            // preload saved values
            val apiId = prefs.apiIdFlow.first()
            val apiHash = prefs.apiHashFlow.first()
            val phone = prefs.phoneFlow.first()
            val targetChat = prefs.targetChatIdFlow.first()
            val wm = prefs.watermarkUriFlow.first()
            if (apiId != 0) b.etApiId.setText(apiId.toString())
            if (apiHash.isNotBlank()) b.etApiHash.setText(apiHash)
            if (phone.isNotBlank()) b.etPhone.setText(phone)
            if (targetChat != 0L) b.etTargetUsername.setText(targetChat.toString())
            if (wm.isNotBlank()) watermarkUri = Uri.parse(wm)
        }

        b.btnPickWatermark.setOnClickListener {
            pickWatermark.launch("image/*")
        }

        b.btnSaveSettings.setOnClickListener {
            lifecycleScope.launch {
                val apiId = b.etApiId.text?.toString()?.trim()?.toIntOrNull() ?: 0
                val apiHash = b.etApiHash.text?.toString()?.trim().orEmpty()
                val phone = b.etPhone.text?.toString()?.trim().orEmpty()
                val target = b.etTargetUsername.text?.toString()?.trim()?.toLongOrNull() ?: 0L

                if (apiId == 0 || apiHash.isBlank() || phone.isBlank()) {
                    Snackbar.make(b.root, "חובה למלא API ID / HASH ומספר טלפון", Snackbar.LENGTH_LONG).show()
                    return@launch
                }
                prefs.saveApi(apiId, apiHash, phone)
                prefs.saveTargetChatId(target)
                prefs.saveWatermark(watermarkUri)
                Snackbar.make(b.root, "✅ נשמר", Snackbar.LENGTH_SHORT).show()
            }
        }

        b.btnSendCode.setOnClickListener {
            val apiId = b.etApiId.text?.toString()?.trim()?.toIntOrNull() ?: 0
            val apiHash = b.etApiHash.text?.toString()?.trim().orEmpty()
            val phone = b.etPhone.text?.toString()?.trim().orEmpty()
            if (apiId == 0 || apiHash.isBlank() || phone.isBlank()) {
                Snackbar.make(b.root, "חובה למלא API ID / HASH ומספר טלפון", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            auth.setTdLibParameters(apiId, apiHash)
            auth.setPhoneNumber(phone)
        }

        b.btnLogin.setOnClickListener {
            val code = b.etCode.text?.toString()?.trim().orEmpty()
            val pass = b.etTwoFa.text?.toString()?.trim().orEmpty()
            if (code.isNotBlank()) auth.checkCode(code)
            if (pass.isNotBlank()) auth.checkPassword(pass)
        }

        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest
                updateUiForState(st)
                if (st.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    prefs.setLoggedIn(true)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            }
        }

        auth.start()
    }

    private fun updateUiForState(st: TdApi.AuthorizationState) {
        b.progress.visibility = View.GONE
        val msg = when (st.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> "ממתין להגדרות TDLib..."
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> "ממתין למספר טלפון..."
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> "נשלח קוד. הזן קוד אימות."
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> "נדרש אימות דו־שלבי. הזן סיסמה."
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> "✅ מחובר!"
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> "מתנתק..."
            TdApi.AuthorizationStateClosing.CONSTRUCTOR -> "סוגר..."
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> "סגור."
            else -> "מצב: ${st.javaClass.simpleName}"
        }
        b.tvStatus.text = msg
    }
}

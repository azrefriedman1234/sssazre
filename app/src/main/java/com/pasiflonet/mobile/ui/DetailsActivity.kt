package com.pasiflonet.mobile.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.ui.editor.OverlayEditorView
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.InputStream

class DetailsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailsBinding
    private lateinit var prefs: AppPrefs

    private var chatId: Long = 0L
    private var messageId: Long = 0L
    private var hasMedia: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(this)

        // מגיעים מהטבלה
        chatId = intent.getLongExtra("chatId", 0L)
        messageId = intent.getLongExtra("messageId", 0L)
        val meta = intent.getStringExtra("meta") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val miniThumbB64 = intent.getStringExtra("miniThumbB64") ?: ""
        hasMedia = intent.getBooleanExtra("hasMedia", false)

        b.tvMeta.text = meta
        b.etMessage.setText(text)

        // Preview: miniThumbnail (base64) אם קיים
        if (miniThumbB64.isNotBlank()) {
            try {
                val bytes = android.util.Base64.decode(miniThumbB64, android.util.Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                b.editorView.setImageBitmap(bmp)
            } catch (_: Throwable) { }
        }

        // כפתור הוסף לוגו: טוען watermark מההגדרות ומפעיל מצב WATERMARK
        b.btnWatermark.setOnClickListener {
            val wm = prefs.getWatermark()
            if (wm.isNullOrBlank()) {
                Snackbar.make(b.root, "לא נטען סימן מים בהגדרות", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val bmp = loadBitmapFromUriString(wm)
            if (bmp == null) {
                Snackbar.make(b.root, "לא הצלחתי לפתוח את סימן המים", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            b.editorView.setWatermarkBitmap(bmp)
            b.editorView.setMode(OverlayEditorView.Mode.WATERMARK)
            Snackbar.make(b.root, "גע בתמונה כדי למקם את הלוגו", Snackbar.LENGTH_SHORT).show()
        }

        // מצב טשטוש: גרור כדי ליצור מלבן טשטוש
        b.btnBlur.setOnClickListener {
            b.editorView.setMode(OverlayEditorView.Mode.BLUR)
            Snackbar.make(b.root, "גרור על התמונה כדי לסמן אזור טשטוש", Snackbar.LENGTH_SHORT).show()
        }

        b.btnClearEdits.setOnClickListener {
            b.editorView.clearEdits()
            Snackbar.make(b.root, "✅ נוקו עריכות", Snackbar.LENGTH_SHORT).show()
        }

        // תרגום אוטומטי בחינם (on-device)
        b.btnTranslate.setOnClickListener { autoTranslateIfNeeded(force = true) }
        autoTranslateIfNeeded(force = false)

        // שליחה (כרגע: טקסט בלבד בצורה אמיתית עם בדיקת TdApi.Error)
        // מדיה + עריכות (watermark/blur) נחבר לשלב FFmpeg/קובץ בפועל בהמשך.
        b.btnSend.setOnClickListener {
            val target = prefs.getTargetUsername()?.trim().orEmpty()
            if (target.isBlank() || !target.startsWith("@")) {
                Snackbar.make(b.root, "חובה להגדיר ערוץ יעד בפורמט @username", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val messageText = b.etMessage.text?.toString().orEmpty()
            if (messageText.isBlank() && !b.swSendWithMedia.isChecked) {
                Snackbar.make(b.root, "אין מה לשלוח", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                // resolve @username -> chatId
                TdLibManager.send(TdApi.SearchPublicChat(target.removePrefix("@"))) { obj ->
                    if (obj is TdApi.Error) {
                        runOnUiThread {
                            Snackbar.make(b.root, "שגיאה ביעד: ${obj.code} ${obj.message}", Snackbar.LENGTH_LONG).show()
                        }
                        return@send
                    }
                    val chat = obj as TdApi.Chat
                    val input = TdApi.InputMessageText(
                        TdApi.FormattedText(messageText, null),
                        null,
                        false
                    )

                    // שליחה אמיתית עם בדיקת Error/Message
                    TdLibManager.send(TdApi.SendMessage(chat.id, 0L, null, null, input)) { res ->
                        runOnUiThread {
                            when (res) {
                                is TdApi.Error -> Snackbar.make(b.root, "❌ לא נשלח: ${res.code} ${res.message}", Snackbar.LENGTH_LONG).show()
                                is TdApi.Message -> {
                                    Snackbar.make(b.root, "✅ נשלח בהצלחה (ID ${res.id})", Snackbar.LENGTH_LONG).show()
                                    finish()
                                }
                                else -> Snackbar.make(b.root, "⚠️ תשובה לא צפויה מהשרת", Snackbar.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun autoTranslateIfNeeded(force: Boolean) {
        val src = b.etMessage.text?.toString()?.trim().orEmpty()
        if (src.isBlank()) return
        if (!force && b.etTranslation.text?.toString()?.isNotBlank() == true) return

        val langId = LanguageIdentification.getClient()
        langId.identifyLanguage(src).addOnSuccessListener { lang ->
            // אם זה עברית או לא מזוהה – לא מתרגמים
            if (lang == "he" || lang == "iw" || lang == "und") return@addOnSuccessListener

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.fromLanguageTag(lang) ?: TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.HEBREW)
                .build()
            val translator = Translation.getClient(options)

            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    translator.translate(src)
                        .addOnSuccessListener { out -> b.etTranslation.setText(out) }
                        .addOnFailureListener { e ->
                            Snackbar.make(b.root, "שגיאת תרגום: ${e.message}", Snackbar.LENGTH_LONG).show()
                        }
                }
                .addOnFailureListener { e ->
                    Snackbar.make(b.root, "לא הצלחתי להוריד מודל תרגום: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
        }
    }

    private fun loadBitmapFromUriString(uriStr: String): android.graphics.Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            val ins: InputStream? = contentResolver.openInputStream(uri)
            val bmp = ins.use { it?.let { stream -> BitmapFactory.decodeStream(stream) } }
            bmp
        } catch (_: Throwable) { null }
    }
}

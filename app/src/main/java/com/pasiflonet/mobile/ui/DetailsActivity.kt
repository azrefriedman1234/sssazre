package com.pasiflonet.mobile.ui

import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import com.pasiflonet.mobile.util.OnDeviceTranslate
import com.pasiflonet.mobile.util.Thumbs

class DetailsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailsBinding
    private var blurMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        // meta/text מגיעים מה-Intent (מהטבלה)
        val meta = intent.getStringExtra("meta") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val miniThumbB64 = intent.getStringExtra("miniThumbB64")

        b.tvMeta.text = meta
        b.etCaption.setText(text)

        // Preview: גם אם הקובץ עדיין לא ירד - מציגים miniThumb
        val bmp: Bitmap? = Thumbs.b64ToBitmap(miniThumbB64)
        if (bmp != null) b.imgPreview.setImageBitmap(bmp)

        // Blur button: מצב ציור מלבנים
        b.btnBlur.setOnClickListener {
            blurMode = !blurMode
            b.overlay.enabledDraw = blurMode
            val msg = if (blurMode) "✅ מצב טשטוש פעיל: גרור מלבנים על ה-Preview" else "✅ מצב טשטוש כבוי"
            Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
        }

        // Translate (חינם on-device)
        b.btnTranslate.setOnClickListener {
            val src = b.etCaption.text?.toString().orEmpty()
            Snackbar.make(b.root, "⏳ מתרגם… (on-device)", Snackbar.LENGTH_SHORT).show()
            OnDeviceTranslate.toHebrew(src) { out, err ->
                runOnUiThread {
                    if (out != null) {
                        b.etCaption.setText(out)
                        Snackbar.make(b.root, "✅ תורגם לעברית", Snackbar.LENGTH_SHORT).show()
                    } else {
                        Snackbar.make(b.root, "❌ תרגום נכשל: ${err ?: "unknown"}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Watermark: כרגע רק UI (הפעלת editor בהמשך ב-ffmpeg). בינתיים מראה שנקלט.
        b.btnWatermark.setOnClickListener {
            Snackbar.make(b.root, "✅ לוגו/סימן מים: בשלב הבא נצרוב למדיה לפני שליחה", Snackbar.LENGTH_SHORT).show()
        }

        // Send: בשלב הזה רק מאשר UI (השליחה האמיתית + ffmpeg נכניס בפקודה הבאה כדי לא לשבור קומפילציה)
        b.btnSend.setOnClickListener {
            Snackbar.make(b.root, "✅ לחצת שלח. עכשיו נוסיף שליחה אמיתית + צריבת blur/watermark בפקודה הבאה.", Snackbar.LENGTH_LONG).show()
        }
    }
}

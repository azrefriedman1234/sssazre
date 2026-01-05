package com.pasiflonet.mobile.util

import android.content.Context
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object OnDeviceTranslate {

    /**
     * Offline translate ANY language -> Hebrew using ML Kit on-device models.
     * First detects language (offline). Then downloads the needed translation model if missing.
     * Returns null on failure/timeout.
     */
    fun translateToHebrewBlocking(ctx: Context, text: String, timeoutMs: Long = 20000L): String? {
        val src = text.trim()
        if (src.isEmpty()) return ""

        val detected = detectLangBlocking(src, timeoutMs = timeoutMs) ?: "en"
        val sourceLang = normalizeToMlKit(detected) ?: TranslateLanguage.ENGLISH

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(TranslateLanguage.HEBREW)
            .build()

        val translator = Translation.getClient(options)

        try {
            // Ensure model exists (downloads once, then works offline)
            val dlLatch = CountDownLatch(1)
            var dlOk = false
            var dlErr: Throwable? = null

            translator.downloadModelIfNeeded()
                .addOnSuccessListener { dlOk = true; dlLatch.countDown() }
                .addOnFailureListener { e -> dlErr = e; dlLatch.countDown() }

            if (!dlLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return null
            }
            if (!dlOk) return null

            val trLatch = CountDownLatch(1)
            var out: String? = null
            translator.translate(src)
                .addOnSuccessListener { t -> out = t; trLatch.countDown() }
                .addOnFailureListener { trLatch.countDown() }

            if (!trLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
            return out
        } finally {
            try { translator.close() } catch (_: Throwable) {}
        }
    }

    private fun detectLangBlocking(text: String, timeoutMs: Long): String? {
        val client = LanguageIdentification.getClient()
        val latch = CountDownLatch(1)
        var lang: String? = null
        client.identifyLanguage(text)
            .addOnSuccessListener { code ->
                // code can be "und"
                lang = if (code == "und") null else code
                latch.countDown()
            }
            .addOnFailureListener {
                latch.countDown()
            }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return lang
    }

    private fun normalizeToMlKit(code: String): String? {
        // ML Kit expects BCP-47-ish / TranslateLanguage constants.
        // We just try map; fallback to null.
        return try {
            TranslateLanguage.fromLanguageTag(code)
        } catch (_: Throwable) {
            null
        }
    }
}

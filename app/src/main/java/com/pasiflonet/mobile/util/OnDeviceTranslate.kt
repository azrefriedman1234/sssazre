package com.pasiflonet.mobile.util

import android.content.Context
import android.os.SystemClock

/**
 * Offline translation helper.
 * Uses reflection so the project COMPILES even if ML Kit translate dependency changes.
 * If ML Kit translate is present, it will try to download model (on device) and translate.
 * If not present or fails, returns null.
 */
object OnDeviceTranslate {

    fun translateToHebrewBlocking(ctx: Context, text: String, timeoutMs: Long = 20000L): String? {
        val src = text.trim()
        if (src.isEmpty()) return ""

        return try {
            // ML Kit translate (optional). Reflection avoids hard dependency compile issues.
            val langCls = Class.forName("com.google.mlkit.nl.translate.TranslateLanguage")
            val translationCls = Class.forName("com.google.mlkit.nl.translate.Translation")
            val optionsBuilderCls = Class.forName("com.google.mlkit.nl.translate.TranslatorOptions\$Builder")
            val conditionsCls = Class.forName("com.google.mlkit.common.model.DownloadConditions\$Builder")

            val heb = langCls.getMethod("HEBREW").invoke(null) as String

            // detect source language if possible; fallback to ENGLISH
            val eng = langCls.getMethod("ENGLISH").invoke(null) as String

            val optBuilder = optionsBuilderCls.getConstructor().newInstance()
            optionsBuilderCls.getMethod("setSourceLanguage", String::class.java).invoke(optBuilder, eng)
            optionsBuilderCls.getMethod("setTargetLanguage", String::class.java).invoke(optBuilder, heb)
            val options = optionsBuilderCls.getMethod("build").invoke(optBuilder)

            val translator = translationCls.getMethod("getClient", Class.forName("com.google.mlkit.nl.translate.TranslatorOptions"))
                .invoke(null, options)

            // download model if needed
            val condBuilder = conditionsCls.getConstructor().newInstance()
            val conditions = conditionsCls.getMethod("build").invoke(condBuilder)

            val downloadTask = translator.javaClass.getMethod("downloadModelIfNeeded", Class.forName("com.google.mlkit.common.model.DownloadConditions"))
                .invoke(translator, conditions)

            // await download
            awaitTask(downloadTask, timeoutMs)

            val translateTask = translator.javaClass.getMethod("translate", String::class.java).invoke(translator, src)
            val out = awaitTask(translateTask, timeoutMs) as? String

            // close if possible
            try { translator.javaClass.getMethod("close").invoke(translator) } catch (_: Throwable) {}

            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun awaitTask(taskObj: Any, timeoutMs: Long): Any? {
        val taskCls = Class.forName("com.google.android.gms.tasks.Task")
        if (!taskCls.isInstance(taskObj)) return null

        val isComplete = taskCls.getMethod("isComplete")
        val isSuccessful = taskCls.getMethod("isSuccessful")
        val getResult = taskCls.getMethod("getResult")
        val getException = taskCls.getMethod("getException")

        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            val done = isComplete.invoke(taskObj) as Boolean
            if (done) {
                val ok = isSuccessful.invoke(taskObj) as Boolean
                if (ok) return getResult.invoke(taskObj)
                val ex = getException.invoke(taskObj) as? Throwable
                throw ex ?: RuntimeException("Task failed")
            }
            Thread.sleep(50)
        }
        throw RuntimeException("Task timeout")
    }
}

package com.pasiflonet.mobile.util

import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * תרגום אונליין "חינמי" ללא מפתח (endpoint ציבורי של Google Translate web).
 * אם נחסם/נכשל - מחזיר את הטקסט המקורי.
 */
fun translateToHebrewCompat(src: String, timeoutMs: Int = 15000): String {
    val s = src.trim()
    if (s.isBlank()) return s
    return try {
        val q = URLEncoder.encode(s, "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=iw&dt=t&q=$q")
        val con = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val body = con.inputStream.bufferedReader().readText()
        val arr = JSONArray(body)
        val parts = arr.getJSONArray(0)
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val seg = parts.getJSONArray(i).optString(0, "")
            out.append(seg)
        }
        out.toString().trim().ifBlank { s }
    } catch (t: Throwable) {
        Log.w("Compat", "translateToHebrewCompat failed: ${t.message}")
        s
    }
}

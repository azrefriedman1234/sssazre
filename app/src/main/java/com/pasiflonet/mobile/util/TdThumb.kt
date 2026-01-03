package com.pasiflonet.mobile.util

import android.util.Base64
import org.drinkless.tdlib.TdApi

object TdThumb {

    fun miniThumbB64(msg: TdApi.Message): String? {
        val c = msg.content ?: return null

        val holders = ArrayList<Any?>()
        when (c) {
            is TdApi.MessagePhoto -> holders += c.photo
            is TdApi.MessageVideo -> holders += c.video
            is TdApi.MessageAnimation -> holders += c.animation
            is TdApi.MessageDocument -> holders += c.document
            is TdApi.MessageSticker -> holders += c.sticker
        }

        for (h in holders) {
            val data = extractMiniThumbData(h) ?: continue
            if (data.isNotEmpty()) {
                return Base64.encodeToString(data, Base64.NO_WRAP)
            }
        }
        return null
    }

    private fun extractMiniThumbData(holder: Any?): ByteArray? {
        if (holder == null) return null

        // ניסיון ישיר: minithumbnail / miniThumbnail
        val miniObj =
            getField(holder, "minithumbnail")
                ?: getField(holder, "miniThumbnail")
                ?: getField(holder, "mini_thumbnail")

        val direct = miniObj?.let { getField(it, "data") as? ByteArray }
        if (direct != null) return direct

        // fallback: סריקה כללית לשדות שדומים ל-minithumbnail
        for (f in holder.javaClass.fields) {
            val n = f.name
            if (n.contains("mini", ignoreCase = true) && n.contains("thumb", ignoreCase = true)) {
                val o = runCatching { f.get(holder) }.getOrNull() ?: continue
                val d = getField(o, "data") as? ByteArray
                if (d != null) return d
            }
        }
        return null
    }

    private fun getField(obj: Any, name: String): Any? {
        return runCatching { obj.javaClass.getField(name).get(obj) }.getOrNull()
    }
}

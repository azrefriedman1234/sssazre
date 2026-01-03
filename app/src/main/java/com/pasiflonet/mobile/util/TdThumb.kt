package com.pasiflonet.mobile.util

import android.util.Base64
import org.drinkless.tdlib.TdApi

object TdThumb {

    private fun extractMiniBytes(anyMedia: Any?): ByteArray? {
        if (anyMedia == null) return null

        // TDLib generators vary: minithumbnail / miniThumbnail / etc.
        val candidates = listOf("minithumbnail", "miniThumbnail", "mini_thumb", "miniThumb", "miniThumbnail")

        for (name in candidates) {
            try {
                val f = anyMedia.javaClass.getField(name)
                val mtObj = f.get(anyMedia) ?: continue

                // Best case: it's TdApi.Minithumbnail
                if (mtObj is TdApi.Minithumbnail) {
                    val b = mtObj.data
                    if (b != null && b.isNotEmpty()) return b
                }

                // Fallback: read "data" field reflectively
                try {
                    val df = mtObj.javaClass.getField("data")
                    val b = df.get(mtObj) as? ByteArray
                    if (b != null && b.isNotEmpty()) return b
                } catch (_: Throwable) { /* ignore */ }

            } catch (_: Throwable) {
                // ignore and continue
            }
        }
        return null
    }

    fun miniThumbB64(msg: TdApi.Message): String? {
        val c = msg.content ?: return null

        val bytes: ByteArray? = when (c) {
            is TdApi.MessagePhoto -> extractMiniBytes(c.photo)
            is TdApi.MessageVideo -> extractMiniBytes(c.video)
            is TdApi.MessageAnimation -> extractMiniBytes(c.animation)
            is TdApi.MessageDocument -> extractMiniBytes(c.document)
            is TdApi.MessageSticker -> extractMiniBytes(c.sticker)
            else -> null
        }

        if (bytes == null || bytes.isEmpty()) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

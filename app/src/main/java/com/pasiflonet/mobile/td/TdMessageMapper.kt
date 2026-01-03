package com.pasiflonet.mobile.td

import android.util.Base64
import com.pasiflonet.mobile.model.MessageRow
import org.drinkless.tdlib.TdApi

object TdMessageMapper {

    fun isChannelChat(chat: TdApi.Chat): Boolean {
        return when (val t = chat.type) {
            is TdApi.ChatTypeSupergroup -> t.isChannel
            else -> false
        }
    }

    fun mapToRow(chatId: Long, message: TdApi.Message, thumbLocalPath: String?, miniThumbB64: String?): MessageRow {
        val (text, hasMedia, mediaKind) = extractTextAndMedia(message.content)
        val typeLabel = buildString {
            append("ערוץ")
            if (mediaKind != null) append(" • ").append(mediaKind)
        }
        return MessageRow(
            chatId = chatId,
            messageId = message.id,
            text = text,
            unixSeconds = message.date.toLong(),
            typeLabel = typeLabel,
            mediaKind = mediaKind,
            thumbLocalPath = thumbLocalPath,
            miniThumbBase64 = miniThumbB64,
            hasMedia = hasMedia
        )
    }

    private fun extractTextAndMedia(content: TdApi.MessageContent): Triple<String, Boolean, String?> {
        return when (content.constructor) {
            TdApi.MessageText.CONSTRUCTOR -> {
                val c = content as TdApi.MessageText
                Triple(c.text?.text ?: "", false, null)
            }
            TdApi.MessagePhoto.CONSTRUCTOR -> {
                val c = content as TdApi.MessagePhoto
                Triple(c.caption?.text ?: "", true, "תמונה")
            }
            TdApi.MessageVideo.CONSTRUCTOR -> {
                val c = content as TdApi.MessageVideo
                Triple(c.caption?.text ?: "", true, "וידאו")
            }
            TdApi.MessageAnimation.CONSTRUCTOR -> {
                val c = content as TdApi.MessageAnimation
                Triple(c.caption?.text ?: "", true, "GIF")
            }
            TdApi.MessageDocument.CONSTRUCTOR -> {
                val c = content as TdApi.MessageDocument
                Triple(c.caption?.text ?: "", true, "קובץ")
            }
            else -> Triple("", false, "אחר")
        }
    }

    fun getThumbFileId(content: TdApi.MessageContent): Int? {
        return when (content.constructor) {
            TdApi.MessagePhoto.CONSTRUCTOR -> {
                val c = content as TdApi.MessagePhoto
                c.photo?.sizes?.lastOrNull()?.photo?.id
            }
            TdApi.MessageVideo.CONSTRUCTOR -> {
                val c = content as TdApi.MessageVideo
                c.video?.thumbnail?.file?.id
            }
            TdApi.MessageAnimation.CONSTRUCTOR -> {
                val c = content as TdApi.MessageAnimation
                c.animation?.thumbnail?.file?.id
            }
            TdApi.MessageDocument.CONSTRUCTOR -> {
                val c = content as TdApi.MessageDocument
                c.document?.thumbnail?.file?.id
            }
            else -> null
        }
    }

    fun getMiniThumbBase64(content: TdApi.MessageContent): String? {
        val data: ByteArray? = when (content.constructor) {
            TdApi.MessagePhoto.CONSTRUCTOR -> (content as TdApi.MessagePhoto).photo?.minithumbnail?.data
            TdApi.MessageVideo.CONSTRUCTOR -> (content as TdApi.MessageVideo).video?.minithumbnail?.data
            TdApi.MessageAnimation.CONSTRUCTOR -> (content as TdApi.MessageAnimation).animation?.minithumbnail?.data
            TdApi.MessageDocument.CONSTRUCTOR -> (content as TdApi.MessageDocument).document?.minithumbnail?.data
            else -> null
        }
        return if (data != null && data.isNotEmpty()) Base64.encodeToString(data, Base64.NO_WRAP) else null
    }

    fun getMainMediaFileId(content: TdApi.MessageContent): Int? {
        return when (content.constructor) {
            TdApi.MessagePhoto.CONSTRUCTOR -> {
                val c = content as TdApi.MessagePhoto
                c.photo?.sizes?.lastOrNull()?.photo?.id
            }
            TdApi.MessageVideo.CONSTRUCTOR -> {
                val c = content as TdApi.MessageVideo
                c.video?.video?.id
            }
            TdApi.MessageAnimation.CONSTRUCTOR -> {
                val c = content as TdApi.MessageAnimation
                c.animation?.animation?.id
            }
            TdApi.MessageDocument.CONSTRUCTOR -> {
                val c = content as TdApi.MessageDocument
                c.document?.document?.id
            }
            else -> null
        }
    }
}

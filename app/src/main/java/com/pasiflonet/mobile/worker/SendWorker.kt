package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_USERNAME = "target_username"
        const val KEY_TEXT = "text"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"

        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_MEDIA_MIME = "media_mime"

        const val KEY_WATERMARK_URI = "watermark_uri"
    }

    override fun doWork(): Result {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val text = inputData.getString(KEY_TEXT).orEmpty()
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, false)
        val mediaUriStr = inputData.getString(KEY_MEDIA_URI).orEmpty().trim()
        val mediaMime = inputData.getString(KEY_MEDIA_MIME).orEmpty().trim()

        if (srcChatId == 0L || srcMsgId == 0L || targetUsernameRaw.isBlank()) return Result.failure()

        val username = targetUsernameRaw.removePrefix("@").trim()
        if (username.isBlank()) return Result.failure()

        // 1) Resolve @username -> chatId
        val latch1 = CountDownLatch(1)
        var targetChatId: Long = 0L

        TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
            if (obj.constructor == TdApi.Chat.CONSTRUCTOR) {
                targetChatId = (obj as TdApi.Chat).id
            }
            latch1.countDown()
        }

        if (!latch1.await(25, TimeUnit.SECONDS) || targetChatId == 0L) return Result.failure()

        // 2) Build content
        val caption = TdApi.FormattedText(text, null)

        val content: TdApi.InputMessageContent =
            if (sendWithMedia && mediaUriStr.isNotBlank()) {
                val uri = Uri.parse(mediaUriStr)
                val local = copyUriToCache(applicationContext, uri)
                    ?: return Result.failure()

                val inputFile = TdApi.InputFileLocal(local.absolutePath)

                if (mediaMime.startsWith("image/")) {
                    TdApi.InputMessagePhoto().apply {
                        photo = inputFile
                        this.caption = caption
                        hasSpoiler = false
                    }
                } else if (mediaMime.startsWith("video/")) {
                    TdApi.InputMessageVideo().apply {
                        video = inputFile
                        this.caption = caption
                        supportsStreaming = true
                        hasSpoiler = false
                    }
                } else {
                    // fallback: send as document
                    TdApi.InputMessageDocument().apply {
                        document = inputFile
                        this.caption = caption
                    }
                }
            } else {
                TdApi.InputMessageText().apply {
                    this.text = caption
                    linkPreviewOptions = null
                    clearDraft = false
                }
            }

        // 3) Send
        val latch2 = CountDownLatch(1)
        var ok = false

        val fn = TdApi.SendMessage(targetChatId, null, null, null, null, content)
        TdLibManager.send(fn) { obj ->
            ok = (obj.constructor == TdApi.Message.CONSTRUCTOR)
            latch2.countDown()
        }

        if (!latch2.await(40, TimeUnit.SECONDS)) return Result.failure()
        return if (ok) Result.success() else Result.failure()
    }

    private fun copyUriToCache(ctx: Context, uri: Uri): File? {
        return try {
            val name = "pf_${System.currentTimeMillis()}"
            val out = File(ctx.cacheDir, name)
            ctx.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(out).use { output ->
                    input.copyTo(output)
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }
}

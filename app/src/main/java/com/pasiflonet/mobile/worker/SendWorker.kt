package com.pasiflonet.mobile.worker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_USERNAME = "target_username"
        const val KEY_TEXT = "text"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"
        const val KEY_WATERMARK_URI = "watermark_uri"
        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_MEDIA_MIME = "media_mime"
        const val KEY_BLUR_RECTS = "blur_rects" // "l,t,r,b;..."
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
        val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
        val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()

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

        if (!latch1.await(25, TimeUnit.SECONDS) || targetChatId == 0L) {
            return Result.failure()
        }

        val caption = TdApi.FormattedText(text, null)

        // 2) If no media -> send text
        if (!sendWithMedia || mediaUriStr.isBlank()) {
            val content = TdApi.InputMessageText().apply {
                this.text = caption
                this.linkPreviewOptions = null
                this.clearDraft = false
            }
            return sendContent(targetChatId, content)
        }

        // 3) Media path from content:// -> local cache file
        val inUri = runCatching { Uri.parse(mediaUriStr) }.getOrNull() ?: return Result.failure()
        val localIn = copyUriToCache(inUri, mediaMime) ?: return Result.failure()

        // 4) If video and blur rects exist -> produce edited output via ffmpeg
        val finalFile = if (mediaMime.lowercase(Locale.ROOT).startsWith("video/") && blurRectsStr.isNotBlank()) {
            blurVideo(localIn, blurRectsStr) ?: localIn
        } else {
            localIn
        }

        // 5) Build TDLib content
        val inputFile = TdApi.InputFileLocal(finalFile.absolutePath)

        val content: TdApi.InputMessageContent = when {
            mediaMime.lowercase(Locale.ROOT).startsWith("image/") -> {
                TdApi.InputMessagePhoto().apply {
                    this.photo = inputFile
                    this.caption = caption
                }
            }
            mediaMime.lowercase(Locale.ROOT).startsWith("video/") -> {
                TdApi.InputMessageVideo().apply {
                    this.video = inputFile
                    this.caption = caption
                    this.supportsStreaming = true
                }
            }
            else -> {
                // fallback: send as document
                TdApi.InputMessageDocument().apply {
                    this.document = inputFile
                    this.caption = caption
                }
            }
        }

        return sendContent(targetChatId, content)
    }

    private fun sendContent(chatId: Long, content: TdApi.InputMessageContent): Result {
        val latch = CountDownLatch(1)
        var ok = false

        val fn = TdApi.SendMessage(chatId, null, null, null, null, content)
        TdLibManager.send(fn) { obj ->
            ok = (obj.constructor == TdApi.Message.CONSTRUCTOR)
            latch.countDown()
        }

        if (!latch.await(45, TimeUnit.SECONDS)) return Result.failure()
        return if (ok) Result.success() else Result.failure()
    }

    private fun copyUriToCache(uri: Uri, mime: String): File? {
        return try {
            val ext = when {
                mime.startsWith("image/") -> ".jpg"
                mime.startsWith("video/") -> ".mp4"
                else -> ".bin"
            }
            val out = File(applicationContext.cacheDir, "send_${System.currentTimeMillis()}$ext")
            applicationContext.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(out).use { fos -> input.copyTo(fos) }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun blurVideo(input: File, rectsStr: String): File? {
        // rectsStr: "l,t,r,b;..." where values are normalized 0..1 relative to preview.
        val (vw, vh) = getVideoSize(input) ?: return null
        val rects = rectsStr.split(";").mapNotNull { part ->
            val p = part.split(",")
            if (p.size != 4) return@mapNotNull null
            val l = p[0].toFloatOrNull() ?: return@mapNotNull null
            val t = p[1].toFloatOrNull() ?: return@mapNotNull null
            val r = p[2].toFloatOrNull() ?: return@mapNotNull null
            val b = p[3].toFloatOrNull() ?: return@mapNotNull null
            floatArrayOf(l, t, r, b)
        }.filter { it[2] > it[0] && it[3] > it[1] }

        if (rects.isEmpty()) return null

        val out = File(applicationContext.cacheDir, "blur_${System.currentTimeMillis()}.mp4")

        // Build filter_complex using crop+boxblur+overlay chain
        // Start: [0:v]split=N+1[v0][s1]...[sN]
        val n = rects.size
        val sb = StringBuilder()
        sb.append("[0:v]split=").append(n + 1)
        for (i in 0 until (n + 1)) sb.append("[v").append(i).append("]")
        sb.append(";")

        // for each rect: crop + blur -> [b{i}]
        for (i in 0 until n) {
            val rr = rects[i]
            val x = (rr[0] * vw).roundToInt().coerceIn(0, vw - 2)
            val y = (rr[1] * vh).roundToInt().coerceIn(0, vh - 2)
            val w = ((rr[2] - rr[0]) * vw).roundToInt().coerceAtLeast(2).coerceIn(2, vw - x)
            val h = ((rr[3] - rr[1]) * vh).roundToInt().coerceAtLeast(2).coerceIn(2, vh - y)

            sb.append("[v").append(i + 1).append("]")
            sb.append("crop=").append(w).append(":").append(h).append(":").append(x).append(":").append(y)
            sb.append(",boxblur=10:1")
            sb.append("[b").append(i).append("];")
        }

        // overlay chain on base [v0]
        var base = "[v0]"
        for (i in 0 until n) {
            val rr = rects[i]
            val x = (rr[0] * vw).roundToInt().coerceIn(0, vw - 2)
            val y = (rr[1] * vh).roundToInt().coerceIn(0, vh - 2)

            sb.append(base)
                .append("[b").append(i).append("]")
                .append("overlay=").append(x).append(":").append(y)
                .append("[o").append(i).append("];")
            base = "[o$i]"
        }

        // final label
        sb.append(base).append("copy[vout]")

        val cmd = listOf(
            "-y",
            "-i", input.absolutePath,
            "-filter_complex", sb.toString(),
            "-map", "[vout]",
            "-map", "0:a?",
            "-c:v", "libx264",
            "-crf", "23",
            "-preset", "veryfast",
            "-c:a", "aac",
            out.absolutePath
        )

        val session = FFmpegKit.execute(cmd.joinToString(" "))
        val rc = session.returnCode
        return if (rc != null && rc.isValueSuccess) out else null
    }

    private fun getVideoSize(f: File): Pair<Int, Int>? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            r.release()
            if (w > 0 && h > 0) Pair(w, h) else null
        } catch (_: Throwable) {
            null
        }
    }
}

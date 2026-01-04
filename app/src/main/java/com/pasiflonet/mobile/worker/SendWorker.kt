package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    // caption helpers (shared across methods/lambdas)
    private var captionText: String = ""
    private var captionFmt: TdApi.FormattedText = TdApi.FormattedText("", null)
    private var lpOpts: TdApi.LinkPreviewOptions = TdApi.LinkPreviewOptions().apply { isDisabled = true }



    companion object {
        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_USERNAME = "target_username"
        const val KEY_TEXT = "text"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"
        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_MEDIA_MIME = "media_mime"
        const val KEY_WATERMARK_URI = "watermark_uri"
        const val KEY_BLUR_RECTS = "blur_rects"
        const val KEY_WM_X = "wm_x"
        const val KEY_WM_Y = "wm_y"
    }

    private val TAG = "SendWorker"

    override fun doWork(): Result {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsername = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val text = inputData.getString(KEY_TEXT).orEmpty()

        captionText = text
        captionFmt = TdApi.FormattedText(captionText, null)
        lpOpts = TdApi.LinkPreviewOptions().apply { isDisabled = true }

        // caption helpers (single source of truth)

        // --- caption compat (String) + TDLib formatted caption ---

        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)
        val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
        val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
        val wmX = inputData.getFloat(KEY_WM_X, -1f)
        val wmY = inputData.getFloat(KEY_WM_Y, -1f)

        if (targetUsername.isBlank()) return Result.failure()

        val chatId = resolvePublicChatId(targetUsername.removePrefix("@")) ?: return Result.retry()

        if (!sendWithMedia) {
            return if (sendText(chatId, text)) Result.success() else Result.retry()
        }

        val msg = fetchMessage(srcChatId, srcMsgId) ?: return Result.retry()
        val media = extractMediaFileId(msg) ?: run {
            // אין מדיה אמיתית — נשלח טקסט
            return if (sendText(chatId, text)) Result.success() else Result.retry()
        }

        val originalPath = waitDownloadPath(media.fileId, 90) ?: return Result.retry()
        val originalFile = File(originalPath)

        val tmpDir = File(applicationContext.cacheDir, "pasiflonet_tmp").apply { mkdirs() }
        val outBase = File(tmpDir, "out_${UUID.randomUUID()}")

        val wmFile = if (watermarkUriStr.isNotBlank()) copyUriToTmp(Uri.parse(watermarkUriStr), tmpDir) else null
        val rects = parseRects(blurRectsStr)

        val editedFile = tryEdit(originalFile, media.kind, rects, wmFile, wmX, wmY, outBase) ?: originalFile

        val ok = sendMedia(chatId, media.kind, editedFile, text)
        safeCleanup(tmpDir)

        return if (ok) Result.success() else Result.retry()
    }

    private data class Media(val kind: Kind, val fileId: Int)
    private enum class Kind { PHOTO, VIDEO, ANIMATION, DOCUMENT }

    private fun resolvePublicChatId(username: String): Long? {
        val latch = CountDownLatch(1)
        var chatId: Long? = null
        TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
            if (obj is TdApi.Chat) chatId = obj.id
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        return chatId
    }

    private fun sendText(chatId: Long, text: String): Boolean {
        val ft = captionText
        val content = TdApi.InputMessageText(captionFmt, lpOpts, false) // <- חתימה נכונה אצלך
        return sendContent(chatId, content)
    }

    private fun sendMedia(chatId: Long, kind: Kind, file: File, caption: String): Boolean {
        val inputFile = TdApi.InputFileLocal(file.absolutePath)
        val ft = TdApi.FormattedText(caption, null)

        val content: TdApi.InputMessageContent = when (kind) {
            Kind.PHOTO -> TdApi.InputMessagePhoto().apply {
                photo = inputFile
                caption = ft
            }
            Kind.VIDEO -> TdApi.InputMessageVideo().apply {
                video = inputFile
                caption = ft
                supportsStreaming = true
            }
            Kind.ANIMATION -> TdApi.InputMessageAnimation().apply {
                animation = inputFile
                caption = ft
            }
            Kind.DOCUMENT -> TdApi.InputMessageDocument().apply {
                document = inputFile
                caption = ft
            }
        }
        return sendContent(chatId, content)
    }

    private fun sendContent(chatId: Long, content: TdApi.InputMessageContent): Boolean {
        val latch = CountDownLatch(1)
        var ok = false

        TdLibManager.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { obj ->
            ok = obj is TdApi.Message
            latch.countDown()
        }
        latch.await(30, TimeUnit.SECONDS)
        return ok
    }

    private fun fetchMessage(chatId: Long, msgId: Long): TdApi.Message? {
        if (chatId == 0L || msgId == 0L) return null
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) msg = obj
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
        return msg
    }

    private fun extractMediaFileId(msg: TdApi.Message): Media? {
        return when (val c = msg.content) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height } ?: return null
                Media(Kind.PHOTO, best.photo.id)
            }
            is TdApi.MessageVideo -> Media(Kind.VIDEO, c.video?.video?.id ?: return null)
            is TdApi.MessageAnimation -> Media(Kind.ANIMATION, c.animation?.animation?.id ?: return null)
            is TdApi.MessageDocument -> Media(Kind.DOCUMENT, c.document?.document?.id ?: return null)
            else -> null
        }
    }

    private fun waitDownloadPath(fileId: Int, timeoutSec: Int): String? {
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { }
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var f: TdApi.File? = null
            TdLibManager.send(TdApi.GetFile(fileId)) { obj ->
                if (obj is TdApi.File) f = obj
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)
            val done = f?.local?.isDownloadingCompleted ?: false
            val path = f?.local?.path
            if (done && !path.isNullOrBlank()) {
                val ff = File(path)
                if (ff.exists() && ff.length() > 0) return ff.absolutePath
            }
            Thread.sleep(250)
        }
        return null
    }

    private data class NRect(val l: Float, val t: Float, val r: Float, val b: Float)

    private fun parseRects(s: String): List<NRect> {
        if (s.isBlank()) return emptyList()
        return s.split(";").mapNotNull { part ->
            val p = part.split(",")
            if (p.size != 4) return@mapNotNull null
            val l = p[0].toFloatOrNull() ?: return@mapNotNull null
            val t = p[1].toFloatOrNull() ?: return@mapNotNull null
            val r = p[2].toFloatOrNull() ?: return@mapNotNull null
            val b = p[3].toFloatOrNull() ?: return@mapNotNull null
            NRect(l, t, r, b)
        }
    }

    private fun copyUriToTmp(uri: Uri, dir: File): File? {
        return try {
            val out = File(dir, "wm_${UUID.randomUUID()}.png")
            applicationContext.contentResolver.openInputStream(uri).use { inp ->
                if (inp == null) return null
                FileOutputStream(out).use { inp.copyTo(it) }
            }
            if (out.exists() && out.length() > 0) out else null
        } catch (_: Throwable) { null }
    }

    private fun tryEdit(
        input: File,
        kind: Kind,
        rects: List<NRect>,
        wmFile: File?,
        wmX: Float,
        wmY: Float,
        outBase: File
    ): File? {
        val hasBlur = rects.isNotEmpty()
        val hasWm = wmFile != null

        if (!hasBlur && !hasWm) return input

        val out = when (kind) {
            Kind.PHOTO -> File(outBase.absolutePath + ".jpg")
            Kind.VIDEO, Kind.ANIMATION -> File(outBase.absolutePath + ".mp4")
            Kind.DOCUMENT -> File(outBase.absolutePath + "_doc")
        }

        val useWm = hasWm && (kind == Kind.PHOTO || kind == Kind.VIDEO || kind == Kind.ANIMATION)
        val inputs = buildList {
            add("-i"); add(input.absolutePath)
            if (useWm) { add("-i"); add(wmFile!!.absolutePath) }
        }.joinToString(" ")

        val filter = buildFilterGraph(rects, useWm, wmX, wmY)
        val cmd = when (kind) {
            Kind.PHOTO -> {
                // תמונה
                "$inputs -filter_complex \"$filter\" -map \"[vout]\" -frames:v 1 -q:v 3 -y ${out.absolutePath}"
            }
            Kind.VIDEO, Kind.ANIMATION -> {
                "$inputs -filter_complex \"$filter\" -map \"[vout]\" -map 0:a? -c:v libx264 -crf 28 -preset veryfast -c:a copy -y ${out.absolutePath}"
            }
            else -> return input
        }

        Log.d(TAG, "FFmpeg: $cmd")
        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode
        val ok = ReturnCode.isSuccess(rc)

        return if (ok && out.exists() && out.length() > 0) out else input
    }

    private fun buildFilterGraph(rects: List<NRect>, useWm: Boolean, wmX: Float, wmY: Float): String {
        // chain: b0 -> b1 -> ... -> bN
        val sb = StringBuilder()
        sb.append("[0:v]null[b0];")

        var cur = "b0"
        var idx = 0
        for (r in rects) {
            val l = r.l.coerceIn(0f, 1f)
            val t = r.t.coerceIn(0f, 1f)
            val rr = r.r.coerceIn(0f, 1f)
            val bb = r.b.coerceIn(0f, 1f)
            val w = abs(rr - l).coerceIn(0f, 1f)
            val h = abs(bb - t).coerceIn(0f, 1f)

            sb.append("[$cur]split=2[ba$idx][bb$idx];")
            sb.append("[bb$idx]crop=iw*${w}:ih*${h}:iw*${minOf(l, rr)}:ih*${minOf(t, bb)},boxblur=10:1[bl$idx];")
            sb.append("[ba$idx][bl$idx]overlay=x=iw*${minOf(l, rr)}:y=ih*${minOf(t, bb)}[b${idx+1}];")
            cur = "b${idx+1}"
            idx++
        }

        if (useWm) {
            val xExpr = if (wmX >= 0f && wmX <= 1f) "(main_w-overlay_w)*$wmX" else "main_w-overlay_w-10"
            val yExpr = if (wmY >= 0f && wmY <= 1f) "(main_h-overlay_h)*$wmY" else "main_h-overlay_h-10"
            sb.append("[$cur][1:v]overlay=x=$xExpr:y=$yExpr[vout]")
        } else {
            sb.append("[$cur]null[vout]")
        }

        return sb.toString()
    }

    private fun safeCleanup(dir: File) {
        try {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f ->
                if (f.isFile && now - f.lastModified() > 30_000) {
                    runCatching { f.delete() }
                }
            }
        } catch (_: Throwable) {}
    }
}

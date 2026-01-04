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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class SendWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    companion object {
        private const val TAG = "SendWorker"

        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_USERNAME = "target_username"
        const val KEY_TEXT = "text"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"

        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_MEDIA_MIME = "media_mime"

        const val KEY_WATERMARK_URI = "watermark_uri"
        const val KEY_BLUR_RECTS = "blur_rects" // "l,t,r,b;..."
        const val KEY_WM_X = "wm_x"
        const val KEY_WM_Y = "wm_y"
    }

    private enum class Kind { PHOTO, VIDEO, ANIMATION, DOCUMENT }

    private data class RectN(val l: Float, val t: Float, val r: Float, val b: Float)

    override fun doWork(): Result {
        try {
            TdLibManager.init(applicationContext)
            TdLibManager.ensureClient()

            val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
            val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
            val targetUsername = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
            val captionText = inputData.getString(KEY_TEXT).orEmpty()
            val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)

            val wmUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
            val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
            val wmX = inputData.getFloat(KEY_WM_X, -1f)
            val wmY = inputData.getFloat(KEY_WM_Y, -1f)

            val captionFmt = TdApi.FormattedText(captionText, null)

            val chatId = resolvePublicChatId(targetUsername) ?: run {
                Log.e(TAG, "resolve chatId failed for $targetUsername")
                return Result.failure()
            }

            if (!sendWithMedia) {
                val lp = TdApi.LinkPreviewOptions() // TDLib חדש דורש אובייקט, לא boolean
                val content = TdApi.InputMessageText(captionFmt, lp, false)
                if (!send(chatId, content)) return Result.failure()
                Log.i(TAG, "Sent TEXT ok chatId=$chatId len=${captionText.length}")
                return Result.success()
            }

            // ==== MEDIA FLOW ====
            val msg = fetchMessage(srcChatId, srcMsgId) ?: run {
                Log.e(TAG, "GetMessage failed srcChatId=$srcChatId msgId=$srcMsgId")
                return Result.failure()
            }

            val (kind, tgFile) = extractMedia(msg) ?: run {
                // אין מדיה באמת -> שלח כטקסט
                val lp = TdApi.LinkPreviewOptions()
                val content = TdApi.InputMessageText(captionFmt, lp, false)
                if (!send(chatId, content)) return Result.failure()
                Log.w(TAG, "No media in msg -> sent TEXT fallback")
                return Result.success()
            }

            val inFile = downloadTelegramFile(tgFile.id) ?: run {
                Log.e(TAG, "DownloadFile failed id=${tgFile.id}")
                return Result.failure()
            }

            val rects = parseRects(blurRectsStr)
            val wmFile = if (wmUriStr.isNotBlank()) resolveUriToFile(wmUriStr) else null

            val edited = processEdits(
                kind = kind,
                input = inFile,
                rects = rects,
                watermark = wmFile,
                wmX = wmX,
                wmY = wmY
            )

            val outFile = edited ?: inFile
            val inputFile = TdApi.InputFileLocal(outFile.absolutePath)

            val content: TdApi.InputMessageContent = when (kind) {
                Kind.PHOTO -> TdApi.InputMessagePhoto().apply {
                    photo = inputFile
                    caption = captionFmt
                }
                Kind.VIDEO -> TdApi.InputMessageVideo().apply {
                    video = inputFile
                    supportsStreaming = true
                    caption = captionFmt
                }
                Kind.ANIMATION -> TdApi.InputMessageAnimation().apply {
                    animation = inputFile
                    caption = captionFmt
                }
                Kind.DOCUMENT -> TdApi.InputMessageDocument().apply {
                    document = inputFile
                    caption = captionFmt
                }
            }

            val ok = send(chatId, content)
            Log.i(TAG, "Sent MEDIA ok=$ok kind=$kind file=${outFile.name} captionLen=${captionText.length}")

            // ניקוי קבצים זמניים שנוצרו בעריכה בלבד
            runCatching {
                if (edited != null && edited.exists() && edited.absolutePath != inFile.absolutePath) edited.delete()
                if (wmFile != null && wmFile.exists() && wmFile.name.startsWith("wm_")) wmFile.delete()
            }

            return if (ok) Result.success() else Result.failure()
        } catch (t: Throwable) {
            Log.e(TAG, "doWork crash: ${t.message}", t)
            return Result.failure()
        }
    }

    private fun resolvePublicChatId(usernameRaw: String): Long? {
        val u = usernameRaw.removePrefix("@").trim()
        if (u.isBlank()) return null
        val latch = CountDownLatch(1)
        var chatId: Long? = null
        TdLibManager.send(TdApi.SearchPublicChat(u)) { obj ->
            if (obj is TdApi.Chat) chatId = obj.id
            latch.countDown()
        }
        latch.await(25, TimeUnit.SECONDS)
        return chatId
    }

    private fun fetchMessage(chatId: Long, msgId: Long): TdApi.Message? {
        if (chatId == 0L || msgId == 0L) return null
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) msg = obj
            latch.countDown()
        }
        latch.await(25, TimeUnit.SECONDS)
        return msg
    }

    private fun extractMedia(msg: TdApi.Message): Pair<Kind, TdApi.File>? {
        val c = msg.content ?: return null
        return when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.photo?.size ?: 0 } ?: sizes.lastOrNull()
                val f = best?.photo ?: return null
                Kind.PHOTO to f
            }
            is TdApi.MessageVideo -> {
                val f = c.video?.video ?: return null
                Kind.VIDEO to f
            }
            is TdApi.MessageAnimation -> {
                val f = c.animation?.animation ?: return null
                Kind.ANIMATION to f
            }
            is TdApi.MessageDocument -> {
                val f = c.document?.document ?: return null
                Kind.DOCUMENT to f
            }
            else -> null
        }
    }

    private fun downloadTelegramFile(fileId: Int, timeoutSec: Int = 120): File? {
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
            val path = f?.local?.path
            val done = f?.local?.isDownloadingCompleted ?: false
            if (!path.isNullOrBlank() && done) {
                val ff = File(path)
                if (ff.exists() && ff.length() > 0) return ff
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun parseRects(s: String): List<RectN> {
        if (s.isBlank()) return emptyList()
        return s.split(";").mapNotNull { chunk ->
            val p = chunk.split(",")
            if (p.size != 4) return@mapNotNull null
            val l = p[0].toFloatOrNull() ?: return@mapNotNull null
            val t = p[1].toFloatOrNull() ?: return@mapNotNull null
            val r = p[2].toFloatOrNull() ?: return@mapNotNull null
            val b = p[3].toFloatOrNull() ?: return@mapNotNull null
            RectN(l, t, r, b)
        }
    }

    private fun tmpDir(): File {
        val d = File(applicationContext.cacheDir, "pasiflonet_tmp")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun resolveUriToFile(uriStr: String): File? {
        return try {
            val uri = Uri.parse(uriStr)
            val out = File(tmpDir(), "wm_${System.currentTimeMillis()}.png")
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return null
            out
        } catch (t: Throwable) {
            Log.w(TAG, "resolveUriToFile failed: ${t.message}")
            null
        }
    }

    private fun q(s: String): String {
        // FFmpegKit command-line quoting
        val safe = s.replace("'", "\\'")
        return "'$safe'"
    }

    private fun processEdits(
        kind: Kind,
        input: File,
        rects: List<RectN>,
        watermark: File?,
        wmX: Float,
        wmY: Float
    ): File? {
        val hasBlur = rects.isNotEmpty()
        val hasWm = watermark != null && watermark.exists() && watermark.length() > 0
        if (!hasBlur && !hasWm) return null

        val outExt = when (kind) {
            Kind.PHOTO -> "jpg"
            else -> "mp4"
        }
        val out = File(tmpDir(), "out_${System.currentTimeMillis()}.$outExt")

        // ===== filtergraph =====
        // NOTE: overlay לא תומך iw/ih -> חייב main_w/main_h (זה מה שהפיל לך את ה-FFmpeg בלוג)
        val filters = mutableListOf<String>()
        var cur = "v0"
        filters += "[0:v]format=rgba[$cur]"

        // blur rectangles
        rects.forEachIndexed { i, r ->
            val base = cur
            val b = "b$i"
            val t = "t$i"
            val bl = "bl$i"
            val v = "v${i + 1}"

            val xCrop = "${r.l}*iw"
            val yCrop = "${r.t}*ih"
            val wCrop = "(${r.r}-${r.l})*iw"
            val hCrop = "(${r.b}-${r.t})*ih"

            val xOv = "${r.l}*main_w"
            val yOv = "${r.t}*main_h"

            filters += "[$base]split=2[$b][$t]"
            filters += "[$t]crop=w=$wCrop:h=$hCrop:x=$xCrop:y=$yCrop,boxblur=10:1[$bl]"
            filters += "[$b][$bl]overlay=x=$xOv:y=$yOv[$v]"
            cur = v
        }

        // watermark overlay
        if (hasWm) {
            val wLabel = "wm"
            filters += "[1:v]format=rgba[$wLabel]"
            val xExpr = if (wmX >= 0f) "${wmX}*main_w" else "main_w-overlay_w-10"
            val yExpr = if (wmY >= 0f) "${wmY}*main_h" else "main_h-overlay_h-10"
            val outV = "vout"
            filters += "[$cur][$wLabel]overlay=x=$xExpr:y=$yExpr:format=auto[$outV]"
            cur = outV
        }

        val filterGraph = filters.joinToString(";")

        val cmd = buildString {
            append("-y ")
            append("-i ${q(input.absolutePath)} ")
            if (hasWm) append("-i ${q(watermark!!.absolutePath)} ")

            append("-filter_complex ${q(filterGraph)} ")
            append("-map [$cur] ")

            // keep audio if exists (video)
            if (kind != Kind.PHOTO) {
                append("-map 0:a? ")
                append("-c:a copy ")
                append("-c:v libx264 -crf 28 -preset veryfast ")
                append("-movflags +faststart ")
            } else {
                append("-frames:v 1 -q:v 2 ")
            }

            append(q(out.absolutePath))
        }

        Log.i(TAG, "FFmpeg cmd: $cmd")
        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode
        if (ReturnCode.isSuccess(rc) && out.exists() && out.length() > 0) {
            Log.i(TAG, "FFmpeg OK out=${out.absolutePath} size=${out.length()}")
            return out
        }

        Log.e(TAG, "FFmpeg FAILED rc=$rc")
        Log.e(TAG, session.failStackTrace ?: "no stacktrace")
        runCatching { if (out.exists()) out.delete() }
        return null
    }

    private fun send(chatId: Long, content: TdApi.InputMessageContent): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
        }
        TdLibManager.send(req) { obj ->
            ok = obj is TdApi.Message
            latch.countDown()
        }
        latch.await(45, TimeUnit.SECONDS)
        return ok
    }
}

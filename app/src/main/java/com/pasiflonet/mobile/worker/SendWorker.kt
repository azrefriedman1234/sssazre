package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.Data
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.util.AppLog
import com.pasiflonet.mobile.util.TempCleaner
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SendWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    companion object {
        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_msg_id"
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

    private enum class Kind { PHOTO, VIDEO, ANIMATION, DOCUMENT, UNKNOWN }

    override fun doWork(): Result {
        val ctx = applicationContext

        try {
            TdLibManager.init(ctx)
            TdLibManager.ensureClient()

            val target = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim().removePrefix("@")
            val text = inputData.getString(KEY_TEXT).orEmpty()
            val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)

            val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
            val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
            val wmX = inputData.getFloat(KEY_WM_X, -1f)
            val wmY = inputData.getFloat(KEY_WM_Y, -1f)

            val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
            val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)

            AppLog.i(ctx, "SendWorker start: target=@$target sendWithMedia=$sendWithMedia srcChat=$srcChatId srcMsg=$srcMsgId textLen=${text.length}")

            val targetChatId = resolveTargetChatId(target)
            if (targetChatId == 0L) {
                AppLog.e(ctx, "resolveTargetChatId failed for @$target")
                return Result.failure(Data.Builder().putString("err","resolveTargetChatId failed").build())
            }

            // אם המשתמש בחר "בלי מדיה" – שולחים טקסט בלבד
            if (!sendWithMedia) {
                sendText(targetChatId, text)
                AppLog.i(ctx, "Sent TEXT only to chatId=$targetChatId")
                return Result.success()
            }

            // מביאים את הודעת המקור מהטלגרם
            val msg = fetchMessageSync(srcChatId, srcMsgId)
            if (msg == null) {
                AppLog.e(ctx, "GetMessage returned null")
                return Result.failure(Data.Builder().putString("err","GetMessage null").build())
            }

            val (kind, tdFile) = extractMediaFile(msg)
            if (tdFile == null) {
                AppLog.e(ctx, "No media file in source message (kind=$kind)")
                // לא עושים fallback לטקסט כי ביקשת "עם מדיה"
                return Result.failure(Data.Builder().putString("err","no media in message").build())
            }

            val downloaded = ensureFileDownloaded(tdFile.id)
            if (downloaded == null) {
                AppLog.e(ctx, "DownloadFile/GetFile failed for fileId=${tdFile.id}")
                return Result.failure(Data.Builder().putString("err","download failed").build())
            }

            val tmpDir = TempCleaner.tempDir(ctx)
            val inFile = File(tmpDir, "in_${System.currentTimeMillis()}_${downloaded.name}")
            downloaded.copyTo(inFile, overwrite = true)

            val wmFile = resolveWatermarkToLocalFile(watermarkUriStr, tmpDir)
            val outFile = processEdits(
                kind = kind,
                input = inFile,
                tmpDir = tmpDir,
                watermarkFile = wmFile,
                blurRectsStr = blurRectsStr,
                wmX = wmX, wmY = wmY
            )

            // שולחים מדיה + caption (טקסט)
            sendMediaWithCaption(targetChatId, kind, outFile, text)

            AppLog.i(ctx, "Sent MEDIA kind=$kind file=${outFile.name} captionLen=${text.length}")

            // ניקוי temp
            val cleaned = TempCleaner.clean(ctx)
            AppLog.i(ctx, "TempCleaner: deleted=${cleaned.deletedFiles} freed=${TempCleaner.fmt(cleaned.freedBytes)}")

            return Result.success()
        } catch (t: Throwable) {
            AppLog.e(applicationContext, "SendWorker crash", t)
            return Result.failure(Data.Builder().putString("err", t.message ?: "crash").build())
        }
    }

    private fun resolveTargetChatId(usernameNoAt: String): Long {
        val latch = CountDownLatch(1)
        var id = 0L
        TdLibManager.send(TdApi.SearchPublicChat(usernameNoAt)) { obj ->
            if (obj is TdApi.Chat) id = obj.id
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
        return id
    }

    private fun fetchMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
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

    private fun extractMediaFile(msg: TdApi.Message): Pair<Kind, TdApi.File?> {
        val c = msg.content ?: return Kind.UNKNOWN to null
        return when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height }
                Kind.PHOTO to best?.photo
            }
            is TdApi.MessageVideo -> Kind.VIDEO to c.video?.video
            is TdApi.MessageAnimation -> Kind.ANIMATION to c.animation?.animation
            is TdApi.MessageDocument -> Kind.DOCUMENT to c.document?.document
            else -> Kind.UNKNOWN to null
        }
    }

    private fun ensureFileDownloaded(fileId: Int): File? {
        // start download
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { }

        val deadline = System.currentTimeMillis() + 120_000L
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
                if (ff.exists() && ff.length() > 0) return ff
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun resolveWatermarkToLocalFile(wmUriStr: String, tmpDir: File): File? {
        if (wmUriStr.isBlank()) return null
        return try {
            val uri = Uri.parse(wmUriStr)
            // copy content:// into local temp so ffmpeg can read
            val out = File(tmpDir, "wm_${System.currentTimeMillis()}.png")
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return null
            if (out.exists() && out.length() > 0) out else null
        } catch (_: Throwable) {
            // maybe it is a file path
            val f = File(wmUriStr.removePrefix("file://"))
            if (f.exists() && f.length() > 0) f else null
        }
    }

    data class NRect(val l: Float, val t: Float, val r: Float, val b: Float)

    private fun parseRects(s: String): List<NRect> {
        if (s.isBlank()) return emptyList()
        return s.split(";")
            .mapNotNull { part ->
                val p = part.split(",")
                if (p.size != 4) return@mapNotNull null
                val l = p[0].toFloatOrNull() ?: return@mapNotNull null
                val t = p[1].toFloatOrNull() ?: return@mapNotNull null
                val r = p[2].toFloatOrNull() ?: return@mapNotNull null
                val b = p[3].toFloatOrNull() ?: return@mapNotNull null
                NRect(l, t, r, b)
            }
    }

    private fun f(x: Float): String = String.format(Locale.US, "%.6f", x)

    private fun processEdits(
        kind: Kind,
        input: File,
        tmpDir: File,
        watermarkFile: File?,
        blurRectsStr: String,
        wmX: Float, wmY: Float
    ): File {
        val rects = parseRects(blurRectsStr)
        val hasBlur = rects.isNotEmpty()
        val hasWm = watermarkFile != null && wmX >= 0f && wmY >= 0f

        if (!hasBlur && !hasWm) return input

        val outExt = when (kind) {
            Kind.PHOTO -> "jpg"
            else -> "mp4"
        }
        val out = File(tmpDir, "out_${System.currentTimeMillis()}.$outExt")

        val sb = StringBuilder()
        sb.append("-y -i ").append(q(input.absolutePath)).append(" ")

        if (hasWm) sb.append("-i ").append(q(watermarkFile!!.absolutePath)).append(" ")

        val fc = StringBuilder()
        fc.append("[0:v]format=rgba[v0];")
        var cur = "v0"

        rects.forEachIndexed { i, r ->
            val l = f(r.l.coerceIn(0f, 1f))
            val t = f(r.t.coerceIn(0f, 1f))
            val rr = f(r.r.coerceIn(0f, 1f))
            val bb = f(r.b.coerceIn(0f, 1f))
            val next = "v${i+1}"
            fc.append("[$cur]split=2[vmain$i][vtmp$i];")
            fc.append("[vtmp$i]crop=w='($rr-$l)*iw':h='($bb-$t)*ih':x='$l*iw':y='$t*ih',boxblur=10:1[blur$i];")
            fc.append("[vmain$i][blur$i]overlay=x='$l*iw':y='$t*ih':format=auto[$next];")
            cur = next
        }

        if (hasWm) {
            val x = f(wmX.coerceIn(0f, 1f))
            val y = f(wmY.coerceIn(0f, 1f))
            fc.append("[1:v]format=rgba[wm];")
            fc.append("[$cur][wm]overlay=x='$x*(main_w-overlay_w)':y='$y*(main_h-overlay_h)':format=auto[vout]")
        } else {
            fc.append("[$cur]null[vout]")
        }

        sb.append("-filter_complex ").append(q(fc.toString())).append(" ")
        sb.append("-map ").append(q("[vout]")).append(" ")

        if (kind != Kind.PHOTO) {
            sb.append("-map 0:a? -c:a copy ")
            sb.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -movflags +faststart ")
        } else {
            sb.append("-q:v 2 ")
        }

        sb.append(q(out.absolutePath))

        val cmd = sb.toString()
        AppLog.i(applicationContext, "FFmpeg cmd: $cmd")
        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode
        if (!ReturnCode.isSuccess(rc) || !out.exists() || out.length() == 0L) {
            AppLog.e(applicationContext, "FFmpeg failed rc=$rc\n${session.allLogsAsString}")
            // fallback: return input but לפחות לא נקרוס
            return input
        }
        return out
    }

    private fun sendText(chatId: Long, text: String) {
        val ft = TdApi.FormattedText(text, null)
        val lp = TdApi.LinkPreviewOptions()
        val content = TdApi.InputMessageText()
        content.text = ft
        content.linkPreviewOptions = lp
        content.clearDraft = false
        sendContent(chatId, content)
    }

    private fun sendMediaWithCaption(chatId: Long, kind: Kind, file: File, text: String) {
        val ft = TdApi.FormattedText(text, null)
        val input = TdApi.InputFileLocal(file.absolutePath)

        val content: TdApi.InputMessageContent = when (kind) {
            Kind.PHOTO -> TdApi.InputMessagePhoto().apply {
                photo = input
                caption = ft
                addedStickerFileIds = intArrayOf()
                hasSpoiler = false
                selfDestructType = null
            }
            Kind.VIDEO -> TdApi.InputMessageVideo().apply {
                video = input
                caption = ft
                addedStickerFileIds = intArrayOf()
                supportsStreaming = true
                selfDestructType = null
            }
            Kind.ANIMATION -> TdApi.InputMessageAnimation().apply {
                animation = input
                caption = ft
            }
            else -> TdApi.InputMessageDocument().apply {
                document = input
                caption = ft
            }
        }

        sendContent(chatId, content)
    }

    
    private fun setField(obj: Any, name: String, value: Any?) {
        try {
            val f = obj.javaClass.getField(name)
            f.isAccessible = true
            f.set(obj, value)
        } catch (_: Throwable) { }
    }

private fun sendContent(chatId: Long, content: TdApi.InputMessageContent) {
        val latch = CountDownLatch(1)
        var ok = false
        val req = TdApi.SendMessage()
        // TDLib משתנה בין גרסאות: לפעמים messageTopic, לפעמים messageThreadId
        setField(req, "chatId", chatId)
        setField(req, "messageThreadId", 0L)
        setField(req, "messageTopic", null)
        setField(req, "replyTo", null)
        setField(req, "options", null)
        setField(req, "replyMarkup", null)
        setField(req, "inputMessageContent", content)

        TdLibManager.send(req) { obj ->
            ok = (obj is TdApi.Message) || (obj is TdApi.Ok)
            if (!ok && obj is TdApi.Error) {
                AppLog.e(applicationContext, "SendMessage error: ${obj.code} ${obj.message}")
            }
            latch.countDown()
        }
        latch.await(30, TimeUnit.SECONDS)
        if (!ok) throw RuntimeException("SendMessage failed (see logs)")
    }

    private fun q(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""
}

package com.pasiflonet.mobile.worker

import android.content.Context
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
        const val KEY_BLUR_RECTS = "blur_rects" // "l,t,r,b;..."

        const val KEY_WM_X = "wm_x"
        const val KEY_WM_Y = "wm_y"
    }

    private enum class Kind { PHOTO, VIDEO, DOCUMENT }

    override fun doWork(): Result {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val captionText = inputData.getString(KEY_TEXT).orEmpty()
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, false)

        val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
        val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
        val wmX = inputData.getFloat(KEY_WM_X, -1f)
        val wmY = inputData.getFloat(KEY_WM_Y, -1f)

        if (srcChatId == 0L || srcMsgId == 0L || targetUsernameRaw.isBlank()) return Result.failure()

        val username = targetUsernameRaw.removePrefix("@").trim()
        if (username.isBlank()) return Result.failure()

        // resolve target chat
        val targetChatId = searchPublicChatId(username) ?: return Result.failure()

        val caption = TdApi.FormattedText(captionText, null)

        if (!sendWithMedia) {
            val content = TdApi.InputMessageText().apply {
                text = caption
                linkPreviewOptions = null
                clearDraft = false
            }
            return sendContent(targetChatId, content)
        }

        // media requested -> MUST send media or fail (no fallback to text)
        val msg = getMessageSync(srcChatId, srcMsgId) ?: return Result.failure()
        val media = extractMedia(msg) ?: return Result.failure()

        val inFile = ensureDownloaded(media.fileId) ?: return Result.failure()

        val cached = copyFileToCache(inFile, media.kind)
        val edited = applyEdits(
            input = cached,
            kind = media.kind,
            blurRectsStr = blurRectsStr,
            watermarkUriStr = watermarkUriStr,
            wmX = wmX,
            wmY = wmY
        ) ?: cached

        val inputFile = TdApi.InputFileLocal(edited.absolutePath)
        val content: TdApi.InputMessageContent = when (media.kind) {
            Kind.PHOTO -> TdApi.InputMessagePhoto().apply { photo = inputFile; this.caption = caption }
            Kind.VIDEO -> TdApi.InputMessageVideo().apply { video = inputFile; this.caption = caption; supportsStreaming = true }
            Kind.DOCUMENT -> TdApi.InputMessageDocument().apply { document = inputFile; this.caption = caption }
        }

        return sendContent(targetChatId, content)
    }

    private data class Media(val kind: Kind, val fileId: Int)

    private fun extractMedia(msg: TdApi.Message): Media? {
        val c = msg.content ?: return null
        return when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height } ?: return null
                Media(Kind.PHOTO, best.photo.id)
            }
            is TdApi.MessageVideo -> {
                val id = c.video?.video?.id ?: return null
                Media(Kind.VIDEO, id)
            }
            is TdApi.MessageAnimation -> {
                val id = c.animation?.animation?.id ?: return null
                Media(Kind.VIDEO, id)
            }
            is TdApi.MessageDocument -> {
                val id = c.document?.document?.id ?: return null
                Media(Kind.DOCUMENT, id)
            }
            else -> null
        }
    }

    private fun searchPublicChatId(username: String): Long? {
        val latch = CountDownLatch(1)
        var out: Long? = null
        TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
            if (obj is TdApi.Chat) out = obj.id
            latch.countDown()
        }
        latch.await(25, TimeUnit.SECONDS)
        return out
    }

    private fun getMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var out: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) out = obj
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
        return out
    }

    private fun ensureDownloaded(fileId: Int, timeoutSec: Int = 160): File? {
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
                if (ff.exists() && ff.length() > 0) return ff
            }
            Thread.sleep(350)
        }
        return null
    }

    private fun copyFileToCache(src: File, kind: Kind): File {
        val ext = when (kind) {
            Kind.PHOTO -> ".jpg"
            Kind.VIDEO -> ".mp4"
            Kind.DOCUMENT -> ".bin"
        }
        val out = File(applicationContext.cacheDir, "tg_${System.currentTimeMillis()}$ext")
        src.inputStream().use { input ->
            FileOutputStream(out).use { fos -> input.copyTo(fos) }
        }
        return out
    }

    private fun resolveWatermarkFile(wm: String): File? {
        if (wm.isBlank()) return null
        return try {
            val u = Uri.parse(wm)
            when (u.scheme?.lowercase(Locale.ROOT)) {
                "content" -> {
                    val out = File(applicationContext.cacheDir, "wm_${System.currentTimeMillis()}.png")
                    applicationContext.contentResolver.openInputStream(u).use { input ->
                        if (input == null) return null
                        FileOutputStream(out).use { fos -> input.copyTo(fos) }
                    }
                    out
                }
                "file" -> File(u.path ?: return null).takeIf { it.exists() }
                else -> File(wm).takeIf { it.exists() }
            }
        } catch (_: Throwable) {
            File(wm).takeIf { it.exists() }
        }
    }

    private fun parseRects(rectsStr: String): List<FloatArray> {
        if (rectsStr.isBlank()) return emptyList()
        return rectsStr.split(";").mapNotNull { part ->
            val p = part.split(",")
            if (p.size != 4) return@mapNotNull null
            val l = p[0].toFloatOrNull() ?: return@mapNotNull null
            val t = p[1].toFloatOrNull() ?: return@mapNotNull null
            val r = p[2].toFloatOrNull() ?: return@mapNotNull null
            val b = p[3].toFloatOrNull() ?: return@mapNotNull null
            if (r <= l || b <= t) return@mapNotNull null
            floatArrayOf(l, t, r, b)
        }
    }

    private fun applyEdits(
        input: File,
        kind: Kind,
        blurRectsStr: String,
        watermarkUriStr: String,
        wmX: Float,
        wmY: Float
    ): File? {
        // כרגע עריכות רק ל-PHOTO/VIDEO (DOCUMENT לא)
        if (kind == Kind.DOCUMENT) return null

        val rects = parseRects(blurRectsStr)
        val wmFile = resolveWatermarkFile(watermarkUriStr)
        val hasBlur = rects.isNotEmpty()
        val hasWm = wmFile != null

        if (!hasBlur && !hasWm) return null

        val out = File(
            applicationContext.cacheDir,
            "edit_${System.currentTimeMillis()}" + if (kind == Kind.PHOTO) ".jpg" else ".mp4"
        )

        // watermark position
        fun wmPosExpr(w: String, h: String, wmW: String): Pair<String, String> {
            val pad = "max(12\\,${w}*0.02)"
            return if (wmX < 0f || wmY < 0f) {
                Pair("${w}-${wmW}-${pad}", "${h}-${pad}-(${wmW}/2)")
            } else {
                Pair("(${w}*${wmX})", "(${h}*${wmY})")
            }
        }

        // Build filter complex:
        // - blur: crop+boxblur+overlay (works for image and video)
        // - watermark: [1:v]scale=... then overlay
        val vw = "main_w"
        val vh = "main_h"
        val wmW = "${vw}*0.22"
        val (wmPx, wmPy) = wmPosExpr(vw, vh, wmW)

        val filter = StringBuilder()

        // Start with base video as [base]
        filter.append("[0:v]scale=iw:ih[base];")

        var cur = "[base]"

        if (hasBlur) {
            // split for N rects + base copy
            val n = rects.size
            filter.append("$cur split=${n + 1}")
            for (i in 0 until (n + 1)) filter.append("[v$i]")
            filter.append(";")

            // crops -> blur
            for (i in 0 until n) {
                val rr = rects[i]
                val x = "${rr[0]}*${vw}"
                val y = "${rr[1]}*${vh}"
                val w = "(${rr[2]}-${rr[0]})*${vw}"
                val h = "(${rr[3]}-${rr[1]})*${vh}"
                filter.append("[v${i + 1}]crop=$w:$h:$x:$y,boxblur=10:1[b$i];")
            }

            // overlay back
            var base = "[v0]"
            for (i in 0 until n) {
                val rr = rects[i]
                val x = "${rr[0]}*${vw}"
                val y = "${rr[1]}*${vh}"
                filter.append("$base[b$i]overlay=$x:$y[o$i];")
                base = "[o$i]"
            }
            cur = base
        }

        if (hasWm) {
            filter.append("[1:v]scale=${wmW}:-1[wm];")
            filter.append("$cur[wm]overlay=$wmPx:$wmPy[vout]")
        } else {
            filter.append("$cur copy[vout]")
        }

        val cmd = mutableListOf<String>()
        cmd += "-y"
        cmd += "-i"; cmd += input.absolutePath
        if (wmFile != null) { cmd += "-i"; cmd += wmFile.absolutePath }
        cmd += "-filter_complex"; cmd += filter.toString()
        cmd += "-map"; cmd += "[vout]"

        if (kind == Kind.VIDEO) {
            cmd += "-map"; cmd += "0:a?"
            cmd += "-c:v"; cmd += "libx264"
            cmd += "-crf"; cmd += "23"
            cmd += "-preset"; cmd += "veryfast"
            cmd += "-c:a"; cmd += "aac"
            cmd += out.absolutePath
        } else {
            cmd += "-frames:v"; cmd += "1"
            cmd += "-q:v"; cmd += "2"
            cmd += out.absolutePath
        }

        val session = FFmpegKit.execute(cmd.joinToString(" "))
        val rc = session.returnCode
        return if (rc != null && rc.isValueSuccess) out else null
    }

    private fun sendContent(chatId: Long, content: TdApi.InputMessageContent): Result {
        val latch = CountDownLatch(1)
        var ok = false
        TdLibManager.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { obj ->
            ok = (obj is TdApi.Message)
            latch.countDown()
        }
        latch.await(45, TimeUnit.SECONDS)
        return if (ok) Result.success() else Result.failure()
    }
}

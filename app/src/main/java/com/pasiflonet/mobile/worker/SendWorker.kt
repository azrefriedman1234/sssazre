package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

class SendWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    companion object {
        const val KEY_LOG_TAIL = "log_tail"
        const val KEY_ERROR_MSG = "error_msg"
        const val KEY_LOG_FILE = "log_file"

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

    private enum class Kind { PHOTO, VIDEO, ANIMATION, DOCUMENT }
    private data class RectN(val l: Float, val t: Float, val r: Float, val b: Float)
    private data class MediaInfo(val kind: Kind, val fileId: Int)

    override fun doWork(): Result {
        val logDir = File(applicationContext.getExternalFilesDir(null), "pasiflonet_logs").apply { mkdirs() }
        val logFile = File(logDir, "send_${System.currentTimeMillis()}.log")
        val tail = ArrayDeque<String>(240)

        fun pushLine(line: String) {
            runCatching { logFile.appendText(line + "\n") }
            if (tail.size >= 240) tail.removeFirst()
            tail.addLast(line.take(800))
            runCatching { setProgressAsync(workDataOf(KEY_LOG_TAIL to tail.joinToString("\n"))) }
        }

        fun fail(msg: String, t: Throwable? = null): Result {
            val m = msg.take(300)
            pushLine("=== FAILED: $m ===")
            if (t != null) pushLine(Log.getStackTraceString(t).take(3000))
            return Result.failure(
                workDataOf(
                    KEY_ERROR_MSG to m,
                    KEY_LOG_FILE to logFile.absolutePath,
                    KEY_LOG_TAIL to tail.joinToString("\n")
                )
            )
        }

        val tmpDir = File(applicationContext.cacheDir, "pasiflonet_tmp").apply { mkdirs() }

        try {
            pushLine("=== SendWorker started ===")
            pushLine("INPUT: " + inputData.keyValueMap.toString())

            // FFmpeg log bridge (single)
            runCatching {
                FFmpegKitConfig.enableLogCallback { log ->
                    pushLine("[FFMPEG ${'$'}{log.level}] ${'$'}{log.message}".take(900))
                }
                FFmpegKitConfig.enableStatisticsCallback { stat: Statistics ->
                    pushLine("[STAT] time=${'$'}{stat.time} size=${'$'}{stat.size} bitrate=${'$'}{stat.bitrate} speed=${'$'}{stat.speed}".take(900))
                }
            }.onFailure {
                pushLine("WARN: cannot enable FFmpeg callbacks: " + (it.message ?: it.javaClass.simpleName))
            }

            // inputs
            val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
            val captionText = inputData.getString(KEY_TEXT).orEmpty()
            val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)

            val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
            val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
            val wmX = inputData.getFloat(KEY_WM_X, 0.8f)
            val wmY = inputData.getFloat(KEY_WM_Y, 0.8f)

            val mediaUriStr = inputData.getString(KEY_MEDIA_URI).orEmpty().trim()
            val mediaMime = inputData.getString(KEY_MEDIA_MIME).orEmpty().trim()

            val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
            val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)

            if (targetUsernameRaw.isBlank()) {
                return fail("Missing target username")
            }

            TdLibManager.init(applicationContext)
            TdLibManager.ensureClient()

            val targetChatId = resolveTargetChatId(targetUsernameRaw)
                ?: return fail("resolveTargetChatId failed for '$targetUsernameRaw'")

            val captionFmt = TdApi.FormattedText(captionText, null)
            val lpOpts = TdApi.LinkPreviewOptions()

            // TEXT only
            if (!sendWithMedia) {
                val content = TdApi.InputMessageText(captionFmt, lpOpts, false)
                val ok = sendMessage(targetChatId, content)
                return if (ok) Result.success(workDataOf(KEY_LOG_FILE to logFile.absolutePath)) else fail("Send TEXT failed")
            }

            // optional edits
            val rects = parseRects(blurRectsStr)
            val wmFile: File? = if (watermarkUriStr.isNotBlank()) {
                resolveUriToTempFile(Uri.parse(watermarkUriStr), tmpDir, "wm_${System.currentTimeMillis()}.png")
            } else null

            // MEDIA path A: explicit media_uri (from picker/editor)
            if (mediaUriStr.isNotBlank()) {
                val uri = Uri.parse(mediaUriStr)
                val kind = detectKindFromMime(mediaMime, uri.toString())
                val inputFile = resolveUriToTempFile(uri, tmpDir, "in_${System.currentTimeMillis()}${extFor(kind)}")
                    ?: return fail("Cannot read media_uri")

                val finalFile = if (wmFile == null && rects.isEmpty()) {
                    inputFile
                } else {
                    val outFile = File(tmpDir, "out_${System.currentTimeMillis()}${extFor(kind)}")
                    val ok = runFfmpegEdits(
                        input = inputFile,
                        output = outFile,
                        kind = kind,
                        rects = rects,
                        wmFile = wmFile,
                        wmX = wmX,
                        wmY = wmY
                    )
                    if (ok) outFile else inputFile
                }

                val content = buildContent(kind, finalFile, captionFmt)
                val sentOk = sendMessage(targetChatId, content)
                pushLine("SENT uri-media kind=$kind edits=${(wmFile!=null)||rects.isNotEmpty()} ok=$sentOk")
                return if (sentOk) Result.success(workDataOf(KEY_LOG_FILE to logFile.absolutePath)) else fail("Send media failed")
            }

            // MEDIA path B: from source Telegram message (src_chat_id/src_message_id)
            if (srcChatId == 0L || srcMsgId == 0L) {
                // if user wanted media but didn't provide it and no src ids -> fallback to text
                val content = TdApi.InputMessageText(captionFmt, lpOpts, false)
                val ok = sendMessage(targetChatId, content)
                return if (ok) Result.success(workDataOf(KEY_LOG_FILE to logFile.absolutePath)) else fail("Missing src ids and media_uri; text fallback failed")
            }

            val msg = getMessageSync(srcChatId, srcMsgId) ?: return fail("GetMessage failed")
            val media = extractMedia(msg) ?: run {
                // fallback to text
                val content = TdApi.InputMessageText(captionFmt, lpOpts, false)
                val ok = sendMessage(targetChatId, content)
                return if (ok) Result.success(workDataOf(KEY_LOG_FILE to logFile.absolutePath)) else fail("No media in src message; text fallback failed")
            }

            val srcFile = downloadFileToLocal(media.fileId, timeoutSec = 90) ?: run {
                val content = TdApi.InputMessageText(captionFmt, lpOpts, false)
                val ok = sendMessage(targetChatId, content)
                return if (ok) Result.success(workDataOf(KEY_LOG_FILE to logFile.absolutePath)) else fail("DownloadFile failed; text fallback failed")
            }

            val inputFile = File(tmpDir, "in_${System.currentTimeMillis()}${extFor(media.kind)}")
            srcFile.copyTo(inputFile, overwrite = true)

            val finalFile = if (wmFile == null && rects.isEmpty()) {
                inputFile
            } else {
                val outFile = File(tmpDir, "out_${System.currentTimeMillis()}${extFor(media.kind)}")
                val ok = runFfmpegEdits(
                    input = inputFile,
                    output = outFile,
                    kind = media.kind,
                    rects = rects,
                    wmFile = wmFile,
                    wmX = wmX,
                    wmY = wmY
                )
                if (ok) outFile else inputFile
            }

            val content = buildContent(media.kind, finalFile, captionFmt)
            val sentOk = sendMessage(targetChatId, content)
            pushLine("SENT src-media kind=${'$'}{media.kind} edits=${(wmFile!=null)||rects.isNotEmpty()} ok=$sentOk")
            return if (sentOk) Result.success(workDataOf(KEY_LOG_FILE to logFile.absolutePath)) else fail("Send media failed")

        } catch (t: Throwable) {
            return fail("SendWorker crash: " + (t.message ?: t.javaClass.simpleName), t)
        } finally {
            runCatching { cleanupTmp(tmpDir) }
            pushLine("=== SendWorker finished ===")
        }
    }

    // ---------- helpers ----------

    private fun extFor(kind: Kind): String = when (kind) {
        Kind.PHOTO -> ".jpg"
        Kind.VIDEO -> ".mp4"
        Kind.ANIMATION -> ".mp4"
        else -> ".bin"
    }

    private fun detectKindFromMime(mime: String, nameOrUri: String): Kind {
        val m = mime.lowercase()
        val s = nameOrUri.lowercase()
        return when {
            m.startsWith("image/") -> Kind.PHOTO
            m.startsWith("video/") -> Kind.VIDEO
            s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp") -> Kind.PHOTO
            s.endsWith(".mp4") || s.endsWith(".mov") || s.endsWith(".mkv") || s.endsWith(".webm") -> Kind.VIDEO
            s.endsWith(".gif") -> Kind.ANIMATION
            else -> Kind.DOCUMENT
        }
    }

    private fun buildContent(kind: Kind, file: File, caption: TdApi.FormattedText): TdApi.InputMessageContent {
        val input = TdApi.InputFileLocal(file.absolutePath)
        return when (kind) {
            Kind.PHOTO -> TdApi.InputMessagePhoto().apply {
                photo = input
                this.caption = caption
            }
            Kind.VIDEO -> TdApi.InputMessageVideo().apply {
                video = input
                this.caption = caption
                supportsStreaming = true
            }
            Kind.ANIMATION -> TdApi.InputMessageAnimation().apply {
                animation = input
                this.caption = caption
            }
            else -> TdApi.InputMessageDocument().apply {
                document = input
                this.caption = caption
            }
        }
    }

    private fun cleanupTmp(tmpDir: File) {
        val files = tmpDir.listFiles() ?: return
        var n = 0
        for (f in files) {
            if (f.isFile) {
                if (runCatching { f.delete() }.getOrDefault(false)) n++
            }
        }
        Log.i(TAG, "cleanupTmp: deleted=$n in ${tmpDir.absolutePath}")
    }

    private fun parseRects(s: String): List<RectN> {
        if (s.isBlank()) return emptyList()
        return s.split(";").mapNotNull { part ->
            val p = part.trim()
            if (p.isBlank()) return@mapNotNull null
            val nums = p.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (nums.size != 4) return@mapNotNull null
            val l = nums[0].coerceIn(0f, 1f)
            val t = nums[1].coerceIn(0f, 1f)
            val r = nums[2].coerceIn(0f, 1f)
            val b = nums[3].coerceIn(0f, 1f)
            if (r <= l || b <= t) return@mapNotNull null
            RectN(l, t, r, b)
        }
    }

    private fun resolveUriToTempFile(uri: Uri, tmpDir: File, name: String): File? {
        return try {
            val out = File(tmpDir, name)
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            if (out.exists() && out.length() > 0) out else null
        } catch (t: Throwable) {
            Log.e(TAG, "resolveUriToTempFile failed: $uri", t)
            null
        }
    }

    private fun resolveTargetChatId(raw: String): Long? {
        val s = raw.trim()
        if (s.isBlank()) return null
        s.toLongOrNull()?.let { return it }

        val username = s.removePrefix("@")
        val latch = CountDownLatch(1)
        var chatId: Long? = null

        TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
            if (obj is TdApi.Chat) chatId = obj.id
            latch.countDown()
        }

        if (!latch.await(20, TimeUnit.SECONDS)) return null
        return chatId
    }

    private fun getMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) msg = obj
            latch.countDown()
        }
        if (!latch.await(25, TimeUnit.SECONDS)) return null
        return msg
    }

    private fun extractMedia(msg: TdApi.Message): MediaInfo? {
        val c = msg.content ?: return null
        return when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.photo?.size?.toLong() ?: 0L } ?: sizes.lastOrNull()
                val fid = best?.photo?.id ?: return null
                MediaInfo(Kind.PHOTO, fid)
            }
            is TdApi.MessageVideo -> {
                val fid = c.video?.video?.id ?: return null
                MediaInfo(Kind.VIDEO, fid)
            }
            is TdApi.MessageAnimation -> {
                val fid = c.animation?.animation?.id ?: return null
                MediaInfo(Kind.ANIMATION, fid)
            }
            is TdApi.MessageDocument -> {
                val fid = c.document?.document?.id ?: return null
                MediaInfo(Kind.DOCUMENT, fid)
            }
            else -> null
        }
    }

    private fun downloadFileToLocal(fileId: Int, timeoutSec: Int): File? {
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

    private fun sendMessage(chatId: Long, content: TdApi.InputMessageContent): Boolean {
        val latch = CountDownLatch(1)
        var ok = false

        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
        }

        TdLibManager.send(req) { obj ->
            ok = obj !is TdApi.Error
            if (!ok) Log.e(TAG, "SendMessage error: $obj")
            latch.countDown()
        }

        latch.await(30, TimeUnit.SECONDS)
        return ok
    }

    private fun q(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private fun runFfmpegEdits(
        input: File,
        output: File,
        kind: Kind,
        rects: List<RectN>,
        wmFile: File?,
        wmX: Float,
        wmY: Float
    ): Boolean {
        val hasWm = wmFile != null
        val hasBlur = rects.isNotEmpty()
        if (!hasWm && !hasBlur) return true

        val filters = mutableListOf<String>()
        var cur = "v0"

        // base
        filters += "[0:v]format=rgba[$cur]"

        // blur rects
        rects.forEachIndexed { i: Int, r: RectN ->
            val base = "base$i"
            val tmp = "tmp$i"
            val bl = "bl$i"
            val out = "v${i + 1}"

            val xCrop = "max(0,${r.l}*iw)"
            val yCrop = "max(0,${r.t}*ih)"
            val wCrop = "max(1,(${r.r}-${r.l})*iw)"
            val hCrop = "max(1,(${r.b}-${r.t})*ih)"
            val xOv = "max(0,${r.l}*main_w)"
            val yOv = "max(0,${r.t}*main_h)"

            filters += "[$cur]split=2[$base][$tmp]"
            filters += "[$tmp]crop=w=$wCrop:h=$hCrop:x=$xCrop:y=$yCrop,boxblur=10:1[$bl]"
            filters += "[$base][$bl]overlay=x=$xOv:y=$yOv[$out]"
            cur = out
        }

        val outLabel = if (hasWm) "outv" else cur

        if (hasWm) {
            val nx = wmX.coerceIn(0f, 1f)
            val ny = wmY.coerceIn(0f, 1f)

            // Scale watermark relative to main width:
            // photo -> smaller (0.12), video -> 0.18
            val scale = if (kind == Kind.PHOTO) 0.12f else 0.18f

            val vwm = "vwm"
            // order: [1:v] watermark, [$cur] main
            filters += "[1:v][$cur]scale2ref=w=main_w*${scale}:h=-1[wm][$vwm]"

            val xExpr = "max(0,min(main_w-overlay_w,${nx}*(main_w-overlay_w)))"
            val yExpr = "max(0,min(main_h-overlay_h,${ny}*(main_h-overlay_h)))"
            filters += "[$vwm][wm]overlay=x=$xExpr:y=$yExpr:format=auto[$outLabel]"
        }

        val fc = filters.joinToString(";")

        val args = mutableListOf<String>()
        args += "-y"
        args += "-i"; args += q(input.absolutePath)
        if (hasWm) { args += "-i"; args += q(wmFile!!.absolutePath) }
        args += "-filter_complex"; args += "\"$fc\""
        args += "-map"; args += "[$outLabel]"

        when (kind) {
            Kind.PHOTO -> {
                args += "-q:v"; args += "2"
                args += q(output.absolutePath)
            }
            Kind.VIDEO, Kind.ANIMATION -> {
                args += "-map"; args += "0:a?"
                args += "-c:v"; args += "libx264"
                args += "-preset"; args += "veryfast"
                args += "-crf"; args += "28"
                args += "-c:a"; args += "aac"
                args += "-b:a"; args += "128k"
                args += q(output.absolutePath)
            }
            else -> {
                args += q(output.absolutePath)
            }
        }

        val cmd = args.joinToString(" ")
        Log.i(TAG, "FFmpeg cmd: $cmd")

        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode
        val ok = rc != null && ReturnCode.isSuccess(rc)

        if (!ok) {
            Log.e(TAG, "FFmpeg failed rc=$rc")
            Log.e(TAG, "FFmpeg logs:\n" + session.allLogsAsString)
            return false
        }

        return output.exists() && output.length() > 0
    }
}

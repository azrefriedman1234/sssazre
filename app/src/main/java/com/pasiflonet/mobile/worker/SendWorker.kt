package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
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
        // 1) log file + tail
        val logDir = File(applicationContext.getExternalFilesDir(null), "pasiflonet_logs").apply { mkdirs() }
        val logFile = File(logDir, "send_${System.currentTimeMillis()}.log")
        val tail = ArrayDeque<String>(200)

        fun pushLine(line: String) {
            runCatching { logFile.appendText(line + "\n") }
            if (tail.size >= 200) tail.removeFirst()
            tail.addLast(line.take(500))
            setProgressAsync(workDataOf(KEY_LOG_TAIL to tail.joinToString("\n")))
        }

        pushLine("=== SendWorker started ===")
        pushLine("INPUT: " + inputData.keyValueMap.toString())
        pushLine("Thread=" + Thread.currentThread().name)

        // 2) FFmpegKit logs -> pushLine (best effort)
        runCatching {
            FFmpegKitConfig.enableLogCallback { log ->
                try {
                    pushLine("[FFMPEG ${log.level}] ${log.message}")
                } catch (_: Throwable) {
                }
            }
            FFmpegKitConfig.enableStatisticsCallback { st: Statistics ->
                try {
                    pushLine("STAT: t=${st.time} size=${st.size} br=${st.bitrate} sp=${st.speed}")
                } catch (_: Throwable) {
                }
            }
        }.onFailure { t ->
            pushLine("WARN: cannot enable FFmpegKit callbacks: " + (t.message ?: t.javaClass.simpleName))
        }

        // 3) read inputs
        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val captionText = inputData.getString(KEY_TEXT).orEmpty()
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)

        val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
        val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
        val wmX = inputData.getFloat(KEY_WM_X, -1f)
        val wmY = inputData.getFloat(KEY_WM_Y, -1f)

        val tmpDir = File(applicationContext.cacheDir, "pasiflonet_tmp").apply { mkdirs() }

        try {
            logI("start: sendWithMedia=$sendWithMedia srcChatId=$srcChatId srcMsgId=$srcMsgId target=$targetUsernameRaw")

            TdLibManager.init(applicationContext)
            TdLibManager.ensureClient()

            val targetChatId = resolveTargetChatId(targetUsernameRaw)
                ?: run {
                    logE("resolveTargetChatId failed for '$targetUsernameRaw'")
                    pushLine("RETURN: Result.failure")
                    return Result.failure(
                        workDataOf(
                            KEY_ERROR_MSG to "Cannot resolve target chat",
                            KEY_LOG_FILE to logFile.absolutePath,
                            KEY_LOG_TAIL to tail.joinToString("\n")
                        )
                    )
                }

            val captionFmt = TdApi.FormattedText(captionText, null)
            val lpOpts = TdApi.LinkPreviewOptions()

            // 4) TEXT only
            if (!sendWithMedia) {
                val content = TdApi.InputMessageText(captionFmt, lpOpts, false)
                if (!sendMessage(targetChatId, content)) {
                    logE("send TEXT only failed")
                    pushLine("RETURN: Result.failure")
                    return Result.failure(
                        workDataOf(
                            KEY_ERROR_MSG to "send text failed",
                            KEY_LOG_FILE to logFile.absolutePath,
                            KEY_LOG_TAIL to tail.joinToString("\n")
                        )
                    )
                }
                logI("sent TEXT only OK")
                pushLine("RETURN: Result.success")
                return Result.success(
                    workDataOf(
                        KEY_LOG_FILE to logFile.absolutePath,
                        KEY_LOG_TAIL to tail.joinToString("\n")
                    )
                )
            }

            // 5) with media
            

            // === Local media (from editor) support ===
            val localMediaUriStr = inputData.getString(KEY_MEDIA_URI).orEmpty().trim()
            val localMediaMime = inputData.getString(KEY_MEDIA_MIME).orEmpty().trim()

            if (localMediaUriStr.isNotBlank()) {
                val uri = Uri.parse(localMediaUriStr)

                val kindFromMime = when {
                    localMediaMime.equals("image/gif", ignoreCase = true) -> Kind.ANIMATION
                    localMediaMime.startsWith("image/", ignoreCase = true) -> Kind.PHOTO
                    localMediaMime.startsWith("video/", ignoreCase = true) -> Kind.VIDEO
                    else -> Kind.DOCUMENT
                }

                val inExt = when (kindFromMime) {
                    Kind.PHOTO -> "jpg"
                    Kind.VIDEO -> "mp4"
                    Kind.ANIMATION -> "mp4"
                    else -> "bin"
                }

                val inputFileLocal = resolveUriToTempFile(uri, tmpDir, "in_${System.currentTimeMillis()}.$inExt")
                    ?: run {
                        logE("local media_uri open failed: $localMediaUriStr")
                        pushLine("RETURN: Result.failure (media_uri open failed)")
                        return Result.failure()
                    }

                // watermark file (אם יש)
                val wmFileLocal: File? = if (watermarkUriStr.isNotBlank()) {
                    resolveUriToTempFile(Uri.parse(watermarkUriStr), tmpDir, "wm_${System.currentTimeMillis()}.png")
                } else null

                // blur rects
                val rectsLocal = parseRects(blurRectsStr)
                val needEditsLocal = (wmFileLocal != null) || rectsLocal.isNotEmpty()

                val finalFileLocal: File = if (!needEditsLocal) {
                    inputFileLocal
                } else {
                    val outExt = when (kindFromMime) {
                        Kind.PHOTO -> "jpg"
                        Kind.VIDEO -> "mp4"
                        Kind.ANIMATION -> "mp4"
                        else -> "bin"
                    }
                    val outFile = File(tmpDir, "out_${System.currentTimeMillis()}.$outExt")
                    val ok = runFfmpegEdits(
                        input = inputFileLocal,
                        output = outFile,
                        kind = kindFromMime,
                        rects = rectsLocal,
                        wmFile = wmFileLocal,
                        wmX = wmX,
                        wmY = wmY
                    )
                    if (!ok) inputFileLocal else outFile
                }

                val input = TdApi.InputFileLocal(finalFileLocal.absolutePath)
                val content: TdApi.InputMessageContent = when (kindFromMime) {
                    Kind.PHOTO -> TdApi.InputMessagePhoto().apply {
                        photo = input
                        caption = captionFmt
                    }
                    Kind.VIDEO -> TdApi.InputMessageVideo().apply {
                        video = input
                        caption = captionFmt
                        supportsStreaming = true
                    }
                    Kind.ANIMATION -> TdApi.InputMessageAnimation().apply {
                        animation = input
                        caption = captionFmt
                    }
                    else -> TdApi.InputMessageDocument().apply {
                        document = input
                        caption = captionFmt
                    }
                }

                val sentOk = sendMessage(targetChatId, content)
                logI("sent LOCAL media kind=$kindFromMime edited=$needEditsLocal final=${finalFileLocal.name} ok=$sentOk")
                return if (sentOk) Result.success() else Result.failure()
            }

if (srcChatId == 0L || srcMsgId == 0L) {
                logE("missing src ids")
                pushLine("RETURN: Result.failure")
                return Result.failure(
                    workDataOf(
                        KEY_ERROR_MSG to "missing src ids",
                        KEY_LOG_FILE to logFile.absolutePath,
                        KEY_LOG_TAIL to tail.joinToString("\n")
                    )
                )
            }

            // 6) fetch source message
            val msg = getMessageSync(srcChatId, srcMsgId) ?: run {
                logE("GetMessage failed")
                pushLine("RETURN: Result.failure")
                return Result.failure(
                    workDataOf(
                        KEY_ERROR_MSG to "GetMessage failed",
                        KEY_LOG_FILE to logFile.absolutePath,
                        KEY_LOG_TAIL to tail.joinToString("\n")
                    )
                )
            }

            // 7) extract media
            val media = extractMedia(msg) ?: run {
                logE("No media in source message -> fallback TEXT")
                val content = TdApi.InputMessageText(captionFmt, lpOpts, false)
                if (!sendMessage(targetChatId, content)) {
                    pushLine("RETURN: Result.failure")
                    return Result.failure(
                        workDataOf(
                            KEY_ERROR_MSG to "no media & text send failed",
                            KEY_LOG_FILE to logFile.absolutePath,
                            KEY_LOG_TAIL to tail.joinToString("\n")
                        )
                    )
                }
                pushLine("RETURN: Result.success")
                return Result.success(
                    workDataOf(
                        KEY_LOG_FILE to logFile.absolutePath,
                        KEY_LOG_TAIL to tail.joinToString("\n")
                    )
                )
            }

            // 8) download file via TDLib
            val srcFile = downloadFileToLocal(media.fileId, timeoutSec = 90) ?: run {
                logE("DownloadFile failed (fileId=${media.fileId}) -> fallback TEXT")
                val content = TdApi.InputMessageText(captionFmt, lpOpts, false)
                if (!sendMessage(targetChatId, content)) {
                    pushLine("RETURN: Result.failure")
                    return Result.failure(
                        workDataOf(
                            KEY_ERROR_MSG to "download failed & text send failed",
                            KEY_LOG_FILE to logFile.absolutePath,
                            KEY_LOG_TAIL to tail.joinToString("\n")
                        )
                    )
                }
                pushLine("RETURN: Result.success")
                return Result.success(
                    workDataOf(
                        KEY_LOG_FILE to logFile.absolutePath,
                        KEY_LOG_TAIL to tail.joinToString("\n")
                    )
                )
            }

            // 9) copy to our tmp
            val inExt = when (media.kind) {
                Kind.PHOTO -> "jpg"
                Kind.VIDEO -> "mp4"
                Kind.ANIMATION -> "mp4"
                else -> "bin"
            }
            val inputFile = File(tmpDir, "in_${System.currentTimeMillis()}." + inExt)
            srcFile.copyTo(inputFile, overwrite = true)

            // 10) watermark file (if any)
            val wmFile: File? = if (watermarkUriStr.isNotBlank()) {
                resolveUriToTempFile(Uri.parse(watermarkUriStr), tmpDir, "wm_${System.currentTimeMillis()}.png")
            } else null

            // 11) blur rects
            val rects = parseRects(blurRectsStr)

            val needEdits = (wmFile != null) || rects.isNotEmpty()
            val finalFile: File = if (!needEdits) {
                logI("no edits requested -> send original")
                inputFile
            } else {
                val outExt = when (media.kind) {
                    Kind.PHOTO -> "jpg"
                    Kind.VIDEO -> "mp4"
                    Kind.ANIMATION -> "mp4"
                    else -> "bin"
                }
                val outFile = File(tmpDir, "out_${System.currentTimeMillis()}." + outExt)
                val ok = runFfmpegEdits(
                    input = inputFile,
                    output = outFile,
                    kind = media.kind,
                    rects = rects,
                    wmFile = wmFile,
                    wmX = wmX,
                    wmY = wmY
                )
                if (!ok) {
                    logE("FFmpeg failed -> send original instead (still with caption)")
                    inputFile
                } else outFile
            }

            // 12) build TDLib content
            val input = TdApi.InputFileLocal(finalFile.absolutePath)
            val content: TdApi.InputMessageContent = when (media.kind) {
                Kind.PHOTO -> TdApi.InputMessagePhoto().apply {
                    photo = input
                    caption = captionFmt
                }
                Kind.VIDEO -> TdApi.InputMessageVideo().apply {
                    video = input
                    caption = captionFmt
                    supportsStreaming = true
                }
                Kind.ANIMATION -> TdApi.InputMessageAnimation().apply {
                    animation = input
                    caption = captionFmt
                }
                else -> TdApi.InputMessageDocument().apply {
                    document = input
                    caption = captionFmt
                }
            }

            val sentOk = sendMessage(targetChatId, content)
            logI("sent media kind=${media.kind} edited=$needEdits final=${finalFile.name} ok=$sentOk")
            pushLine(if (sentOk) "RETURN: Result.success" else "RETURN: Result.failure")

            return if (sentOk) {
                Result.success(
                    workDataOf(
                        KEY_LOG_FILE to logFile.absolutePath,
                        KEY_LOG_TAIL to tail.joinToString("\n")
                    )
                )
            } else {
                Result.failure(
                    workDataOf(
                        KEY_ERROR_MSG to "sendMessage failed",
                        KEY_LOG_FILE to logFile.absolutePath,
                        KEY_LOG_TAIL to tail.joinToString("\n")
                    )
                )
            }
        } catch (t: Throwable) {
            logE("crash in SendWorker", t)
            val msg = (t.message ?: t.javaClass.simpleName).take(300)
            pushLine("=== FAILED: $msg ===")
            pushLine(android.util.Log.getStackTraceString(t))
            pushLine("RETURN: Result.failure")
            return Result.failure(
                workDataOf(
                    KEY_ERROR_MSG to msg,
                    KEY_LOG_FILE to logFile.absolutePath,
                    KEY_LOG_TAIL to tail.joinToString("\n")
                )
            )
        } finally {
            runCatching { cleanupTmp(tmpDir) }
            pushLine("=== SendWorker finished ===")
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
        logI("cleanupTmp: deleted=$n in ${tmpDir.absolutePath}")
    }

    private fun logI(msg: String) = android.util.Log.i(TAG, msg)
    private fun logE(msg: String, t: Throwable? = null) = android.util.Log.e(TAG, msg, t)

    private fun parseRects(s: String): List<RectN> {
        if (s.isBlank()) return emptyList()
        return s.split(";")
            .mapNotNull { part ->
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
            logE("resolveUriToTempFile failed: $uri", t)
            null
        }
    }

    private fun resolveTargetChatId(raw: String): Long? {
        val s = raw.trim()
        if (s.isBlank()) return null

        // אם נתנו מספר chatId
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
        // בקש הורדה
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
            if (!ok) logE("SendMessage error: $obj")
            latch.countDown()
        }

        latch.await(30, TimeUnit.SECONDS)
        return ok
    }

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

        val filters = mutableListOf<String>()
        var cur = "v0"

        // base: always go to rgba
        filters += "[0:v]format=rgba[$cur]"

        // blur rectangles
        rects.forEachIndexed { i, r ->
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

        // watermark overlay (if exists)
        if (hasWm && wmFile != null) {
            val xExpr = "(${wmX.coerceIn(0f, 1f)}*(main_w-overlay_w))"
            val yExpr = "(${wmY.coerceIn(0f, 1f)}*(main_h-overlay_h))"

            val vScaled = "vwm"
            // scale watermark relative to current video
            filters += "[$cur][1:v]scale2ref=w=iw*0.18:h=-1[$vScaled][wm]"
            // simple overlay using xExpr/yExpr
            filters += "[$vScaled][wm]overlay=$xExpr:$yExpr[$outLabel]"
        }

        val fc = filters.joinToString(";")

        val args = mutableListOf<String>()
        args += "-y"
        args += "-i"; args += input.absolutePath
        if (hasWm && wmFile != null) {
            args += "-i"; args += wmFile.absolutePath
        }
        args += "-filter_complex"; args += fc
        args += "-map"; args += "[$outLabel]"

        when (kind) {
            Kind.PHOTO -> {
                args += "-q:v"; args += "2"
                args += output.absolutePath
            }
            Kind.VIDEO, Kind.ANIMATION -> {
                // keep audio if present
                args += "-map"; args += "0:a?"
                args += "-c:v"; args += "libx264"
                args += "-preset"; args += "veryfast"
                args += "-crf"; args += "28"
                args += "-c:a"; args += "aac"
                args += "-b:a"; args += "128k"
                args += output.absolutePath
            }
            else -> {
                args += output.absolutePath
            }
        }

        val cmd = args.joinToString(" ")
        logI("FFmpeg cmd: $cmd")

        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode
        val ok = ReturnCode.isSuccess(rc)

        if (!ok) {
            logE("FFmpeg failed rc=$rc")
            logE("FFmpeg logs:\n" + session.allLogsAsString)
        } else {
            logI("FFmpeg OK -> ${output.name} size=${output.length()}")
        }

        return ok && output.exists() && output.length() > 0
    }

}

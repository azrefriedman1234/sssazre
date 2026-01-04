package com.pasiflonet.mobile.worker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

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
        const val KEY_BLUR_RECTS = "blur_rects"
        const val KEY_WM_X = "wm_x"
        const val KEY_WM_Y = "wm_y"
    }

    private data class RectN(val l: Float, val t: Float, val r: Float, val b: Float)
    private enum class Kind { PHOTO, VIDEO, ANIMATION, OTHER }

    override fun doWork(): Result {
        try {
            TdLibManager.init(applicationContext)
            TdLibManager.ensureClient()
        } catch (t: Throwable) {
            Log.e("SendWorker", "TD init failed", t)
            return Result.retry()
        }

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsername = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val text = inputData.getString(KEY_TEXT).orEmpty()
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)

        val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
        val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
        val wmX = inputData.getFloat(KEY_WM_X, -1f)
        val wmY = inputData.getFloat(KEY_WM_Y, -1f)

        if (targetUsername.isBlank()) return Result.failure()
        val chatId = resolvePublicChatId(targetUsername) ?: return Result.retry()

        val captionFt = TdApi.FormattedText(text, null)

        // TEXT ONLY
        if (!sendWithMedia) {
            val content = TdApi.InputMessageText(captionFt, false, false)
            val ok = sendMessage(chatId, content)
            return if (ok) Result.success() else Result.retry()
        }

        // MEDIA: must have source ids
        if (srcChatId == 0L || srcMsgId == 0L) return Result.failure()

        val msg = getMessage(srcChatId, srcMsgId) ?: return Result.retry()
        val (fileId, kindGuess, mimeGuess) = extractMediaFile(msg) ?: run {
            // no media actually -> send as text
            val content = TdApi.InputMessageText(captionFt, false, false)
            val ok = sendMessage(chatId, content)
            return if (ok) Result.success() else Result.retry()
        }

        val inFile = downloadFile(fileId) ?: return Result.retry()

        val rects = parseRects(blurRectsStr)
        val needEdit = rects.isNotEmpty() || watermarkUriStr.isNotBlank()

        val outFile = if (needEdit) {
            val wmFile = if (watermarkUriStr.isNotBlank()) copyUriToCache(watermarkUriStr, "pf_wm") else null
            val edited = applyEditsWithFfmpeg(inFile, wmFile, rects, wmX, wmY, kindGuess, mimeGuess)
            // cleanup wm temp
            try { wmFile?.delete() } catch (_: Throwable) {}
            edited ?: return Result.retry()
        } else {
            inFile
        }

        val sendOk = when (kindGuess) {
            Kind.PHOTO -> {
                val content = TdApi.InputMessagePhoto(
                    TdApi.InputFileLocal(outFile.absolutePath),
                    null,
                    intArrayOf(),
                    0, 0,
                    captionFt,
                    false,
                    null,
                    false
                )
                sendMessage(chatId, content)
            }
            Kind.ANIMATION -> {
                val content = TdApi.InputMessageAnimation(
                    TdApi.InputFileLocal(outFile.absolutePath),
                    null,
                    intArrayOf(),
                    0, 0, 0,
                    captionFt,
                    false,
                    false
                )
                sendMessage(chatId, content)
            }
            Kind.VIDEO -> {
                val content = TdApi.InputMessageVideo(
                    TdApi.InputFileLocal(outFile.absolutePath),
                    null,
                    null,
                    0,
                    intArrayOf(),
                    0, 0, 0,
                    true,
                    captionFt,
                    false,
                    null,
                    false
                )
                sendMessage(chatId, content)
            }
            Kind.OTHER -> {
                // fallback: still try as document (if signature differs, keep as text)
                val content = TdApi.InputMessageText(captionFt, false, false)
                sendMessage(chatId, content)
            }
        }

        // cleanup only our temp outputs
        try {
            if (needEdit && outFile != inFile && outFile.name.startsWith("pf_")) outFile.delete()
        } catch (_: Throwable) {}

        return if (sendOk) Result.success() else Result.retry()
    }

    private fun resolvePublicChatId(usernameRaw: String): Long? {
        val username = usernameRaw.trim().removePrefix("@")
        val obj = sendSync(TdApi.SearchPublicChat(username) as TdApi.Function<TdApi.Object>, 30) ?: return null
        return if (obj is TdApi.Chat) obj.id else null
    }

    private fun getMessage(chatId: Long, msgId: Long): TdApi.Message? {
        val obj = sendSync(TdApi.GetMessage(chatId, msgId) as TdApi.Function<TdApi.Object>, 30) ?: return null
        return obj as? TdApi.Message
    }

    private fun sendMessage(chatId: Long, content: TdApi.InputMessageContent): Boolean {
        val obj = sendSync(TdApi.SendMessage(chatId, null, null, null, null, content) as TdApi.Function<TdApi.Object>, 60)
        return obj is TdApi.Message
    }

    private fun sendSync(f: TdApi.Function<TdApi.Object>, timeoutSec: Int): TdApi.Object? {
        val latch = CountDownLatch(1)
        var out: TdApi.Object? = null
        TdLibManager.send(f) { o ->
            out = o
            latch.countDown()
        }
        val ok = latch.await(timeoutSec.toLong(), TimeUnit.SECONDS)
        return if (ok) out else null
    }

    private fun downloadFile(fileId: Int): File? {
        // start
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false) as TdApi.Function<TdApi.Object>) { }
        val deadline = System.currentTimeMillis() + 120_000L
        while (System.currentTimeMillis() < deadline) {
            val obj = sendSync(TdApi.GetFile(fileId) as TdApi.Function<TdApi.Object>, 20) as? TdApi.File
            val path = obj?.local?.path
            val done = obj?.local?.isDownloadingCompleted ?: false
            if (!path.isNullOrBlank() && done) {
                val f = File(path)
                if (f.exists() && f.length() > 0) return f
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun extractMediaFile(msg: TdApi.Message): Triple<Int, Kind, String?>? {
        val c = msg.content ?: return null
        return when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray<TdApi.PhotoSize>()
                val best = sizes.maxByOrNull { it.width * it.height }?.photo ?: return null
                Triple(best.id, Kind.PHOTO, "image/jpeg")
            }
            is TdApi.MessageVideo -> {
                val f = c.video?.video ?: return null
                Triple(f.id, Kind.VIDEO, c.video?.mimeType)
            }
            is TdApi.MessageAnimation -> {
                val f = c.animation?.animation ?: return null
                // gif-as-animation
                Triple(f.id, Kind.ANIMATION, c.animation?.mimeType)
            }
            is TdApi.MessageDocument -> {
                val f = c.document?.document ?: return null
                val mime = c.document?.mimeType
                val kind = when {
                    mime?.startsWith("image/") == true -> Kind.PHOTO
                    mime?.startsWith("video/") == true -> Kind.VIDEO
                    mime == "image/gif" -> Kind.ANIMATION
                    else -> Kind.OTHER
                }
                Triple(f.id, kind, mime)
            }
            else -> null
        }
    }

    private fun parseRects(s: String): List<RectN> {
        if (s.isBlank()) return emptyList()
        val out = ArrayList<RectN>()
        val parts = s.split(";").map { it.trim() }.filter { it.isNotBlank() }
        for (p in parts) {
            val nums = p.split(",").map { it.trim() }
            if (nums.size != 4) continue
            val l = nums[0].toFloatOrNull() ?: continue
            val t = nums[1].toFloatOrNull() ?: continue
            val r = nums[2].toFloatOrNull() ?: continue
            val b = nums[3].toFloatOrNull() ?: continue
            out.add(RectN(l, t, r, b))
        }
        return out
    }

    private fun copyUriToCache(uriStr: String, prefix: String): File? {
        return try {
            val uri = Uri.parse(uriStr)
            val out = File(applicationContext.cacheDir, "${prefix}_${System.currentTimeMillis()}.png")
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            } ?: return null
            out
        } catch (_: Throwable) { null }
    }

    private fun getVideoSize(file: File): Pair<Int, Int> {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            val w = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0)
            val h = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0)
            r.release()
            Pair(max(1, w), max(1, h))
        } catch (_: Throwable) { Pair(1, 1) }
    }

    private fun applyEditsWithFfmpeg(
        inFile: File,
        wmFile: File?,
        rects: List<RectN>,
        wmX: Float,
        wmY: Float,
        kind: Kind,
        mime: String?
    ): File? {
        val isPhoto = (kind == Kind.PHOTO)
        val isAnim = (kind == Kind.ANIMATION) || (mime == "image/gif")
        val isVideo = (kind == Kind.VIDEO) || isAnim

        val (vw, vh) = if (isVideo) getVideoSize(inFile) else Pair(1080, 1080)

        // build delogo chain with pixel ints
        val delogos = StringBuilder()
        for (r in rects) {
            val x = (r.l * vw).toInt().coerceIn(0, vw - 2)
            val y = (r.t * vh).toInt().coerceIn(0, vh - 2)
            val w = max(2, ((r.r - r.l) * vw).toInt())
            val h = max(2, ((r.b - r.t) * vh).toInt())
            val ww = min(w, vw - x)
            val hh = min(h, vh - y)
            if (delogos.isNotEmpty()) delogos.append(",")
            delogos.append("delogo=x=$x:y=$y:w=$ww:h=$hh:show=0")
        }

        val out = if (isPhoto) {
            File(applicationContext.cacheDir, "pf_out_${System.currentTimeMillis()}.jpg")
        } else {
            // video/animation -> mp4 (works for tg video/animation)
            File(applicationContext.cacheDir, "pf_out_${System.currentTimeMillis()}.mp4")
        }

        val overlayX = if (wmX >= 0f) "(main_w-overlay_w)*$wmX" else "(main_w-overlay_w)-12"
        val overlayY = if (wmY >= 0f) "(main_h-overlay_h)*$wmY" else "(main_h-overlay_h)-12"

        val cmd = if (wmFile != null) {
            val filter0 = if (delogos.isNotEmpty()) "[0:v]$delogos[v0];" else "[0:v]null[v0];"
            val filter = filter0 + "[v0][1:v]overlay=x='$overlayX':y='$overlayY'[v]"
            if (isPhoto) {
                "-y -i '${inFile.absolutePath}' -i '${wmFile.absolutePath}' -filter_complex \"$filter\" -map \"[v]\" -q:v 2 '${out.absolutePath}'"
            } else {
                "-y -i '${inFile.absolutePath}' -i '${wmFile.absolutePath}' -filter_complex \"$filter\" -map \"[v]\" -map 0:a? -c:v libx264 -crf 23 -preset veryfast -c:a aac -b:a 128k -movflags +faststart '${out.absolutePath}'"
            }
        } else {
            val vf = if (delogos.isNotEmpty()) delogos.toString() else "null"
            if (isPhoto) {
                "-y -i '${inFile.absolutePath}' -vf \"$vf\" -q:v 2 '${out.absolutePath}'"
            } else {
                "-y -i '${inFile.absolutePath}' -vf \"$vf\" -map 0:v -map 0:a? -c:v libx264 -crf 23 -preset veryfast -c:a aac -b:a 128k -movflags +faststart '${out.absolutePath}'"
            }
        }

        Log.d("SendWorker", "ffmpeg: $cmd")
        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            Log.e("SendWorker", "ffmpeg failed: ${session.failStackTrace}")
            try { out.delete() } catch (_: Throwable) {}
            return null
        }
        if (!out.exists() || out.length() == 0L) return null
        return out
    }
}

package com.pasiflonet.mobile.worker

import android.content.Context
import android.util.Log
import android.graphics.BitmapFactory
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

        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_MEDIA_MIME = "media_mime"
        const val KEY_WATERMARK_URI = "watermark_uri"
        const val KEY_BLUR_RECTS = "blur_rects"

        const val KEY_WM_X = "wm_x"
        const val KEY_WM_Y = "wm_y"
    }

    private enum class Kind { PHOTO, VIDEO, DOCUMENT }

    private data class TdMedia(
        val file: File,
        val mime: String,
        val kind: Kind
    )

    override fun doWork(): Result {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val text = inputData.getString(KEY_TEXT).orEmpty()
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)

        val mediaUriStr = inputData.getString(KEY_MEDIA_URI).orEmpty().trim()
        val mediaMimeIn = inputData.getString(KEY_MEDIA_MIME).orEmpty().trim()

        val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
        val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
        val wmX = inputData.getFloat(KEY_WM_X, -1f)
        val wmY = inputData.getFloat(KEY_WM_Y, -1f)

        if (srcChatId == 0L || srcMsgId == 0L || targetUsernameRaw.isBlank()) return Result.failure()
        val username = targetUsernameRaw.removePrefix("@").trim()
        if (username.isBlank()) return Result.failure()

        // resolve @username -> chatId
        val targetChatId = resolvePublicChatId(username) ?: return Result.failure()

        val caption = TdApi.FormattedText(text, null)

        // If user chose text-only: send text
        if (!sendWithMedia) {
            val content = TdApi.InputMessageText().apply {
                this.text = caption
                this.linkPreviewOptions = null
                this.clearDraft = false
            }
            return sendContent(targetChatId, content)
        }

        // Media required. NO fallback to text.
        val tdMedia = if (mediaUriStr.isNotBlank()) {
            val uri = runCatching { Uri.parse(mediaUriStr) }.getOrNull() ?: return Result.failure()
            val local = copyUriToCache(uri, mediaMimeIn.ifBlank { "application/octet-stream" }) ?: return Result.failure()
            val kind = when {
                mediaMimeIn.lowercase(Locale.ROOT).startsWith("image/") -> Kind.PHOTO
                mediaMimeIn.lowercase(Locale.ROOT).startsWith("video/") -> Kind.VIDEO
                else -> Kind.DOCUMENT
            }
            TdMedia(local, mediaMimeIn.ifBlank { "application/octet-stream" }, kind)
        } else {
            fetchMediaFromTelegram(srcChatId, srcMsgId) ?: return Result.failure()
        }

        val finalFile = processEdits(
            input = tdMedia.file,
            mime = tdMedia.mime,
            kind = tdMedia.kind,
            blurRectsStr = blurRectsStr,
            watermarkUriStr = watermarkUriStr,
            wmX = wmX,
            wmY = wmY
        ) ?: tdMedia.file

        val inputFile = TdApi.InputFileLocal(
        // ✅ NO TEXT FALLBACK: אם ביקשו מדיה – שולחים תמיד קובץ (גם אם זה תמונה/וידאו)
        if (!finalFile.exists() || finalFile.length() <= 0L) {
            Log.e("SendWorker", "finalFile missing/empty -> abort (no text fallback)")
            return Result.failure()
        }

        val captionFt = TdApi.FormattedText(text, null)
        val content = TdApi.InputMessageDocument(inputFile, null, false, captionFt)

 var ok = false
        TdLibManager.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { obj ->
            ok = (obj is TdApi.Message)
            latch.countDown()
        }
        if (!latch.await(45, TimeUnit.SECONDS)) return Result.failure()
        return if (ok) Result.success() else Result.failure()
    }

    private fun fetchMediaFromTelegram(srcChatId: Long, srcMsgId: Long): TdMedia? {
        val msg = getMessageSync(srcChatId, srcMsgId) ?: return null
        val c = msg.content ?: return null

        val (fileId, mime, kind) = when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height } ?: return null
                Triple(best.photo.id, "image/jpeg", Kind.PHOTO)
            }
            is TdApi.MessageVideo -> {
                val v = c.video ?: return null
                Triple(v.video.id, v.mimeType?.ifBlank { "video/mp4" } ?: "video/mp4", Kind.VIDEO)
            }
            is TdApi.MessageAnimation -> {
                val a = c.animation ?: return null
                Triple(a.animation.id, a.mimeType?.ifBlank { "video/mp4" } ?: "video/mp4", Kind.VIDEO)
            }
            is TdApi.MessageDocument -> {
                val d = c.document ?: return null
                Triple(d.document.id, d.mimeType?.ifBlank { "application/octet-stream" } ?: "application/octet-stream", Kind.DOCUMENT)
            }
            else -> return null
        }

        val downloaded = ensureFileDownloaded(fileId) ?: return null
        val cached = copyFileToCache(downloaded, mime) ?: downloaded
        return TdMedia(cached, mime, kind)
    }

    private fun getMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) msg = obj
            latch.countDown()
        }
        if (!latch.await(20, TimeUnit.SECONDS)) return null
        return msg
    }

    private fun ensureFileDownloaded(fileId: Int, timeoutSec: Int = 140): File? {
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
            Thread.sleep(350)
        }
        return null
    }

    private fun copyFileToCache(src: File, mime: String): File? {
        return try {
            val ext = when {
                mime.startsWith("image/") -> ".jpg"
                mime.startsWith("video/") -> ".mp4"
                else -> ".bin"
            }
            val out = File(applicationContext.cacheDir, "tg_${System.currentTimeMillis()}$ext")
            src.inputStream().use { input ->
                FileOutputStream(out).use { fos -> input.copyTo(fos) }
            }
            out
        } catch (_: Throwable) { null }
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
        } catch (_: Throwable) { null }
    }

    // ---------- Edits (FFmpeg) ----------
    private fun processEdits(
        input: File,
        mime: String,
        kind: Kind,
        blurRectsStr: String,
        watermarkUriStr: String,
        wmX: Float,
        wmY: Float
    ): File? {
        val hasWm = watermarkUriStr.isNotBlank()
        val hasBlur = blurRectsStr.isNotBlank()
        if (!hasWm && !hasBlur) return null

        val wmFile = if (hasWm) resolveWatermarkFile(watermarkUriStr) else null

        return when (kind) {
            Kind.VIDEO -> {
                val (vw, vh) = getVideoSize(input) ?: return null
                val out = File(applicationContext.cacheDir, "edit_${System.currentTimeMillis()}.mp4")
                val wmW = (vw * 0.22f).roundToInt().coerceAtLeast(48)

                val filter = buildBlurOverlayFilter(
                    w = vw, h = vh,
                    rectsStr = blurRectsStr,
                    hasWm = (wmFile != null),
                    wmW = wmW,
                    wmX = wmX, wmY = wmY
                ) ?: return null

                val cmd = mutableListOf<String>()
                cmd += "-y"
                cmd += "-i"; cmd += input.absolutePath
                if (wmFile != null) { cmd += "-i"; cmd += wmFile.absolutePath }
                cmd += "-filter_complex"; cmd += filter
                cmd += "-map"; cmd += "[vout]"
                cmd += "-map"; cmd += "0:a?"
                cmd += "-c:v"; cmd += "libx264"
                cmd += "-crf"; cmd += "23"
                cmd += "-preset"; cmd += "veryfast"
                cmd += "-c:a"; cmd += "aac"
                cmd += out.absolutePath

                val session = FFmpegKit.execute(cmd.joinToString(" ") { q(it) })
                val rc = session.returnCode
                if (rc != null && rc.isValueSuccess) out else null
            }

            Kind.PHOTO -> {
                val (iw, ih) = getImageSize(input) ?: return null
                val out = File(applicationContext.cacheDir, "img_${System.currentTimeMillis()}.jpg")
                val wmW = (iw * 0.22f).roundToInt().coerceAtLeast(48)

                val filter = buildBlurOverlayFilter(
                    w = iw, h = ih,
                    rectsStr = blurRectsStr,
                    hasWm = (wmFile != null),
                    wmW = wmW,
                    wmX = wmX, wmY = wmY
                ) ?: return null

                val cmd = mutableListOf<String>()
                cmd += "-y"
                cmd += "-i"; cmd += input.absolutePath
                if (wmFile != null) { cmd += "-i"; cmd += wmFile.absolutePath }
                cmd += "-filter_complex"; cmd += filter
                cmd += "-map"; cmd += "[vout]"
                cmd += "-frames:v"; cmd += "1"
                cmd += "-q:v"; cmd += "2"
                cmd += out.absolutePath

                val session = FFmpegKit.execute(cmd.joinToString(" ") { q(it) })
                val rc = session.returnCode
                if (rc != null && rc.isValueSuccess) out else null
            }

            Kind.DOCUMENT -> null
        }
    }

    private fun q(s: String): String {
        // Safe quoting for FFmpegKit.execute(String)
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private fun resolveWatermarkFile(wm: String): File? {
        return try {
            val u = Uri.parse(wm)
            when (u.scheme?.lowercase(Locale.ROOT)) {
                "content" -> copyUriToCache(u, "image/png")
                "file" -> File(u.path ?: return null).takeIf { it.exists() }
                else -> File(wm).takeIf { it.exists() }
            }
        } catch (_: Throwable) {
            File(wm).takeIf { it.exists() }
        }
    }

    private fun buildBlurOverlayFilter(
        w: Int,
        h: Int,
        rectsStr: String,
        hasWm: Boolean,
        wmW: Int,
        wmX: Float,
        wmY: Float
    ): String? {
        val rects = parseRects(rectsStr).mapNotNull { rr ->
            val x = (rr[0] * w).roundToInt().coerceIn(0, w - 2)
            val y = (rr[1] * h).roundToInt().coerceIn(0, h - 2)
            val ww = ((rr[2] - rr[0]) * w).roundToInt().coerceAtLeast(2).coerceIn(2, w - x)
            val hh = ((rr[3] - rr[1]) * h).roundToInt().coerceAtLeast(2).coerceIn(2, h - y)
            intArrayOf(x, y, ww, hh)
        }

        val (wmPx, wmPy) = computeWatermarkXY(w, h, wmW, wmX, wmY)

        // No blur, only watermark
        if (rects.isEmpty()) {
            if (!hasWm) return null
            return "[1:v]scale=${wmW}:-1[wm];[0:v][wm]overlay=${wmPx}:${wmPy}[vout]"
        }

        val n = rects.size
        val sb = StringBuilder()

        sb.append("[0:v]split=").append(n + 1)
        for (i in 0..n) sb.append("[v").append(i).append("]")
        sb.append(";")

        for (i in 0 until n) {
            val r = rects[i]
            sb.append("[v").append(i + 1).append("]")
            sb.append("crop=").append(r[2]).append(":").append(r[3]).append(":").append(r[0]).append(":").append(r[1])
            sb.append(",boxblur=10:1")
            sb.append("[b").append(i).append("];")
        }

        var base = "[v0]"
        for (i in 0 until n) {
            val r = rects[i]
            sb.append(base).append("[b").append(i).append("]")
                .append("overlay=").append(r[0]).append(":").append(r[1])
                .append("[o").append(i).append("];")
            base = "[o$i]"
        }

        if (hasWm) {
            sb.append(base).append("copy[vb];")
            sb.append("[1:v]scale=").append(wmW).append(":-1[wm];")
            sb.append("[vb][wm]overlay=").append(wmPx).append(":").append(wmPy).append("[vout]")
        } else {
            sb.append(base).append("copy[vout]")
        }

        return sb.toString()
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

    private fun computeWatermarkXY(w: Int, h: Int, wmW: Int, wmX: Float, wmY: Float): Pair<Int, Int> {
        val pad = (w * 0.02f).roundToInt().coerceAtLeast(12)
        if (wmX < 0f || wmY < 0f) {
            val x = (w - wmW - pad).coerceAtLeast(0)
            val y = (h - (wmW / 2) - pad).coerceAtLeast(0)
            return Pair(x, y)
        }
        val x = (wmX.coerceIn(0f, 1f) * w).roundToInt().coerceIn(0, w - 2)
        val y = (wmY.coerceIn(0f, 1f) * h).roundToInt().coerceIn(0, h - 2)
        return Pair(x, y)
    }

    private fun getVideoSize(f: File): Pair<Int, Int>? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            r.release()
            if (w > 0 && h > 0) Pair(w, h) else null
        } catch (_: Throwable) { null }
    }

    private fun getImageSize(f: File): Pair<Int, Int>? {
        return try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opt)
            if (opt.outWidth > 0 && opt.outHeight > 0) Pair(opt.outWidth, opt.outHeight) else null
        } catch (_: Throwable) { null }
    }
}

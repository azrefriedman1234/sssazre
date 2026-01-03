package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.td.TdMediaDownloader
import com.pasiflonet.mobile.td.TdMessageMapper
import com.pasiflonet.mobile.ui.overlay.BlurRectNorm
import com.pasiflonet.mobile.ui.overlay.EditPlan
import com.pasiflonet.mobile.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

class SendWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
                const val KEY_TARGET_USERNAME = "target_username"
private const val TAG = "SendWorker"

        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_CHAT_ID = "target_chat_id"
        const val KEY_TEXT = "text"
        const val KEY_TRANSLATION = "translation"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"
        const val KEY_WATERMARK_URI = "watermark_uri"
        const val KEY_EDIT_PLAN_JSON = "edit_plan_json"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            TdLibManager.ensureClient()

            val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
            val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
            val targetChatId = inputData.getLong(KEY_TARGET_CHAT_ID, 0L)

            val editedText = inputData.getString(KEY_TEXT).orEmpty()
            val translated = inputData.getString(KEY_TRANSLATION).orEmpty()
            val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)

            val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty()
            val planJson = inputData.getString(KEY_EDIT_PLAN_JSON).orEmpty()

            val finalText = if (translated.isNotBlank()) {
                "${editedText.trim()}\n\n${translated.trim()}"
            } else {
                editedText.trim()
            }

            if (!sendWithMedia) {
                val mid = sendText(targetChatId, finalText)
                toastOk(mid)
                return@withContext Result.success()
            }

            val msg = TdMediaDownloader.getMessage(srcChatId, srcMsgId) ?: return@withContext Result.retry()

            val mediaFileId = TdMessageMapper.getMainMediaFileId(msg.content)
            if (mediaFileId == null) {
                val mid = sendText(targetChatId, finalText)
                toastOk(mid)
                return@withContext Result.success()
            }

            val srcPath = TdMediaDownloader.downloadFile(mediaFileId, priority = 32, synchronous = true)
                ?: return@withContext Result.retry()

            val plan = if (planJson.isNotBlank()) JsonUtil.fromJson<EditPlan>(planJson) else EditPlan()

            val srcFile = File(srcPath)

            val wmFile = if (watermarkUriStr.isNotBlank())
                copyUriToCache(Uri.parse(watermarkUriStr), "watermark.png")
            else null

            val isVideo = srcFile.extension.lowercase() in setOf("mp4", "mkv", "webm", "mov")
            val isImage = srcFile.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")

            val out = if (isImage)
                File(applicationContext.cacheDir, "out_${System.currentTimeMillis()}.jpg")
            else
                File(applicationContext.cacheDir, "out_${System.currentTimeMillis()}.mp4")

            val editedOk = if (wmFile != null || plan.blurRects.isNotEmpty()) {
                runFfmpegEdit(srcFile, out, wmFile, plan, isVideo)
            } else {
                srcFile.copyTo(out, overwrite = true)
                true
            }

            if (!editedOk) return@withContext Result.retry()

            val mid = sendMedia(targetChatId, out, finalText, isVideo, isImage)
            toastOk(mid)
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Worker failed", t)
            Result.retry()
        }
    }

    private suspend fun sendText(targetChatId: Long, text: String): Long {
        val content = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text, null)
            // לא מכריחים linkPreviewOptions / disablePreview כדי להיות תואם לכל גרסה
        }

        val req = TdApi.SendMessage().apply {
            chatId = targetChatId
            inputMessageContent = content
        }

        val msg = tdAwaitMessage(req)
        return msg?.id ?: 0L
    }

    private suspend fun sendMedia(
        targetChatId: Long,
        file: File,
        caption: String,
        isVideo: Boolean,
        isImage: Boolean
    ): Long {
        val inputFile = TdApi.InputFileLocal(file.absolutePath)
        val formatted = TdApi.FormattedText(caption, null)

        val content: TdApi.InputMessageContent = when {
            isImage -> TdApi.InputMessagePhoto().apply {
                photo = inputFile
                this.caption = formatted
            }
            isVideo -> TdApi.InputMessageVideo().apply {
                video = inputFile
                this.caption = formatted
                supportsStreaming = true
            }
            else -> TdApi.InputMessageDocument().apply {
                document = inputFile
                this.caption = formatted
            }
        }

        val req = TdApi.SendMessage().apply {
            chatId = targetChatId
            inputMessageContent = content
        }

        val msg = tdAwaitMessage(req)
        return msg?.id ?: 0L
    }

    private fun toastOk(messageId: Long) {
        val text = if (messageId != 0L) "✅ הודעה נשלחה בהצלחה • ID: $messageId" else "✅ הודעה נשלחה בהצלחה"
        Log.i(TAG, text)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun tdAwaitMessage(req: TdApi.SendMessage): TdApi.Message? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            TdLibManager.send(req) { obj ->
                when (obj.constructor) {
                    TdApi.Message.CONSTRUCTOR -> cont.resume(obj as TdApi.Message)
                    TdApi.Error.CONSTRUCTOR -> {
                        val e = obj as TdApi.Error
                        Log.e(TAG, "Send error: ${e.message}")
                        cont.resume(null)
                    }
                    else -> cont.resume(null)
                }
            }
        }

    private fun copyUriToCache(uri: Uri, outName: String): File? {
        return try {
            val out = File(applicationContext.cacheDir, outName)
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            out
        } catch (t: Throwable) {
            Log.e(TAG, "copyUriToCache failed", t)
            null
        }
    }

    private fun runFfmpegEdit(src: File, out: File, watermark: File?, plan: EditPlan, isVideo: Boolean): Boolean {
        val cmd = buildFfmpegCommand(src, out, watermark, plan, isVideo)
        Log.i(TAG, "FFmpeg cmd: $cmd")
        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode
        return ReturnCode.isSuccess(rc)
    }

    private fun buildFfmpegCommand(src: File, out: File, watermark: File?, plan: EditPlan, isVideo: Boolean): String {
        val hasWm = watermark != null && plan.watermarkX != null && plan.watermarkY != null
        val rects = plan.blurRects

        val filters = mutableListOf<String>()
        var cur = "[0:v]"
        var idx = 0

        fun rectToCropExpr(r: BlurRectNorm): String {
            val wExpr = "(iw*${(r.right - r.left).coerceIn(0f, 1f)})"
            val hExpr = "(ih*${(r.bottom - r.top).coerceIn(0f, 1f)})"
            val xExpr = "(iw*${r.left.coerceIn(0f, 1f)})"
            val yExpr = "(ih*${r.top.coerceIn(0f, 1f)})"
            return "w=$wExpr:h=$hExpr:x=$xExpr:y=$yExpr"
        }

        rects.forEach { r ->
            val crop = rectToCropExpr(r)
            val blurLabel = "b$idx"
            val outLabel = "v$idx"
            filters += "$cur split=2 [base$idx][tmp$idx]"
            filters += "[tmp$idx]crop=$crop,boxblur=luma_radius=20:luma_power=2:chroma_radius=20:chroma_power=2[$blurLabel]"
            filters += "[base$idx][$blurLabel]overlay=x=(iw*${r.left}):y=(ih*${r.top})[$outLabel]"
            cur = "[$outLabel]"
            idx++
        }

        if (hasWm) {
            val wmLabel = "wm"
            filters += "[1:v]scale=iw*0.22:-1[$wmLabel]"
            val xExpr = "(iw*${plan.watermarkX} - overlay_w/2)"
            val yExpr = "(ih*${plan.watermarkY} - overlay_h/2)"
            val outLabel = "vwm"
            filters += "$cur[$wmLabel]overlay=x=$xExpr:y=$yExpr[$outLabel]"
            cur = "[$outLabel]"
        }

        val filterComplex = if (filters.isNotEmpty()) {
            filters.joinToString(";") + ";" + "$cur format=yuv420p[v]"
        } else null

        val in0 = "-i ${quote(src.absolutePath)}"
        val in1 = if (hasWm) "-i ${quote(watermark!!.absolutePath)}" else ""
        val fc = if (filterComplex != null) "-filter_complex ${quote(filterComplex)} -map [v]" else ""
        val mapAudio = if (isVideo) "-map 0:a? -c:a aac -b:a 128k" else ""
        val outArgs = if (isVideo) {
            "-c:v libx264 -crf 28 -preset veryfast $mapAudio -movflags +faststart"
        } else {
            "-frames:v 1 -q:v 2"
        }

        return listOf("ffmpeg", "-y", in0, in1, fc, outArgs, quote(out.absolutePath))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun quote(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }
}

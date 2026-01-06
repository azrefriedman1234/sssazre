package com.pasiflonet.mobile.util

import android.content.Context
import android.net.Uri
import com.pasiflonet.mobile.ui.editor.OverlayEditorView
import java.io.File
import java.util.UUID

object VideoEditPipeline {

    data class Result(val outFile: File, val logTail: String)

    fun editVideoBlocking(
        ctx: Context,
        input: Uri,
        blurRects: List<OverlayEditorView.NRect>,
        watermarkText: String?
    ): Result {
        val tmpDir = File(ctx.cacheDir, "pasiflonet_tmp").apply { mkdirs() }
        val out = File(tmpDir, "edited_${UUID.randomUUID()}.mp4")

        val inPath = UriFile.resolveToPath(ctx, input)
            ?: throw IllegalStateException("Cannot resolve input file path from Uri")

        // build filter_complex
        // base video label: [0:v]
        val fc = StringBuilder()
        fc.append("[0:v]format=yuv420p")

        // apply blur for each rect (convert normalized -> pixel using iw/ih)
        // use boxblur on cropped region then overlay back
        // chain:
        // [0:v]split=n+1[v0][v1]..; each blur: crop -> boxblur -> overlay on base
        // simpler: use drawbox to visualize? (preview is in UI; export does real blur)
        var base = "[v0]"
        val n = blurRects.size
        fc.clear()
        fc.append("[0:v]split=${n + 1}")
        for (i in 0..n) fc.append("[v$i]")
        fc.append(";")

        // start with v0 as base
        base = "[v0]"
        for (i in 1..n) {
            val r = blurRects[i - 1]
            val l = "(${r.l}*iw)"; val t = "(${r.t}*ih)"; val w = "((${r.r}-${r.l})*iw)"; val h="((${r.b}-${r.t})*ih)"
            fc.append("$base")
            fc.append("[v$i]")

            // crop from v_i, blur it, then overlay on base
            fc.append(";[v$i]crop=w=$w:h=$h:x=$l:y=$t,boxblur=20:1[blur$i];")
            fc.append("$base[blur$i]overlay=x=$l:y=$t")
            base = "[vb$i]"
            fc.append("$base;")
        }

        // watermark text (optional)
        val wm = watermarkText?.trim().orEmpty()
        val finalLabel = if (wm.isNotEmpty()) {
            val safe = wm.replace(":", "\\:").replace("'", "\\'")
            fc.append("$base" + "drawtext=text='$safe':x=(w-text_w-24):y=(h-text_h-24):fontsize=28:fontcolor=white@0.9:box=1:boxcolor=black@0.35:boxborderw=12[vout];")
            "[vout]"
        } else {
            // rename base to vout
            fc.append("$base" + "copy[vout];")
            "[vout]"
        }

        val filter = fc.toString()

        val cmd = buildString {
            append("-y ")
            append("-i \"$inPath\" ")
            append("-filter_complex \"$filter\" ")
            append("-map \"$finalLabel\" -map 0:a? ")
            append("-c:v libx264 -preset veryfast -crf 24 ")
            append("-c:a aac -b:a 128k ")
            append("\"${out.absolutePath}\"")
        }

        val tail = StringBuilder()
        try {
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
            tail.append(session.allLogsAsString.takeLast(10000))
            val rc = session.returnCode
            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(rc)) {
                throw IllegalStateException("FFmpeg failed: $rc\n${tail}")
            }
        } catch (t: Throwable) {
            throw t
        }

        if (!out.exists() || out.length() < 1000) throw IllegalStateException("Output file not created")
        return Result(out, tail.toString())
    }
}

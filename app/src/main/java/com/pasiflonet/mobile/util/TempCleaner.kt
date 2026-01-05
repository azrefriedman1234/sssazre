package com.pasiflonet.mobile.util

import android.content.Context
import java.io.File
import kotlin.math.roundToLong

object TempCleaner {
    // SAFE clear: delete only cacheDir/pasiflonet_tmp (+ externalCacheDir/pasiflonet_tmp)
    fun clearTemp(ctx: android.content.Context): Pair<Int, Long> {
        var files = 0
        var bytes = 0L

        fun deleteRec(f: File) {
            if (!f.exists()) return
            if (f.isDirectory) {
                f.listFiles()?.forEach { deleteRec(it) }
            }
            val len = try { if (f.isFile) f.length() else 0L } catch (_: Throwable) { 0L }
            if (f.delete()) { files += 1; bytes += len }
        }

        try {
            val d = File(ctx.cacheDir, "pasiflonet_tmp")
            deleteRec(d)
        } catch (_: Throwable) { }

        try {
            val ext = ctx.externalCacheDir
            if (ext != null) {
                val d2 = File(ext, "pasiflonet_tmp")
                deleteRec(d2)
            }
        } catch (_: Throwable) { }

        return files to bytes
    }


    data class Result(val deletedFiles: Int, val freedBytes: Long)

    fun tempDir(ctx: Context): File {
        val d = File(ctx.cacheDir, "pasiflonet_tmp")
        d.mkdirs()
        return d
    }

    fun clean(ctx: Context): Result {
        val d = tempDir(ctx)
        if (!d.exists()) return Result(0, 0)

        var count = 0
        var bytes = 0L

        d.listFiles()?.forEach { f ->
            if (f.isFile) {
                bytes += f.length()
                if (f.delete()) count++
            } else if (f.isDirectory) {
                f.deleteRecursively()
                count++
            }
        }

        return Result(count, bytes)
    }

    fun fmt(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> "${(mb * 10).roundToLong() / 10.0} MB"
            kb >= 1 -> "${(kb * 10).roundToLong() / 10.0} KB"
            else -> "$bytes B"
        }
    }
}

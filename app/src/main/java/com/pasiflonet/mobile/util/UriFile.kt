package com.pasiflonet.mobile.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object UriFile {
    fun resolveToPath(ctx: Context, uri: Uri): String? {
        // If it's file://
        if (uri.scheme == "file") return uri.path

        // Copy content:// to cache file
        return try {
            val name = queryName(ctx, uri) ?: "in_${System.currentTimeMillis()}"
            val tmpDir = File(ctx.cacheDir, "pasiflonet_tmp").apply { mkdirs() }
            val out = File(tmpDir, name)
            ctx.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(out).use { outp ->
                    inp.copyTo(outp)
                }
            }
            out.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryName(ctx: Context, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = ctx.contentResolver.query(uri, null, null, null, null)
            val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
            if (cursor != null && cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        } catch (_: Throwable) {
            null
        } finally {
            cursor?.close()
        }
    }
}

package io.github.sorjo.pdnsautoswitch

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.IOException

/** Explicit export of a snapshot. MediaStore needs no broad storage permission on API 33+. */
object ObservationExport {
    const val FILE_NAME = "pDNS auto switcher.log"

    fun save(context: Context, lines: List<String>) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            // Match .log on this Android build; text/plain can force an unwanted .txt suffix.
            put(MediaStore.MediaColumns.MIME_TYPE,
                MimeTypeMap.getSingleton().getMimeTypeFromExtension("log") ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("Cannot create export")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open export")
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                lines.forEach { writer.append(it).append('\n') }
            }
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "Cannot publish export" }
        } catch (error: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw error
        }
    }
}

package com.subtitleedit.editor

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.subtitleedit.repository.SubtitleRepository
import java.io.File
import java.nio.charset.Charset

/** File and URI session operations kept independent from editor view state. */
internal class EditorFileSessionController(
    private val context: Context,
    private val repository: SubtitleRepository,
    private val contentResolver: ContentResolver = context.contentResolver
) {
    fun readFile(file: File, charset: Charset): String = repository.readFile(file, charset)

    fun readUri(uri: Uri, charset: Charset? = null): String =
        repository.readUri(context, uri, charset)

    fun fileName(uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() } ?: "未命名"
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return fallback

        // A readable external provider may omit DISPLAY_NAME or reject metadata queries.
        // That should not prevent opening the content we have already read successfully.
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }
}

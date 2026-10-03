package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Resolves the user-visible file name of a content or file Uri. */
internal object UriDisplayName {
    fun of(context: Context, uri: Uri, fallback: String = uri.lastPathSegment ?: "unknown"): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
                }
        }.getOrNull() ?: fallback
}

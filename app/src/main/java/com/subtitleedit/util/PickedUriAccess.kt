package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Local picker results and older persisted document selections share the existing consumers. */
internal object PickedUriAccess {
    fun file(uri: Uri): File? = when (uri.scheme) {
        null, "", "file" -> uri.path?.let(::File)
        else -> null
    }

    fun retainPermission(context: Context, uri: Uri, flags: Int) {
        if (uri.scheme == "content") {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    fun directory(context: Context, uri: Uri): DocumentFile? = file(uri)?.let(DocumentFile::fromFile)
        ?: DocumentFile.fromTreeUri(context, uri)

    fun document(context: Context, uri: Uri): DocumentFile? = file(uri)?.let(DocumentFile::fromFile)
        ?: DocumentFile.fromSingleUri(context, uri)
}

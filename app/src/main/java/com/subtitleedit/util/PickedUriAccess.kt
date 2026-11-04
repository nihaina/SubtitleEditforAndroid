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

    /** RawDocumentFile appends a MIME suffix; local exports already include their extension. */
    fun createFile(directory: DocumentFile, mimeType: String, name: String): DocumentFile? {
        val localDirectory = file(directory.uri) ?: return directory.createFile(mimeType, name)
        if (!localDirectory.isDirectory || File(name).name != name) return null
        val target = File(localDirectory, name)
        return if (target.createNewFile()) DocumentFile.fromFile(target) else null
    }
}

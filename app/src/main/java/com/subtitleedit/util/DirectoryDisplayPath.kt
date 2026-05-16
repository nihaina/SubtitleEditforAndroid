package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

object DirectoryDisplayPath {
    fun fromUri(context: Context, uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.path ?: uri.toString()
        }

        val treeDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull()

        val parsedPath = treeDocumentId?.let { documentIdToPath(it) }
        if (!parsedPath.isNullOrBlank()) {
            return parsedPath
        }

        val docName = DocumentFile.fromTreeUri(context, uri)?.name
        return docName?.let { "$it (${uri})" } ?: uri.toString()
    }

    private fun documentIdToPath(documentId: String): String? {
        val parts = documentId.split(":", limit = 2)
        val volume = parts.getOrNull(0) ?: return null
        val relativePath = parts.getOrNull(1).orEmpty()

        val rootPath = when {
            volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            volume.equals("home", ignoreCase = true) ->
                File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS).absolutePath
            else -> "/storage/$volume"
        }

        return if (relativePath.isBlank()) {
            rootPath
        } else {
            "$rootPath/$relativePath"
        }
    }
}

package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** Resolves local SAF selections to paths required by ONNX external tensor files. */
internal object Qwen3ForcedAlignerPathResolver {
    fun resolve(context: Context, uri: Uri): File? {
        if (uri.scheme.isNullOrBlank() || uri.scheme == "file") {
            return uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        }
        if (uri.scheme != "content") return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val path = when (uri.authority) {
            "com.android.externalstorage.documents" -> documentId?.let { externalStorageFile(context, it) }
            "com.android.providers.downloads.documents" -> documentId
                ?.takeIf { it.startsWith("raw:") }
                ?.removePrefix("raw:")
                ?.let(::File)
            else -> null
        }
        return (path?.takeIf { it.isFile && it.canRead() } ?: queryLocalPath(context, uri))
            ?.takeIf { it.isFile && it.canRead() }
    }

    private fun externalStorageFile(context: Context, documentId: String): File? {
        val volumeId = documentId.substringBefore(':', "")
        val relative = documentId.substringAfter(':', "")
        if (volumeId.isBlank() || relative.isBlank() || relative.startsWith('/') ||
            relative.split('/').any { it == ".." }
        ) return null
        val root = when {
            volumeId.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            volumeId.equals("home", ignoreCase = true) ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val manager = context.getSystemService(StorageManager::class.java)
                manager.storageVolumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
                    ?.directory ?: File("/storage", volumeId)
            }
            else -> File("/storage", volumeId)
        }
        return File(root, relative)
    }

    private fun queryLocalPath(context: Context, uri: Uri): File? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column)?.let(::File)
                else null
            }
    }.getOrNull()
}

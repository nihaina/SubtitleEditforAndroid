package com.subtitleedit.editor

import android.content.Context
import android.net.Uri
import com.subtitleedit.repository.SubtitleRepository
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Performs subtitle persistence off the main thread and returns a small immutable result. */
internal class EditorSaveSessionController(
    private val context: Context,
    private val repository: SubtitleRepository
) {
    data class Result(val success: Boolean, val error: Throwable? = null)

    suspend fun saveFile(file: File, content: String, charset: Charset): Result =
        runCatching {
            withContext(Dispatchers.IO) { repository.writeFile(file, content, charset) }
        }.fold({ Result(true) }, { Result(false, it) })

    suspend fun saveUri(uri: Uri, content: String, charset: Charset): Result =
        runCatching {
            withContext(Dispatchers.IO) { repository.writeUri(context, uri, content, charset) }
        }.fold({ Result(true) }, { Result(false, it) })
}

package com.subtitleedit

import android.app.Application
import android.content.Intent
import com.subtitleedit.util.PickedUriAccess
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import com.subtitleedit.util.SubtitleFormatConverter
import com.subtitleedit.util.UriDisplayName

internal data class SubtitleFormatSelectUiState(
    val selectedUri: Uri? = null,
    val selectedName: String = "",
    val selectedSize: Long? = null
)

internal class SubtitleFormatSelectViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AppViewModel<SubtitleFormatSelectUiState, Nothing>(
    application,
    SubtitleFormatSelectUiState(
        selectedUri = savedState.get<String>(KEY_SELECTED_URI)?.let(Uri::parse),
        selectedName = savedState.get<String>(KEY_SELECTED_NAME).orEmpty(),
        selectedSize = savedState.get<Long>(KEY_SELECTED_SIZE)
    )
) {
    fun onFilePicked(uri: Uri) {
        val name = queryDisplayName(uri)
        if (!SubtitleFormatConverter.isSupportedSourceFileName(name)) {
            toast(R.string.subtitle_format_select_unsupported, duration = Toast.LENGTH_LONG)
            return
        }
        runCatching {
            PickedUriAccess.retainPermission(app,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val size = queryFileSize(uri)
        savedState[KEY_SELECTED_URI] = uri.toString()
        savedState[KEY_SELECTED_NAME] = name
        savedState[KEY_SELECTED_SIZE] = size
        setState { copy(selectedUri = uri, selectedName = name, selectedSize = size) }
    }

    private fun queryDisplayName(uri: Uri): String =
        UriDisplayName.of(app, uri, string(R.string.unknown_file))

    private fun queryFileSize(uri: Uri): Long? = runCatching {
        PickedUriAccess.file(uri)?.let { file ->
            return@runCatching file.takeIf { it.isFile }?.length()
        }
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) {
                cursor.getLong(column).takeIf { it >= 0L }
            } else null
        }
    }.getOrNull()

    private companion object {
        const val KEY_SELECTED_URI = "selected_uri"
        const val KEY_SELECTED_NAME = "selected_name"
        const val KEY_SELECTED_SIZE = "selected_size"
    }
}

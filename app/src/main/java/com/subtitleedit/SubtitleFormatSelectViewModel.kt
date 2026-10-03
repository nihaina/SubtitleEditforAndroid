package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle

internal data class SubtitleFormatSelectUiState(
    val selectedUri: Uri? = null,
    val selectedName: String = ""
)

internal class SubtitleFormatSelectViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AppViewModel<SubtitleFormatSelectUiState, Nothing>(
    application,
    SubtitleFormatSelectUiState(
        selectedUri = savedState.get<String>(KEY_SELECTED_URI)?.let(Uri::parse),
        selectedName = savedState.get<String>(KEY_SELECTED_NAME).orEmpty()
    )
) {
    fun onFilePicked(uri: Uri) {
        val name = queryDisplayName(uri) ?: string(R.string.unknown_file)
        if (name.substringAfterLast('.', "").lowercase() !in supportedExtensions) {
            toast(R.string.subtitle_format_select_unsupported, duration = Toast.LENGTH_LONG)
            return
        }
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        savedState[KEY_SELECTED_URI] = uri.toString()
        savedState[KEY_SELECTED_NAME] = name
        setState { copy(selectedUri = uri, selectedName = name) }
    }

    private fun queryDisplayName(uri: Uri): String? = app.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private companion object {
        const val KEY_SELECTED_URI = "selected_uri"
        const val KEY_SELECTED_NAME = "selected_name"
        val supportedExtensions = setOf("srt", "lrc", "txt", "vtt")
    }
}

package com.subtitleedit

import android.app.Application
import android.content.Intent
import com.subtitleedit.util.PickedUriAccess
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import com.subtitleedit.util.SubtitleFormatConverter
import com.subtitleedit.util.UriDisplayName

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
        savedState[KEY_SELECTED_URI] = uri.toString()
        savedState[KEY_SELECTED_NAME] = name
        setState { copy(selectedUri = uri, selectedName = name) }
    }

    private fun queryDisplayName(uri: Uri): String =
        UriDisplayName.of(app, uri, string(R.string.unknown_file))

    private companion object {
        const val KEY_SELECTED_URI = "selected_uri"
        const val KEY_SELECTED_NAME = "selected_name"
    }
}

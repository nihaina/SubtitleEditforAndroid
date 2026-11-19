package com.subtitleedit

import android.app.Application
import com.subtitleedit.util.SettingsManager

internal data class FileManagementSettingsUiState(
    val showAllFileTypes: Boolean = false,
    val showHiddenFiles: Boolean = false,
    val showFullFileNames: Boolean = false
)

internal class FileManagementSettingsViewModel(
    application: Application
) : AppViewModel<FileManagementSettingsUiState, Nothing>(
    application,
    SettingsManager.getInstance(application).let {
        FileManagementSettingsUiState(
            showAllFileTypes = it.isShowAllFileTypesEnabled(),
            showHiddenFiles = it.isShowHiddenFilesEnabled(),
            showFullFileNames = it.isShowFullFileNamesEnabled()
        )
    }
) {
    private val settings = SettingsManager.getInstance(application)

    fun setShowAllFileTypes(enabled: Boolean) {
        setState { copy(showAllFileTypes = enabled) }
        settings.setShowAllFileTypesEnabled(enabled)
    }

    fun setShowHiddenFiles(enabled: Boolean) {
        setState { copy(showHiddenFiles = enabled) }
        settings.setShowHiddenFilesEnabled(enabled)
    }

    fun setShowFullFileNames(enabled: Boolean) {
        setState { copy(showFullFileNames = enabled) }
        settings.setShowFullFileNamesEnabled(enabled)
    }
}

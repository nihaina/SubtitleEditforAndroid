package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class FileManagementSettingsActivity : AppComposeActivity() {
    private val viewModel: FileManagementSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                FileManagementSettingsScreen(
                    showAllFileTypes = state.showAllFileTypes,
                    showHiddenFiles = state.showHiddenFiles,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onShowAllFileTypesChanged = viewModel::setShowAllFileTypes,
                    onShowHiddenFilesChanged = viewModel::setShowHiddenFiles
                )
            }
        }
    }
}

@Composable
private fun FileManagementSettingsScreen(
    showAllFileTypes: Boolean,
    showHiddenFiles: Boolean,
    onBack: () -> Unit,
    onShowAllFileTypesChanged: (Boolean) -> Unit,
    onShowHiddenFilesChanged: (Boolean) -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.file_management_settings),
        onBack = onBack
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.activity_settings_text_13),
            description = stringResource(R.string.activity_settings_text_14),
            checked = showAllFileTypes,
            onCheckedChange = onShowAllFileTypesChanged
        )
        SettingsSwitchRow(
            title = stringResource(R.string.activity_settings_text_15),
            description = stringResource(R.string.activity_settings_text_16),
            checked = showHiddenFiles,
            onCheckedChange = onShowHiddenFilesChanged
        )
    }
}

package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

class FileManagementSettingsActivity : AppComposeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsManager.getInstance(this)

        setContent {
            SubtitleEditComposeTheme {
                var showAllFileTypes by rememberSaveable {
                    mutableStateOf(settings.isShowAllFileTypesEnabled())
                }
                var showHiddenFiles by rememberSaveable {
                    mutableStateOf(settings.isShowHiddenFilesEnabled())
                }

                FileManagementSettingsScreen(
                    showAllFileTypes = showAllFileTypes,
                    showHiddenFiles = showHiddenFiles,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onShowAllFileTypesChanged = { enabled ->
                        showAllFileTypes = enabled
                        settings.setShowAllFileTypesEnabled(enabled)
                    },
                    onShowHiddenFilesChanged = { enabled ->
                        showHiddenFiles = enabled
                        settings.setShowHiddenFilesEnabled(enabled)
                    }
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

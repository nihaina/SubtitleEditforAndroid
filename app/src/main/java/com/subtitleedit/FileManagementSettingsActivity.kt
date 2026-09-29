package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

class FileManagementSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsManager.getInstance(this)

        setContent {
            SubtitleEditComposeTheme {
                var showAllFileTypes by remember {
                    mutableStateOf(settings.isShowAllFileTypesEnabled())
                }
                var showHiddenFiles by remember {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileManagementSettingsScreen(
    showAllFileTypes: Boolean,
    showHiddenFiles: Boolean,
    onBack: () -> Unit,
    onShowAllFileTypesChanged: (Boolean) -> Unit,
    onShowHiddenFilesChanged: (Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.file_management_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.tools_navigate_back)
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.activity_settings_text_13)) },
                supportingContent = { Text(stringResource(R.string.activity_settings_text_14)) },
                trailingContent = {
                    Switch(
                        checked = showAllFileTypes,
                        onCheckedChange = onShowAllFileTypesChanged
                    )
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.activity_settings_text_15)) },
                supportingContent = { Text(stringResource(R.string.activity_settings_text_16)) },
                trailingContent = {
                    Switch(
                        checked = showHiddenFiles,
                        onCheckedChange = onShowHiddenFilesChanged
                    )
                }
            )
        }
    }
}

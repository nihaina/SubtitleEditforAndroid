package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                modifier = Modifier.height(56.dp),
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
                .padding(16.dp)
        ) {
            FileManagementSwitchRow(
                title = stringResource(R.string.activity_settings_text_13),
                description = stringResource(R.string.activity_settings_text_14),
                checked = showAllFileTypes,
                onCheckedChange = onShowAllFileTypesChanged
            )
            FileManagementSwitchRow(
                title = stringResource(R.string.activity_settings_text_15),
                description = stringResource(R.string.activity_settings_text_16),
                checked = showHiddenFiles,
                onCheckedChange = onShowHiddenFilesChanged
            )
        }
    }
}

@Composable
private fun FileManagementSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

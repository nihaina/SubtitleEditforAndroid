package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSection
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.theme.AppSpacing

@Composable
fun SubtitleFormatSelectScreen(
    selectedFileName: String?,
    selectedFileSizeLabel: String,
    onSelectFile: () -> Unit,
    onConfirm: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val pageBackground = settingsPageBackground()
    AppToolScaffold(
        title = stringResource(R.string.formatting_tool_title),
        onBack = onNavigateBack,
        contentBackground = pageBackground,
        topBarContainerColor = pageBackground,
        topBarScrolledContainerColor = pageBackground,
        bottomBar = {
            Box(Modifier.background(pageBackground)) {
                Button(
                    onClick = onConfirm,
                    enabled = selectedFileName != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
                        .height(56.dp),
                    shape = CircleShape
                ) {
                    Text(
                        stringResource(R.string.activity_subtitle_format_select_text_05),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) {
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.activity_subtitle_format_select_text_03),
                iconRes = R.drawable.ic_file_text,
                value = stringResource(
                    if (selectedFileName != null) R.string.subtitle_format_file_selected
                    else R.string.activity_subtitle_format_select_text_04
                ),
                onClick = onSelectFile
            )
        }
        SettingsTileSection(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)) {
            if (selectedFileName == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.activity_subtitle_format_select_text_04),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                ToolPendingFileRow(
                    fileName = selectedFileName,
                    fileSizeLabel = selectedFileSizeLabel
                )
            }
        }
    }
}

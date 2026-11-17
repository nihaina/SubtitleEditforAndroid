package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataSelection
import com.subtitleedit.MediaTagEditorState
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.SettingsTileCard
import com.subtitleedit.ui.settings.SettingsTileSection
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.settings.settingsPrimaryTextColor
import com.subtitleedit.ui.theme.AppSpacing

@Composable
internal fun MediaTagEditorScreen(
    state: MediaTagEditorState,
    onBack: () -> Unit,
    onMetadataChanged: (MediaConvertMetadata) -> Unit,
    onPickCover: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onExportCover: () -> Unit = {}
) {
    val busy = state.isSaving || state.isExportingCover
    val pageBackground = settingsPageBackground()
    AppToolScaffold(
        title = stringResource(R.string.media_tag_editor_title),
        onBack = onBack,
        imePadding = true,
        contentBackground = pageBackground,
        topBarContainerColor = pageBackground,
        topBarScrolledContainerColor = pageBackground,
        bottomBar = {
            Box(Modifier.background(pageBackground)) {
                Button(
                    onClick = if (busy) onCancel else onSave,
                    enabled = busy || state.canSave,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
                        .height(56.dp)
                ) {
                    Text(
                        stringResource(if (busy) R.string.cancel else R.string.save),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) {
        SettingsTileSection(title = stringResource(R.string.activity_media_convert_text_01)) {
            Text(
                state.fileNames.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = settingsPrimaryTextColor()
            )
        }
        MediaConvertInfoPage(
            enabled = state.selection.editable && !busy && !state.isReadingCover,
            metadata = state.metadata,
            onMetadataChanged = onMetadataChanged,
            isAudioOutput = state.selection.audio,
            batch = state.selection.batch,
            batchIsAudioSource = state.selection.audio,
            coverFileName = state.coverFileName,
            onPickCover = onPickCover,
            onExportCover = onExportCover,
            coverExportEnabled = state.canExportCover,
            coverEnabled = state.coverSupported,
            coverUnsupportedHint = if (state.coverSupported || !state.selection.editable) null
                else stringResource(R.string.media_tag_editor_cover_unsupported),
            disabledHint = when {
                state.isSaving -> stringResource(R.string.media_tag_editor_saving)
                state.isExportingCover -> stringResource(R.string.media_tag_editor_exporting_cover)
                state.isReadingCover -> stringResource(R.string.media_convert_reading_info)
                state.selection == MediaConvertMetadataSelection.READING -> stringResource(R.string.media_convert_reading_info)
                state.selection == MediaConvertMetadataSelection.MIXED -> stringResource(R.string.media_convert_info_mixed_hint)
                else -> stringResource(R.string.media_tag_editor_unavailable)
            }
        )
        if (busy) {
            SettingsTileCard {
                AppTaskProgress(
                    visible = true,
                    progress = if (state.isExportingCover) null else state.progress,
                    status = state.message
                )
            }
        } else if (state.message.isNotBlank()) {
            SettingsTileCard {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

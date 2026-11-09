package com.subtitleedit.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.theme.AppSpacing

@Composable
internal fun MediaConvertInfoPage(
    enabled: Boolean,
    metadata: MediaConvertMetadata,
    onMetadataChanged: (MediaConvertMetadata) -> Unit,
    isAudioOutput: Boolean = false,
    batch: Boolean = false,
    coverFileName: String? = null,
    onPickCover: () -> Unit = {},
    disabledHint: String? = null,
    batchIsAudioSource: Boolean = isAudioOutput,
    coverEnabled: Boolean = enabled,
    coverUnsupportedHint: String? = null
) {
    var editingFieldName by rememberSaveable { mutableStateOf<String?>(null) }
    var showCoverWarning by rememberSaveable { mutableStateOf(false) }
    var showCoverDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    val fields = if (isAudioOutput) listOf(
        MediaConvertMetadataField.GENRE, MediaConvertMetadataField.TITLE, MediaConvertMetadataField.ARTIST,
        MediaConvertMetadataField.ALBUM, MediaConvertMetadataField.YEAR
    ) else listOf(MediaConvertMetadataField.TITLE, MediaConvertMetadataField.AUTHOR, MediaConvertMetadataField.COMMENT)
    val warning = if (batch) stringResource(
        if (batchIsAudioSource) R.string.media_convert_info_batch_audio_warning else R.string.media_convert_info_batch_video_warning
    ) else null
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner), modifier = Modifier.testTag("convert-info-page")) {
        Text(stringResource(R.string.media_convert_info_settings), style = MaterialTheme.typography.titleLarge)
        if (warning != null && enabled) Text(
            warning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("convert-info-batch-warning")
        )
        val coverLabel = when (metadata.cover) {
            MediaConvertCoverMode.DEFAULT -> stringResource(R.string.media_convert_info_cover_default)
            MediaConvertCoverMode.NONE -> stringResource(R.string.media_convert_info_cover_none)
            MediaConvertCoverMode.CUSTOM -> coverFileName ?: stringResource(R.string.media_convert_info_cover_selected)
        }
        MediaConvertInfoRow(stringResource(R.string.media_convert_info_cover), coverLabel, enabled && coverEnabled,
            { if (batch) showCoverWarning = true else onPickCover() }, "convert-info-cover",
            onLongClick = { showCoverDeleteConfirmation = true },
            onLongClickLabel = stringResource(R.string.media_tag_editor_delete_cover),
            supportingText = stringResource(R.string.media_tag_editor_delete_cover_hint))
        if (coverUnsupportedHint != null) Text(
            text = coverUnsupportedHint,
            modifier = Modifier.testTag("convert-info-cover-unsupported-hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        fields.forEach { field ->
            HorizontalDivider()
            MediaConvertInfoRow(
                label = stringResource(field.labelResource),
                value = metadata.value(field).ifBlank { stringResource(R.string.media_convert_info_empty) },
                enabled = enabled,
                onClick = { editingFieldName = field.name },
                tag = "convert-info-${field.tag}"
            )
        }
        if (!enabled) Text(
            text = disabledHint ?: stringResource(R.string.media_convert_info_unavailable_hint),
            modifier = Modifier.testTag("convert-info-unavailable-hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (showCoverDeleteConfirmation && enabled && coverEnabled) {
        AppDialog(
            onDismissRequest = { showCoverDeleteConfirmation = false },
            title = { Text(stringResource(R.string.media_tag_editor_delete_cover)) },
            text = { Text(stringResource(if (batch) R.string.media_tag_editor_delete_cover_batch_message
                else R.string.media_tag_editor_delete_cover_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMetadataChanged(metadata.copy(cover = MediaConvertCoverMode.NONE, coverPath = null))
                        showCoverDeleteConfirmation = false
                    },
                    modifier = Modifier.testTag("convert-cover-delete-confirm")
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showCoverDeleteConfirmation = false },
                    modifier = Modifier.testTag("convert-cover-delete-cancel")) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    if (showCoverWarning && enabled && coverEnabled) {
        AppDialog(
            onDismissRequest = { showCoverWarning = false },
            title = { Text(stringResource(R.string.media_convert_info_cover)) },
            text = { Text(warning.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { showCoverWarning = false; onPickCover() },
                    modifier = Modifier.testTag("convert-cover-batch-confirm")) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCoverWarning = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    val editingField = fields.firstOrNull { it.name == editingFieldName }
    if (editingField != null && enabled) {
        MediaConvertMetadataDialog(
            field = editingField,
            initialValue = metadata.value(editingField),
            onDismiss = { editingFieldName = null },
            onConfirm = { onMetadataChanged(metadata.withValue(editingField, it)); editingFieldName = null },
            batch = batch,
            batchIsAudioSource = batchIsAudioSource
        )
    }
}

@Composable
private fun MediaConvertInfoRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit, tag: String,
                                onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null,
                                supportingText: String? = null) {
    val interaction = if (onLongClick != null) Modifier.combinedClickable(
        enabled = enabled, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel
    ) else Modifier.clickable(enabled = enabled, onClick = onClick)
    Row(
        modifier = Modifier.fillMaxWidth().then(interaction).testTag(tag).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
        Text(label, modifier = Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyLarge, color = contentColor)
        Column(Modifier.weight(1f)) {
            Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = contentColor)
            if (supportingText != null) Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
            )
        }
        Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

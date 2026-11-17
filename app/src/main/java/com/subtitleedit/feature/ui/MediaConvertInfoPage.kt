package com.subtitleedit.feature.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.settingsChipColor
import com.subtitleedit.ui.settings.settingsPrimaryTextColor
import com.subtitleedit.ui.settings.settingsSecondaryTextColor
import com.subtitleedit.ui.settings.settingsTileColor
import com.subtitleedit.ui.settings.settingsTileShape
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
    coverUnsupportedHint: String? = null,
    onExportCover: () -> Unit = {},
    coverExportEnabled: Boolean = enabled
) {
    var editingFieldName by rememberSaveable { mutableStateOf<String?>(null) }
    var showCoverWarning by rememberSaveable { mutableStateOf(false) }
    var showCoverDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    val fields = if (isAudioOutput) MediaConvertMetadata.audioFields else MediaConvertMetadata.videoFields
    val warning = if (batch) stringResource(
        if (batchIsAudioSource) R.string.media_convert_info_batch_audio_warning else R.string.media_convert_info_batch_video_warning
    ) else null
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner), modifier = Modifier.testTag("convert-info-page")) {
        Text(
            stringResource(R.string.media_convert_info_settings),
            style = MaterialTheme.typography.titleMedium,
            color = settingsPrimaryTextColor(),
            modifier = Modifier.padding(start = 4.dp)
        )
        if (warning != null && enabled) Text(
            warning,
            style = MaterialTheme.typography.bodySmall,
            color = settingsSecondaryTextColor(),
            modifier = Modifier.padding(horizontal = 4.dp).testTag("convert-info-batch-warning")
        )
        val coverLabel = when (metadata.cover) {
            MediaConvertCoverMode.DEFAULT -> stringResource(R.string.media_convert_info_cover_default)
            MediaConvertCoverMode.NONE -> stringResource(R.string.media_convert_info_cover_none)
            MediaConvertCoverMode.CUSTOM -> coverFileName ?: stringResource(R.string.media_convert_info_cover_selected)
        }
        SettingsTileGroup {
            MediaConvertInfoRow(stringResource(R.string.media_convert_info_cover), coverLabel, enabled && coverEnabled,
                { if (batch) showCoverWarning = true else onPickCover() }, "convert-info-cover",
                onLongClick = { showCoverDeleteConfirmation = true },
                onLongClickLabel = stringResource(R.string.media_tag_editor_delete_cover),
                actions = {
                    MediaConvertInfoAction(stringResource(R.string.media_tag_editor_export_cover),
                        MaterialTheme.colorScheme.primary, enabled && coverExportEnabled, "convert-cover-export",
                        onExportCover)
                    MediaConvertInfoAction(stringResource(R.string.delete),
                        MaterialTheme.colorScheme.error, enabled && coverEnabled, "convert-cover-delete") {
                        showCoverDeleteConfirmation = true
                    }
                })
            fields.forEach { field ->
                MediaConvertInfoRow(
                    label = stringResource(field.labelResource),
                    value = metadata.value(field).ifBlank { stringResource(R.string.media_convert_info_empty) },
                    enabled = enabled,
                    onClick = { editingFieldName = field.name },
                    tag = "convert-info-${field.tag}"
                )
            }
        }
        if (coverUnsupportedHint != null) Text(
            text = coverUnsupportedHint,
            modifier = Modifier.padding(horizontal = 4.dp).testTag("convert-info-cover-unsupported-hint"),
            style = MaterialTheme.typography.bodySmall,
            color = settingsSecondaryTextColor()
        )
        if (!enabled) Text(
            text = disabledHint ?: stringResource(R.string.media_convert_info_unavailable_hint),
            modifier = Modifier.padding(horizontal = 4.dp).testTag("convert-info-unavailable-hint"),
            style = MaterialTheme.typography.bodySmall,
            color = settingsSecondaryTextColor()
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

/** One metadata tile in the settings style: title with its value underneath, optional trailing actions and an arrow. */
@Composable
private fun MediaConvertInfoRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit, tag: String,
                                onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null,
                                actions: @Composable () -> Unit = {}) {
    val source = remember { MutableInteractionSource() }
    val interaction = if (onLongClick != null) Modifier.combinedClickable(
        enabled = enabled, interactionSource = source, indication = LocalIndication.current,
        onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel
    ) else Modifier.clickable(enabled = enabled, interactionSource = source, indication = LocalIndication.current,
        onClick = onClick)
    val contentAlpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(settingsTileShape(source))
            .background(settingsTileColor())
            .then(interaction)
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).alpha(contentAlpha)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = settingsPrimaryTextColor())
            Text(value, modifier = Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = settingsSecondaryTextColor())
        }
        actions()
        Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null,
            modifier = Modifier.padding(start = 4.dp).size(24.dp).alpha(contentAlpha),
            tint = settingsSecondaryTextColor())
    }
}

/** Capsule text action placed on a tile (export / delete cover). */
@Composable
private fun MediaConvertInfoAction(text: String, color: Color, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .padding(start = 8.dp)
            .testTag(tag)
            .clip(CircleShape)
            .background(settingsChipColor())
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = color.copy(alpha = if (enabled) 1f else 0.38f)
    )
}

package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.settings.settingsOutlinedTextFieldColors
import com.subtitleedit.ui.theme.AppSpacing

@Composable
internal fun MediaConvertMetadataDialog(
    field: MediaConvertMetadataField,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    batch: Boolean = false,
    batchIsAudioSource: Boolean = false
) {
    var value by rememberSaveable(field, initialValue) { mutableStateOf(initialValue) }
    val multiline = field == MediaConvertMetadataField.COMMENT
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(field.labelResource)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                if (batch) Text(
                    text = stringResource(if (batchIsAudioSource) R.string.media_convert_info_batch_audio_warning else R.string.media_convert_info_batch_video_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("convert-metadata-batch-warning")
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(field.labelResource)) },
                    singleLine = !multiline,
                    minLines = if (multiline) 2 else 1,
                    maxLines = if (multiline) 4 else 1,
                    colors = settingsOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("convert-metadata-${field.tag}")
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, modifier = Modifier.testTag("convert-metadata-confirm")) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("convert-metadata-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

internal val MediaConvertMetadataField.tag: String get() = name.lowercase(java.util.Locale.ROOT)

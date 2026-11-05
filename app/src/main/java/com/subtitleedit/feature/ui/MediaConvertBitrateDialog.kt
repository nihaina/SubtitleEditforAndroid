package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.MediaConvertBitrateMode
import com.subtitleedit.MediaConvertVideoBitrate
import com.subtitleedit.MediaConvertVideoBitrateSettings
import com.subtitleedit.R

@Composable
internal fun MediaConvertBitrateDialog(
    initial: MediaConvertVideoBitrateSettings,
    sourceBitsPerSecond: Long?,
    canUseOriginal: Boolean,
    multipleSources: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (MediaConvertVideoBitrateSettings) -> Unit
) {
    var mode by rememberSaveable { mutableStateOf(initial.mode.name) }
    var custom by rememberSaveable { mutableStateOf(initial.customKbps) }
    var preset by rememberSaveable { mutableIntStateOf(initial.presetKbps) }
    val settings = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.valueOf(mode), custom, preset)
    val originalEnabled = canUseOriginal && sourceBitsPerSecond != null && sourceBitsPerSecond > 0
    val canConfirm = settings.isValid && (settings.mode != MediaConvertBitrateMode.ORIGINAL || originalEnabled)
    val customError = settings.mode == MediaConvertBitrateMode.CUSTOM && !settings.isValid
    val customLabel = stringResource(R.string.media_convert_bitrate_custom)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.95f).fillMaxHeight(0.9f).imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Text(stringResource(R.string.media_convert_bitrate_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onConfirm(settings) }, enabled = canConfirm, modifier = Modifier.testTag("bitrate-confirm")) {
                        Text(stringResource(R.string.media_convert_bitrate_done))
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    item {
                        Text(stringResource(R.string.media_convert_bitrate_original), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
                        BitrateChoice(
                            text = sourceBitsPerSecond?.takeIf { it > 0 }?.let(MediaConvertVideoBitrate::label) ?: stringResource(R.string.media_convert_unknown),
                            detail = stringResource(R.string.media_convert_resolution_source),
                            selected = settings.mode == MediaConvertBitrateMode.ORIGINAL,
                            enabled = originalEnabled,
                            modifier = Modifier.testTag("bitrate-original"),
                            onClick = { mode = MediaConvertBitrateMode.ORIGINAL.name }
                        )
                        if (!originalEnabled) Text(
                            stringResource(R.string.media_convert_bitrate_source_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        if (multipleSources) Text(
                            stringResource(R.string.media_convert_bitrate_batch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        BitrateChoice(
                            text = stringResource(R.string.media_convert_bitrate_auto),
                            detail = "",
                            selected = settings.mode == MediaConvertBitrateMode.AUTO,
                            onClick = { mode = MediaConvertBitrateMode.AUTO.name }
                        )
                        HorizontalDivider()
                        Text(customLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioButton(
                                selected = settings.mode == MediaConvertBitrateMode.CUSTOM,
                                onClick = { mode = MediaConvertBitrateMode.CUSTOM.name },
                                modifier = Modifier.semantics { contentDescription = customLabel }
                            )
                            OutlinedTextField(
                                value = custom,
                                onValueChange = { value ->
                                    if (value.length <= 6 && value.all(Char::isDigit)) {
                                        custom = value
                                        mode = MediaConvertBitrateMode.CUSTOM.name
                                    }
                                },
                                placeholder = { Text("128–999999") },
                                suffix = { Text("kbps") },
                                label = { Text(customLabel) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                isError = customError,
                                modifier = Modifier.weight(1f).testTag("bitrate-custom-input")
                            )
                        }
                        Text(
                            stringResource(R.string.media_convert_bitrate_limits),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (customError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        HorizontalDivider()
                        Text(stringResource(R.string.media_convert_bitrate_presets), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                    }
                    items(MediaConvertVideoBitrate.presets, key = { it }) { kbps ->
                        val detail = when (kbps) {
                            250 -> stringResource(R.string.media_convert_bitrate_sd)
                            2000 -> stringResource(R.string.media_convert_bitrate_hd)
                            5000 -> stringResource(R.string.media_convert_bitrate_fhd)
                            8000 -> stringResource(R.string.media_convert_bitrate_bluray)
                            else -> ""
                        }
                        BitrateChoice(
                            text = "$kbps kbps", detail = detail,
                            selected = settings.mode == MediaConvertBitrateMode.PRESET && preset == kbps,
                            modifier = Modifier.testTag("bitrate-preset-$kbps"),
                            onClick = { preset = kbps; mode = MediaConvertBitrateMode.PRESET.name }
                        )
                        HorizontalDivider()
                    }
                }
                Text(
                    stringResource(R.string.media_convert_bitrate_average_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun BitrateChoice(
    text: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.MediaConvertAudioBitrate
import com.subtitleedit.MediaConvertBitrateMode
import com.subtitleedit.R

@Composable
internal fun MediaConvertAudioBitrateDialog(
    initial: String,
    codec: String,
    muxer: String,
    sourceBitsPerSecond: Long?,
    canUseOriginal: Boolean,
    multipleSources: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val presets = MediaConvertAudioBitrate.presets(codec, muxer)
    val range = MediaConvertAudioBitrate.range(codec, muxer)
    var mode by rememberSaveable {
        mutableStateOf(when (initial) {
            "" -> MediaConvertBitrateMode.AUTO
            MediaConvertAudioBitrate.ORIGINAL -> MediaConvertBitrateMode.ORIGINAL
            in presets -> MediaConvertBitrateMode.PRESET
            else -> MediaConvertBitrateMode.CUSTOM
        })
    }
    var custom by rememberSaveable { mutableStateOf(initial.takeUnless { it == MediaConvertAudioBitrate.ORIGINAL }.orEmpty()) }
    var preset by rememberSaveable { mutableStateOf(initial.takeIf { it in presets } ?: presets.firstOrNull().orEmpty()) }
    val originalEnabled = canUseOriginal && (sourceBitsPerSecond ?: 0) > 0
    val validCustom = MediaConvertAudioBitrate.isValidCustom(custom, codec, muxer)
    val canConfirm = when (mode) {
        MediaConvertBitrateMode.AUTO -> true
        MediaConvertBitrateMode.ORIGINAL -> originalEnabled
        MediaConvertBitrateMode.CUSTOM -> validCustom
        MediaConvertBitrateMode.PRESET -> preset in presets
    }
    val customError = mode == MediaConvertBitrateMode.CUSTOM && !validCustom
    val customLabel = stringResource(R.string.media_convert_bitrate_custom)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.95f).fillMaxHeight(0.9f).imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Text(stringResource(R.string.media_convert_audio_bitrate_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        onConfirm(when (mode) {
                            MediaConvertBitrateMode.AUTO -> ""
                            MediaConvertBitrateMode.ORIGINAL -> MediaConvertAudioBitrate.ORIGINAL
                            MediaConvertBitrateMode.CUSTOM -> custom
                            MediaConvertBitrateMode.PRESET -> preset
                        })
                    }, enabled = canConfirm, modifier = Modifier.testTag("audio-bitrate-confirm")) {
                        Text(stringResource(R.string.media_convert_bitrate_done))
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    item {
                        Text(stringResource(R.string.media_convert_bitrate_original), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
                        BitrateChoice(
                            text = sourceBitsPerSecond?.takeIf { it > 0 }?.let(MediaConvertAudioBitrate::label) ?: stringResource(R.string.media_convert_unknown),
                            detail = stringResource(R.string.media_convert_resolution_source),
                            selected = mode == MediaConvertBitrateMode.ORIGINAL,
                            enabled = originalEnabled,
                            modifier = Modifier.testTag("audio-bitrate-original"),
                            onClick = { mode = MediaConvertBitrateMode.ORIGINAL }
                        )
                        if (!originalEnabled) Text(
                            stringResource(R.string.media_convert_audio_bitrate_source_unavailable),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        if (multipleSources) Text(
                            stringResource(R.string.media_convert_audio_bitrate_batch),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        BitrateChoice(
                            text = stringResource(R.string.media_convert_bitrate_auto), detail = "",
                            selected = mode == MediaConvertBitrateMode.AUTO,
                            modifier = Modifier.testTag("audio-bitrate-auto"),
                            onClick = { mode = MediaConvertBitrateMode.AUTO }
                        )
                        HorizontalDivider()
                        Text(customLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioButton(
                                selected = mode == MediaConvertBitrateMode.CUSTOM,
                                onClick = { mode = MediaConvertBitrateMode.CUSTOM },
                                modifier = Modifier.semantics { contentDescription = customLabel }
                            )
                            OutlinedTextField(
                                value = custom,
                                onValueChange = { value ->
                                    if (value.length <= 8 && value.all { it in '0'..'9' || it == '.' }) {
                                        custom = value
                                        mode = MediaConvertBitrateMode.CUSTOM
                                    }
                                },
                                placeholder = { Text("${MediaConvertAudioBitrate.kbps(range.first)}–${MediaConvertAudioBitrate.kbps(range.last)}") },
                                suffix = { Text("kbps") }, label = { Text(customLabel) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true, isError = customError,
                                modifier = Modifier.weight(1f).testTag("audio-bitrate-custom-input")
                            )
                        }
                        Text(
                            stringResource(R.string.media_convert_audio_bitrate_limits, MediaConvertAudioBitrate.kbps(range.first), MediaConvertAudioBitrate.kbps(range.last)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (customError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        HorizontalDivider()
                        Text(stringResource(R.string.media_convert_bitrate_presets), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                    }
                    items(presets, key = { it }) { kbps ->
                        BitrateChoice(
                            text = "$kbps kbps", detail = "",
                            selected = mode == MediaConvertBitrateMode.PRESET && preset == kbps,
                            modifier = Modifier.testTag("audio-bitrate-preset-$kbps"),
                            onClick = { preset = kbps; mode = MediaConvertBitrateMode.PRESET }
                        )
                        HorizontalDivider()
                    }
                }
                Text(
                    stringResource(R.string.media_convert_audio_bitrate_target_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppOption

@Composable
internal fun MediaConvertChannelDialog(
    initialIndex: Int,
    options: List<AppOption<Int>>,
    sourceLabel: String,
    multipleSources: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selected by rememberSaveable { mutableIntStateOf(initialIndex.takeIf { id -> options.any { it.id == id } } ?: 0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.95f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Text(stringResource(R.string.media_convert_channel_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onConfirm(selected) }, enabled = options.any { it.id == selected }, modifier = Modifier.testTag("channel-confirm")) {
                        Text(stringResource(R.string.media_convert_bitrate_done))
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    item {
                        Text(stringResource(R.string.media_convert_channel_original), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
                        BitrateChoice(
                            text = sourceLabel.ifBlank { stringResource(R.string.media_convert_channel_original) },
                            detail = stringResource(R.string.media_convert_resolution_source),
                            selected = selected == 0,
                            modifier = Modifier.testTag("channel-original"),
                            onClick = { selected = 0 }
                        )
                        if (sourceLabel.isBlank()) Text(
                            stringResource(R.string.media_convert_channel_source_unavailable),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        if (multipleSources) Text(
                            stringResource(R.string.media_convert_channel_batch),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        HorizontalDivider()
                        Text(stringResource(R.string.media_convert_channel_presets), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                    }
                    items(options.filter { it.id != 0 }, key = { it.id }) { option ->
                        BitrateChoice(
                            text = option.label, detail = "", selected = selected == option.id,
                            modifier = Modifier.testTag("channel-option-${option.id}"),
                            onClick = { selected = option.id }
                        )
                        HorizontalDivider()
                    }
                }
                Text(
                    stringResource(R.string.media_convert_channel_adapt_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

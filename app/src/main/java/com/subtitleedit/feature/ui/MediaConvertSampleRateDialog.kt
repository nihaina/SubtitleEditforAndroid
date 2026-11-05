package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppOption

@Composable
internal fun MediaConvertSampleRateDialog(
    initialIndex: Int,
    options: List<AppOption<Int>>,
    sourceSampleRate: Int?,
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
                    Text(stringResource(R.string.media_convert_sample_rate_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onConfirm(selected) }, enabled = options.any { it.id == selected }, modifier = Modifier.testTag("sample-rate-confirm")) {
                        Text(stringResource(R.string.media_convert_bitrate_done))
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    item {
                        Text(stringResource(R.string.media_convert_sample_rate_original), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
                        SampleRateChoice(
                            label = sourceSampleRate?.takeIf { it > 0 }?.let { "$it Hz" } ?: stringResource(R.string.media_convert_sample_rate_original),
                            detail = stringResource(R.string.media_convert_resolution_source),
                            selected = selected == 0,
                            modifier = Modifier.testTag("sample-rate-original"),
                            onClick = { selected = 0 }
                        )
                        if (sourceSampleRate == null || sourceSampleRate <= 0) Text(
                            stringResource(R.string.media_convert_sample_rate_source_unavailable),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        if (multipleSources) Text(
                            stringResource(R.string.media_convert_sample_rate_batch),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        HorizontalDivider()
                        Text(stringResource(R.string.media_convert_sample_rate_presets), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                    }
                    items(options.filter { it.id != 0 }, key = { it.id }) { option ->
                        SampleRateChoice(
                            label = option.label, detail = "", selected = selected == option.id,
                            modifier = Modifier.testTag("sample-rate-option-${option.id}"),
                            onClick = { selected = option.id }
                        )
                        HorizontalDivider()
                    }
                }
                Text(
                    stringResource(R.string.media_convert_sample_rate_adapt_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SampleRateChoice(label: String, detail: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

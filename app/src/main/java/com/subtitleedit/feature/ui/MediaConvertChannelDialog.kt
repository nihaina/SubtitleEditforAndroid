package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    MediaConvertDialogSurface(onDismiss) {
        MediaConvertDialogTitle(stringResource(R.string.media_convert_channel_title))
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
        MediaConvertDialogActions(onDismiss, { onConfirm(selected) }, options.any { it.id == selected }, Modifier.testTag("channel-confirm"))
    }
}

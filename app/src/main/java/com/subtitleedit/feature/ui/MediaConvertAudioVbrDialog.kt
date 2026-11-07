package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import com.subtitleedit.MediaConvertAudioVbr
import com.subtitleedit.R

@Composable
internal fun MediaConvertAudioVbrDialog(initialQuality: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(initialQuality.takeIf(MediaConvertAudioVbr::isValid) ?: MediaConvertAudioVbr.OFF) }
    MediaConvertDialogSurface(onDismiss) {
        MediaConvertDialogTitle(stringResource(R.string.media_convert_audio_vbr))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(listOf(MediaConvertAudioVbr.OFF) + MediaConvertAudioVbr.qualities, key = { it }) { quality ->
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("audio-vbr-option-$quality")
                        .selectable(selected == quality, role = Role.RadioButton, onClick = { selected = quality })
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(selected = selected == quality, onClick = null)
                    Text(
                        text = MediaConvertAudioVbr.bitrateBand(quality)?.let { "$it kbps" }
                            ?: stringResource(R.string.media_convert_audio_vbr_off),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge
                    )
                }
                HorizontalDivider()
            }
        }
        Text(stringResource(R.string.media_convert_audio_vbr_hint),
            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        MediaConvertDialogActions(onDismiss, { onConfirm(selected) }, confirmModifier = Modifier.testTag("audio-vbr-confirm"))
    }
}

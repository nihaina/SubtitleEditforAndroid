package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertAudioVolume
import com.subtitleedit.R

@Composable
internal fun MediaConvertVolumeDialog(initialPercent: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var percent by rememberSaveable { mutableIntStateOf(initialPercent.coerceIn(MediaConvertAudioVolume.MIN, MediaConvertAudioVolume.MAX)) }
    val volumeLabel = stringResource(R.string.media_convert_volume)
    MediaConvertDialogSurface(onDismiss, heightFraction = null) {
        MediaConvertDialogTitle(stringResource(R.string.media_convert_volume_title))
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.media_convert_volume_ratio), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("$percent%", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("volume-value"))
            }
            Slider(
                value = MediaConvertAudioVolume.sliderPosition(percent),
                onValueChange = { percent = MediaConvertAudioVolume.percentAt(it) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("volume-slider").semantics {
                    contentDescription = volumeLabel
                    stateDescription = "$percent%"
                }
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("0", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { percent = MediaConvertAudioVolume.DEFAULT }, modifier = Modifier.testTag("volume-reset")) { Text("100") }
                Text("1000", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.media_convert_volume_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MediaConvertDialogActions(onDismiss, { onConfirm(percent) }, confirmModifier = Modifier.testTag("volume-confirm"))
    }
}

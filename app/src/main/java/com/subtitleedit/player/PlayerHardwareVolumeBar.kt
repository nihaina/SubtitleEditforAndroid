package com.subtitleedit.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtitleedit.R
import kotlin.math.roundToInt

@Composable
internal fun PlayerHardwareVolumeBar(viewModel: PlayerViewModel) {
    val visible by viewModel.hardwareVolumeBar.collectAsStateWithLifecycle()
    if (!visible) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.usbHardwareVolume) return

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        OutlinedCard(Modifier.align(Alignment.TopEnd).widthIn(max = 280.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.player_usb_hardware_volume_level, state.volumePercent),
                    style = MaterialTheme.typography.labelLarge)
                Slider(value = state.volumePercent.toFloat(), onValueChange = { viewModel.setVolume(it.roundToInt()) },
                    valueRange = 0f..100f)
            }
        }
    }
}

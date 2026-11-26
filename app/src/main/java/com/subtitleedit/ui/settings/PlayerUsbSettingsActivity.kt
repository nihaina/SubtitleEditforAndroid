package com.subtitleedit.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtitleedit.AppComposeActivity
import com.subtitleedit.LogActivity
import com.subtitleedit.R
import com.subtitleedit.player.PlayerPreferences
import com.subtitleedit.player.PlayerUsbBitDepth
import com.subtitleedit.player.PlayerUsbDeviceMonitor
import com.subtitleedit.player.PlayerUsbDiagnostics
import com.subtitleedit.player.PlayerUsbSampleRate
import com.subtitleedit.player.PlayerUsbVolumeMode
import com.subtitleedit.player.usbLabel
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class PlayerUsbSettingsActivity : AppComposeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SubtitleEditComposeTheme {
                PlayerUsbSettingsPage(
                    onBack = ::navigateBack,
                    onOpenLogs = { startActivity(Intent(this, LogActivity::class.java)) },
                )
            }
        }
    }
}

@Composable
internal fun PlayerUsbSettingsPage(onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val monitor = remember(context) { PlayerUsbDeviceMonitor.get(context) }
    val preferences = remember(context) { PlayerPreferences.getInstance(context) }
    val devices by monitor.devices.collectAsStateWithLifecycle()
    val enabled by preferences.usbExclusiveEnabledChanges.collectAsStateWithLifecycle(preferences.usbExclusiveEnabled)
    val volumeMode by preferences.usbVolumeModeChanges.collectAsStateWithLifecycle(preferences.usbVolumeMode)
    val sampleRate by preferences.usbSampleRateChanges.collectAsStateWithLifecycle(preferences.usbSampleRate)
    val bitDepth by preferences.usbBitDepthChanges.collectAsStateWithLifecycle(preferences.usbBitDepth)
    val active by PlayerUsbDiagnostics.active.collectAsStateWithLifecycle()
    val hardwareVolumeSupported by PlayerUsbDiagnostics.hardwareVolumeSupported.collectAsStateWithLifecycle()
    val hardwareVolumeUnavailable = active && hardwareVolumeSupported == false
    val usingSoftwareFallback = volumeMode == PlayerUsbVolumeMode.HARDWARE && hardwareVolumeUnavailable
    var confirmFixedVolume by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(monitor) { monitor.refresh() }

    ModelSettingsScaffold(stringResource(R.string.player_usb_settings_title), onBack) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (devices.isEmpty()) {
                        Text(stringResource(R.string.player_usb_no_device), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(R.string.player_usb_detected_devices), style = MaterialTheme.typography.labelLarge)
                        devices.forEach { device ->
                            Text(device.usbLabel(), style = MaterialTheme.typography.bodyMedium,
                                overflow = TextOverflow.Ellipsis, maxLines = 2)
                        }
                        if (active) {
                            Text(stringResource(R.string.player_usb_output_active),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                TextButton(onClick = monitor::refresh) { Text(stringResource(R.string.file_picker_refresh)) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch,
                onValueChange = { preferences.usbExclusiveEnabled = it }).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.player_usb_enable), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = null)
        }
        Text(stringResource(R.string.player_usb_output_help), style = MaterialTheme.typography.bodyMedium)

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp).selectableGroup()) {
                Text(stringResource(R.string.player_usb_volume_title), Modifier.padding(4.dp),
                    style = MaterialTheme.typography.titleSmall)
                UsbOutputOption(
                    title = stringResource(R.string.player_usb_volume_software),
                    description = stringResource(R.string.player_usb_volume_software_help),
                    selected = volumeMode == PlayerUsbVolumeMode.SOFTWARE || usingSoftwareFallback,
                    enabled = enabled,
                ) { preferences.usbVolumeMode = PlayerUsbVolumeMode.SOFTWARE }
                UsbOutputOption(
                    title = stringResource(R.string.player_usb_volume_hardware),
                    description = stringResource(when {
                        usingSoftwareFallback -> R.string.player_usb_volume_hardware_fallback
                        hardwareVolumeUnavailable -> R.string.player_usb_volume_hardware_unsupported
                        else -> R.string.player_usb_volume_hardware_help
                    }),
                    selected = volumeMode == PlayerUsbVolumeMode.HARDWARE && !usingSoftwareFallback,
                    enabled = enabled && !hardwareVolumeUnavailable,
                ) { preferences.usbVolumeMode = PlayerUsbVolumeMode.HARDWARE }
                UsbOutputOption(
                    title = stringResource(R.string.player_usb_volume_fixed),
                    description = stringResource(R.string.player_usb_volume_fixed_help),
                    selected = volumeMode == PlayerUsbVolumeMode.FIXED,
                    enabled = enabled,
                ) { confirmFixedVolume = volumeMode != PlayerUsbVolumeMode.FIXED }
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            val options = PlayerUsbSampleRate.entries.map { option ->
                val label = if (option == PlayerUsbSampleRate.AUTO) {
                    stringResource(R.string.player_usb_sample_rate_auto)
                } else {
                    val khz = (option.hz / 1000.0).toString().removeSuffix(".0")
                    stringResource(R.string.player_usb_sample_rate_khz, khz)
                }
                AppOption(option, label)
            }
            AppOptionSelector(
                label = stringResource(R.string.player_usb_sample_rate_title),
                value = options.first { it.id == sampleRate },
                options = options,
                enabled = enabled,
                onSelected = { preferences.usbSampleRate = it },
                modifier = Modifier.padding(12.dp),
            )
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp).selectableGroup()) {
                Text(stringResource(R.string.player_usb_bit_depth_title), Modifier.padding(4.dp),
                    style = MaterialTheme.typography.titleSmall)
                PlayerUsbBitDepth.entries.forEach { option ->
                    val title = when (option) {
                        PlayerUsbBitDepth.AUTO -> R.string.player_usb_bit_depth_auto
                        PlayerUsbBitDepth.BIT_32 -> R.string.player_usb_bit_depth_32
                        PlayerUsbBitDepth.BIT_16 -> R.string.player_usb_bit_depth_16
                    }
                    UsbOutputOption(stringResource(title), selected = option == bitDepth, enabled = enabled) {
                        preferences.usbBitDepth = option
                    }
                }
                ModelSettingsHint(stringResource(R.string.player_usb_bit_depth_help), Modifier.padding(4.dp))
            }
        }

        TextButton(onClick = { showLogs = true }) { Text(stringResource(R.string.player_usb_open_logs)) }
    }

    if (showLogs) UsbDiagnosticsDialog(onBack = { showLogs = false }, onOpenLogs = onOpenLogs)

    if (enabled && confirmFixedVolume) {
        AppDialog(
            onDismissRequest = { confirmFixedVolume = false },
            title = { Text(stringResource(R.string.player_usb_volume_fixed)) },
            text = { Text(stringResource(R.string.player_usb_volume_fixed_warning)) },
            dismissButton = {
                TextButton(onClick = { confirmFixedVolume = false }) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    preferences.usbVolumeMode = PlayerUsbVolumeMode.FIXED
                    confirmFixedVolume = false
                }) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}

@Composable
private fun UsbDiagnosticsDialog(onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val lines by PlayerUsbDiagnostics.lines.collectAsStateWithLifecycle()
    val title = stringResource(R.string.player_usb_log_title)
    AppDialog(
        onDismissRequest = onBack,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (lines.isEmpty()) {
                    Text(stringResource(R.string.player_usb_log_empty))
                } else {
                    SelectionContainer(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        Text(lines.asReversed().joinToString("\n"), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onOpenLogs) { Text(stringResource(R.string.player_usb_open_app_logs)) }
            }
        },
        dismissButton = {
            TextButton(enabled = lines.isNotEmpty(), onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(title, lines.joinToString("\n")))
            }) { Text(stringResource(R.string.source_selection_copy)) }
        },
        confirmButton = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.player_usb_settings_close)) }
        },
    )
}

@Composable
private fun UsbOutputOption(
    title: String,
    description: String? = null,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled,
            role = Role.RadioButton, onClick = onSelect).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val colors = MaterialTheme.colorScheme
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f))
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}

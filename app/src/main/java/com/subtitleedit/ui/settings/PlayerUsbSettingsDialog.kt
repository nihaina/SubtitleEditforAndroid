package com.subtitleedit.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
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
import com.subtitleedit.R
import com.subtitleedit.player.PlayerPreferences
import com.subtitleedit.player.PlayerUsbDeviceMonitor
import com.subtitleedit.player.PlayerUsbDiagnostics
import com.subtitleedit.player.PlayerUsbVolumeMode
import com.subtitleedit.player.usbLabel
import com.subtitleedit.ui.components.AppDialog

@Composable
internal fun PlayerUsbSettingsDialog(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val context = LocalContext.current
    val monitor = remember(context) { PlayerUsbDeviceMonitor.get(context) }
    val preferences = remember(context) { PlayerPreferences.getInstance(context) }
    val devices by monitor.devices.collectAsStateWithLifecycle()
    val volumeMode by preferences.usbVolumeModeChanges.collectAsStateWithLifecycle(preferences.usbVolumeMode)
    val active by PlayerUsbDiagnostics.active.collectAsStateWithLifecycle()
    var confirmFixedVolume by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(monitor) { monitor.refresh() }

    if (showLogs) {
        UsbDiagnosticsDialog(onBack = { showLogs = false }, onOpenLogs = onOpenLogs)
        return
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_usb_settings_title)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_usb_settings_close)) }
        },
        dismissButton = {
            TextButton(onClick = { showLogs = true }) { Text(stringResource(R.string.player_usb_open_logs)) }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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
                                    Text(
                                        text = device.usbLabel(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 2,
                                    )
                                }
                                if (active) {
                                    Text(stringResource(R.string.player_usb_output_active),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        TextButton(onClick = { monitor.refresh() }) { Text(stringResource(R.string.file_picker_refresh)) }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch,
                        onValueChange = onEnabledChange).padding(vertical = 4.dp),
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
                        UsbVolumeOption(
                            title = stringResource(R.string.player_usb_volume_software),
                            description = stringResource(R.string.player_usb_volume_software_help),
                            selected = volumeMode == PlayerUsbVolumeMode.SOFTWARE,
                            enabled = enabled,
                        ) {
                            preferences.usbVolumeMode = PlayerUsbVolumeMode.SOFTWARE
                        }
                        UsbVolumeOption(
                            title = stringResource(R.string.player_usb_volume_fixed),
                            description = stringResource(R.string.player_usb_volume_fixed_help),
                            selected = volumeMode == PlayerUsbVolumeMode.FIXED,
                            enabled = enabled,
                        ) {
                            confirmFixedVolume = volumeMode != PlayerUsbVolumeMode.FIXED
                        }
                    }
                }
            }
        },
    )

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
private fun UsbVolumeOption(
    title: String,
    description: String,
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
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f))
        }
    }
}

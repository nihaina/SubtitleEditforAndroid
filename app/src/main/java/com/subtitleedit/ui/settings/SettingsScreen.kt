package com.subtitleedit.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.ui.components.SectionHeader
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.SettingsGroup
import com.subtitleedit.ui.components.SettingsRow as SharedSettingsRow
import com.subtitleedit.ui.components.SettingsSwitchRow as SharedSettingsSwitchRow
import java.util.Locale

data class SettingsPageState(
    val encoding: String = "",
    val themeMode: String = SettingsManager.THEME_SYSTEM,
    // Hosts fill this from the localized theme resources.
    val themeLabel: String = "",
    /** User-selected application data directory, or an empty value for the default. */
    val softwareDirectory: String = "",
    val pendingSoftwareDirectory: String? = null,
    val pendingSoftwareDirectoryModelCount: Int = 0,
    val isMigratingSoftwareDirectory: Boolean = false,
    val cacheSize: String = "",
    val checkUpdatesOnStartup: Boolean = false,
    val preserveOutputDirectories: Boolean = true,
    val loopSelectedSubtitle: Boolean = false,
    val selectPlayingSubtitle: Boolean = false,
    val cacheItems: List<SettingsCacheItem> = emptyList()
)

data class SettingsCacheItem(
    val key: String,
    val label: String,
    val sizeBytes: Long,
    val emptyMessage: String,
    val confirmationMessage: String,
    val fileCount: Int = 0
) {
    fun displaySize(): String = when {
        sizeBytes < 1024L -> "$sizeBytes B"
        sizeBytes < 1024L * 1024 -> "${"%.1f".format(Locale.getDefault(), sizeBytes / 1024.0)} KB"
        else -> "${"%.2f".format(Locale.getDefault(), sizeBytes / 1024.0 / 1024.0)} MB"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsPageState,
    encodings: List<FileUtils.EncodingInfo>,
    showTopBar: Boolean = true,
    confirmCacheClear: Boolean = true,
    useMaterial3Visuals: Boolean = false,
    onBack: () -> Unit,
    onEncodingSelected: (FileUtils.EncodingInfo) -> Unit,
    onThemeSelected: (String) -> Unit,
    onCheckUpdatesChanged: (Boolean) -> Unit,
    onPreserveDirectoriesChanged: (Boolean) -> Unit,
    onLoopSelectedChanged: (Boolean) -> Unit,
    onSelectPlayingChanged: (Boolean) -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenModelManagement: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    onSelectSoftwareDirectory: () -> Unit,
    onConfirmSoftwareDirectory: () -> Unit,
    onCancelSoftwareDirectory: () -> Unit,
    onCacheClear: (SettingsCacheItem) -> Unit,
    onEmptyCacheClear: (SettingsCacheItem) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    var showEncodingDialog by rememberSaveable { mutableStateOf(false) }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showCacheDialog by rememberSaveable { mutableStateOf(false) }
    var showBackupWarning by rememberSaveable { mutableStateOf(false) }
    var pendingCacheKey by rememberSaveable { mutableStateOf<String?>(null) }

    AppToolScaffold(
        title = stringResource(R.string.menu_main_title_02),
        onBack = onBack,
        showTopBar = showTopBar,
        imePadding = true,
        contentBackground = if (useMaterial3Visuals) settingsPageBackground() else Color.Transparent
    ) {
            SettingsPageGroup(useMaterial3Visuals) {
                SettingsPageRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_text_01),
                    iconRes = if (useMaterial3Visuals) R.drawable.ic_encoding else null,
                    value = encodings.firstOrNull { it.id == state.encoding }?.displayName
                        ?: state.encoding,
                    showArrow = true,
                    onClick = { showEncodingDialog = true }
                )
                SettingsPageRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_text_02),
                    value = state.themeLabel.ifBlank { stringResource(R.string.settings_theme_system) },
                    iconRes = if (useMaterial3Visuals) R.drawable.ic_settings_moon else R.drawable.ic_theme_moon,
                    onClick = { showThemeDialog = true }
                )
            }
            Spacer(Modifier.height(if (useMaterial3Visuals) 8.dp else 20.dp))
            SettingsPageGroup(useMaterial3Visuals) {
                SettingsPageRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_text_04),
                    iconRes = R.drawable.ic_model,
                    onClick = onOpenModelManagement
                )
                SettingsPageRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_ai_entry),
                    iconRes = R.drawable.ic_ai_translate,
                    onClick = onOpenAiSettings
                )
                SettingsPageRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.tts_settings),
                    iconRes = R.drawable.ic_tts,
                    onClick = onOpenTtsSettings
                )
                SettingsPageRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_text_05),
                    value = state.cacheSize,
                    iconRes = R.drawable.ic_delete_normal,
                    onClick = { showCacheDialog = true }
                )
                SettingsPageRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_text_06),
                    iconRes = R.drawable.ic_document,
                    onClick = onOpenLogs
                )
                SettingsPageRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.about),
                    iconRes = R.drawable.ic_info,
                    showArrow = true,
                    onClick = onOpenAbout
                )
                SettingsPageRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_software_directory),
                    value = state.softwareDirectory.ifBlank {
                        stringResource(R.string.activity_settings_software_directory_default)
                    },
                    valueBelow = true,
                    iconRes = R.drawable.ic_folder,
                    showArrow = true,
                    onClick = onSelectSoftwareDirectory
                )
                SettingsPageSwitchRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_text_07),
                    description = stringResource(R.string.activity_settings_text_08),
                    checked = state.checkUpdatesOnStartup,
                    onCheckedChange = onCheckUpdatesChanged
                )
            }
            Spacer(Modifier.height(if (useMaterial3Visuals) 8.dp else 20.dp))
            SettingsPageGroup(useMaterial3Visuals) {
                SettingsPageSwitchRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_text_17),
                    description = stringResource(R.string.activity_settings_text_18),
                    checked = state.preserveOutputDirectories,
                    onCheckedChange = onPreserveDirectoriesChanged
                )
            }
            Spacer(Modifier.height(if (useMaterial3Visuals) 8.dp else 20.dp))
            SectionHeader(stringResource(R.string.activity_settings_text_09))
            SettingsPageGroup(useMaterial3Visuals) {
                SettingsPageSwitchRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_text_10),
                    description = stringResource(R.string.activity_settings_text_11),
                    checked = state.loopSelectedSubtitle,
                    onCheckedChange = onLoopSelectedChanged
                )
                SettingsPageSwitchRow(
                    useMaterial3Visuals = useMaterial3Visuals,
                    title = stringResource(R.string.activity_settings_text_19),
                    description = stringResource(R.string.activity_settings_text_20),
                    checked = state.selectPlayingSubtitle,
                    onCheckedChange = onSelectPlayingChanged
                )
            }
            Spacer(Modifier.height(if (useMaterial3Visuals) 8.dp else 20.dp))
            if (useMaterial3Visuals) SettingsBackupActions(
                onBackup = { showBackupWarning = true },
                onRestore = onRestore
            ) else Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showBackupWarning = true },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_backup))
                }
                Button(
                    onClick = onRestore,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_restore),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_restore))
                }
            }
    }

    if (showBackupWarning) {
        AppDialog(
            onDismissRequest = { showBackupWarning = false },
            title = { Text(stringResource(R.string.settings_backup_warning_title)) },
            text = { Text(stringResource(R.string.settings_backup_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackupWarning = false
                        onBackup()
                    }
                ) { Text(stringResource(R.string.settings_backup_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showBackupWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showEncodingDialog) {
        ChoiceDialog(
            title = stringResource(R.string.activity_settings_text_01),
            choices = encodings.map { AppOption(it.id, it.displayName) },
            selected = state.encoding,
            showCancel = true,
            onDismiss = { showEncodingDialog = false },
            onSelect = { charset ->
                showEncodingDialog = false
                encodings.firstOrNull { it.id == charset }?.let(onEncodingSelected)
            }
        )
    }

    if (showThemeDialog) {
        val themes = listOf(
            AppOption(SettingsManager.THEME_LIGHT, stringResource(R.string.settings_theme_option_light)),
            AppOption(SettingsManager.THEME_DARK, stringResource(R.string.settings_theme_option_dark)),
            AppOption(SettingsManager.THEME_SYSTEM, stringResource(R.string.settings_theme_system))
        )
        ChoiceDialog(
            title = stringResource(R.string.theme),
            choices = themes,
            selected = state.themeMode,
            showCancel = false,
            onDismiss = { showThemeDialog = false },
            onSelect = { mode ->
                showThemeDialog = false
                onThemeSelected(mode)
            }
        )
    }

    if (showCacheDialog) {
        AppDialog(
            onDismissRequest = { showCacheDialog = false },
            title = { Text(stringResource(R.string.clear_cache)) },
            text = {
                Column {
                    state.cacheItems.forEach { item ->
                        TextButton(
                            onClick = {
                                showCacheDialog = false
                                if (item.sizeBytes == 0L) onEmptyCacheClear(item)
                                else if (!confirmCacheClear) onCacheClear(item)
                                else pendingCacheKey = item.key
                            },
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Text(
                                stringResource(R.string.settings_cache_item_with_size, item.label, item.displaySize()),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCacheDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    state.pendingSoftwareDirectory?.let { target ->
        AppDialog(
            onDismissRequest = onCancelSoftwareDirectory,
            title = { Text(stringResource(R.string.activity_settings_software_directory_migrate_title)) },
            text = {
                Text(
                    if (state.pendingSoftwareDirectoryModelCount > 0) {
                        stringResource(
                            R.string.activity_settings_software_directory_migrate_message,
                            state.pendingSoftwareDirectoryModelCount,
                            target
                        )
                    } else {
                        stringResource(R.string.activity_settings_software_directory_change_message, target)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmSoftwareDirectory,
                    enabled = !state.isMigratingSoftwareDirectory
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = onCancelSoftwareDirectory,
                    enabled = !state.isMigratingSoftwareDirectory
                ) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    state.cacheItems.firstOrNull { it.key == pendingCacheKey }?.let { item ->
        AppDialog(
            onDismissRequest = { pendingCacheKey = null },
            title = { Text(stringResource(R.string.clear_cache_item, item.label)) },
            text = { Text(item.confirmationMessage) },
            confirmButton = {
                TextButton(onClick = {
                    pendingCacheKey = null
                    onCacheClear(item)
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCacheKey = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

// The main settings page uses a slightly deeper background than its tiles in light mode.
@Composable
fun settingsPageBackground(): Color = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
    Color(0xFFEEEDF5)
} else {
    MaterialTheme.colorScheme.surface
}

@Composable
private fun settingsLightPalette(): Boolean = MaterialTheme.colorScheme.surface.luminance() > 0.5f

private val SettingsTileRestingCorner = 4.dp
private val SettingsTilePressedCorner = 20.dp

/** Tile corners relax while pressed; the group clips the outer corners of the first and last tile. */
@Composable
private fun settingsTileShape(source: MutableInteractionSource): RoundedCornerShape {
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) SettingsTilePressedCorner else SettingsTileRestingCorner,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "settingsTileCorner"
    )
    return RoundedCornerShape(corner.coerceAtLeast(0.dp))
}

@Composable
private fun SettingsPageRow(
    useMaterial3Visuals: Boolean,
    title: String,
    iconRes: Int? = null,
    value: String? = null,
    valueBelow: Boolean = false,
    showArrow: Boolean = false,
    onClick: () -> Unit
) {
    if (!useMaterial3Visuals) {
        SharedSettingsRow(title = title, iconRes = iconRes, value = value, valueBelow = valueBelow, showArrow = showArrow, onClick = onClick)
        return
    }
    val colors = MaterialTheme.colorScheme
    val light = settingsLightPalette()
    val tile = if (light) Color(0xFFFAF7FE) else colors.surfaceContainerHigh
    val primaryText = if (light) Color(0xFF303139) else colors.onSurface
    val secondaryText = if (light) Color(0xFF68758A) else colors.onSurfaceVariant
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (valueBelow) 80.dp else 68.dp)
            .clip(settingsTileShape(source)).background(tile)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (light) Color(0xFFE2EAFA) else colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(24.dp), tint = if (light) Color(0xFF303139) else colors.onPrimaryContainer)
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = primaryText)
            if (valueBelow && !value.isNullOrEmpty()) Text(
                value, modifier = Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (!valueBelow && !value.isNullOrEmpty()) Text(
            value,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp).clip(CircleShape)
                .background(if (light) Color(0xFFEDF0F7) else colors.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge, color = secondaryText,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right), contentDescription = null,
            modifier = Modifier.padding(start = 4.dp).size(24.dp), tint = secondaryText
        )
    }
}

@Composable
private fun SettingsPageSwitchRow(
    useMaterial3Visuals: Boolean,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    if (!useMaterial3Visuals) {
        SharedSettingsSwitchRow(title = title, description = description, checked = checked, onCheckedChange = onCheckedChange)
        return
    }
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val light = settingsLightPalette()
    val tile = if (light) Color(0xFFFAF7FE) else colors.surfaceContainerHigh
    val container by animateColorAsState(
        targetValue = if (checked && !light) lerp(tile, colors.primaryContainer, 0.5f) else tile,
        label = "settingsSwitchContainer"
    )
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .clip(settingsTileShape(source)).background(container)
            .toggleable(
                value = checked, interactionSource = source, indication = LocalIndication.current, role = Role.Switch,
                onValueChange = {
                    haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onCheckedChange(it)
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (light) Color(0xFF303139) else colors.onSurface)
            if (description.isNotBlank()) Text(
                description, modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = if (light) Color(0xFF68758A) else colors.onSurfaceVariant
            )
        }
        Switch(
            checked = checked, onCheckedChange = null,
            thumbContent = if (checked) {
                { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
            } else null
        )
    }
}

@Composable
private fun SettingsPageGroup(
    useMaterial3Visuals: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    if (useMaterial3Visuals) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    } else {
        SettingsGroup(content = content)
    }
}

@Composable
private fun SettingsBackupActions(onBackup: () -> Unit, onRestore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onBackup, modifier = Modifier.weight(1f).height(56.dp), shape = CircleShape) {
            Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_backup))
        }
        FilledTonalButton(onClick = onRestore, modifier = Modifier.weight(1f).height(56.dp), shape = CircleShape) {
            Icon(painterResource(R.drawable.ic_restore), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_restore))
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    choices: List<AppOption<T>>,
    selected: T,
    showCancel: Boolean,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(choices.size) { index ->
                    val choice = choices[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (choice.id == selected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent,
                                MaterialTheme.shapes.medium
                            )
                            .clickable { onSelect(choice.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = choice.id == selected, onClick = { onSelect(choice.id) })
                        Text(choice.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (showCancel) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

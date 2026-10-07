package com.subtitleedit.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
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
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.ui.components.SectionHeader
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.SettingsGroup
import com.subtitleedit.ui.components.SettingsRow
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
    val confirmationMessage: String
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
    onEmptyCacheClear: (SettingsCacheItem) -> Unit
) {
    var showEncodingDialog by rememberSaveable { mutableStateOf(false) }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showCacheDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCacheKey by rememberSaveable { mutableStateOf<String?>(null) }

    AppToolScaffold(
        title = stringResource(R.string.menu_main_title_02),
        onBack = onBack,
        showTopBar = showTopBar,
        imePadding = true
    ) {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.activity_settings_text_01),
                    value = encodings.firstOrNull { it.id == state.encoding }?.displayName
                        ?: state.encoding,
                    showArrow = true,
                    onClick = { showEncodingDialog = true }
                )
                SettingsRow(
                    title = stringResource(R.string.activity_settings_text_02),
                    value = state.themeLabel.ifBlank { stringResource(R.string.settings_theme_system) },
                    iconRes = R.drawable.ic_theme_moon,
                    onClick = { showThemeDialog = true }
                )
            }
            Spacer(Modifier.height(20.dp))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.activity_settings_text_04),
                    iconRes = R.drawable.ic_model,
                    onClick = onOpenModelManagement
                )
                SettingsRow(
                    title = stringResource(R.string.activity_settings_ai_entry),
                    iconRes = R.drawable.ic_ai_translate,
                    onClick = onOpenAiSettings
                )
                SettingsRow(
                    title = stringResource(R.string.tts_settings),
                    iconRes = R.drawable.ic_tts,
                    onClick = onOpenTtsSettings
                )
                SettingsRow(
                    title = stringResource(R.string.activity_settings_text_05),
                    value = state.cacheSize,
                    iconRes = R.drawable.ic_delete_normal,
                    onClick = { showCacheDialog = true }
                )
                SettingsRow(
                    title = stringResource(R.string.activity_settings_text_06),
                    iconRes = R.drawable.ic_document,
                    onClick = onOpenLogs
                )
                SettingsRow(
                    title = stringResource(R.string.about),
                    iconRes = R.drawable.ic_info,
                    showArrow = true,
                    onClick = onOpenAbout
                )
                SettingsRow(
                    title = stringResource(R.string.activity_settings_software_directory),
                    value = state.softwareDirectory.ifBlank {
                        stringResource(R.string.activity_settings_software_directory_default)
                    },
                    valueBelow = true,
                    iconRes = R.drawable.ic_folder,
                    showArrow = true,
                    onClick = onSelectSoftwareDirectory
                )
                SharedSettingsSwitchRow(
                    title = stringResource(R.string.activity_settings_text_07),
                    description = stringResource(R.string.activity_settings_text_08),
                    checked = state.checkUpdatesOnStartup,
                    onCheckedChange = onCheckUpdatesChanged
                )
            }
            Spacer(Modifier.height(20.dp))
            SettingsGroup {
                SharedSettingsSwitchRow(
                    title = stringResource(R.string.activity_settings_text_17),
                    description = stringResource(R.string.activity_settings_text_18),
                    checked = state.preserveOutputDirectories,
                    onCheckedChange = onPreserveDirectoriesChanged
                )
            }
            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.activity_settings_text_09))
            SettingsGroup {
                SharedSettingsSwitchRow(
                    title = stringResource(R.string.activity_settings_text_10),
                    description = stringResource(R.string.activity_settings_text_11),
                    checked = state.loopSelectedSubtitle,
                    onCheckedChange = onLoopSelectedChanged
                )
                SharedSettingsSwitchRow(
                    title = stringResource(R.string.activity_settings_text_19),
                    description = stringResource(R.string.activity_settings_text_20),
                    checked = state.selectPlayingSubtitle,
                    onCheckedChange = onSelectPlayingChanged
                )
            }
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
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
        AlertDialog(
            onDismissRequest = onCancelSoftwareDirectory,
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
        AlertDialog(
            onDismissRequest = { pendingCacheKey = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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

@Composable
private fun <T> ChoiceDialog(
    title: String,
    choices: List<AppOption<T>>,
    selected: T,
    showCancel: Boolean,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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

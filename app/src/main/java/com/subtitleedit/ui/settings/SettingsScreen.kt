package com.subtitleedit.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import com.subtitleedit.R
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager

data class SettingsPageState(
    val encoding: String = "",
    val themeMode: String = SettingsManager.THEME_SYSTEM,
    val themeLabel: String = "跟随系统",
    val cacheSize: String = "",
    val checkUpdatesOnStartup: Boolean = false,
    val preserveOutputDirectories: Boolean = true,
    val loopSelectedSubtitle: Boolean = false,
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
        sizeBytes < 1024L * 1024 -> "${"%.1f".format(sizeBytes / 1024.0)} KB"
        else -> "${"%.2f".format(sizeBytes / 1024.0 / 1024.0)} MB"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsPageState,
    encodings: List<FileUtils.EncodingInfo>,
    showTopBar: Boolean = true,
    onBack: () -> Unit,
    onEncodingSelected: (FileUtils.EncodingInfo) -> Unit,
    onThemeSelected: (String) -> Unit,
    onCheckUpdatesChanged: (Boolean) -> Unit,
    onPreserveDirectoriesChanged: (Boolean) -> Unit,
    onLoopSelectedChanged: (Boolean) -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenModelManagement: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    onCacheClear: (SettingsCacheItem) -> Unit,
    onEmptyCacheClear: (SettingsCacheItem) -> Unit
) {
    var showEncodingDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    var pendingCacheItem by remember { mutableStateOf<SettingsCacheItem?>(null) }

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = { Text(stringResource(R.string.menu_main_title_02)) },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back),
                                contentDescription = stringResource(R.string.tools_navigate_back)
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            modifier = Modifier.padding(padding)
        ) {
            item {
                SettingsNavigationRow(
                    title = stringResource(R.string.activity_settings_text_01),
                    value = state.encoding,
                    icon = R.drawable.ic_document,
                    onClick = { showEncodingDialog = true }
                )
            }
            item {
                SettingsNavigationRow(
                    title = stringResource(R.string.activity_settings_text_02),
                    value = state.themeLabel,
                    icon = R.drawable.ic_theme_moon,
                    onClick = { showThemeDialog = true }
                )
            }
            item {
                SettingsNavigationRow(
                    title = stringResource(R.string.activity_settings_ai_entry),
                    icon = R.drawable.ic_ai_translate,
                    onClick = onOpenAiSettings
                )
            }
            item {
                SettingsNavigationRow(
                    title = stringResource(R.string.activity_settings_text_04),
                    icon = R.drawable.ic_model,
                    onClick = onOpenModelManagement
                )
            }
            item {
                SettingsNavigationRow(
                    title = stringResource(R.string.tts_settings),
                    icon = R.drawable.ic_tts,
                    onClick = onOpenTtsSettings
                )
            }
            item {
                SettingsNavigationRow(
                    title = stringResource(R.string.activity_settings_text_05),
                    value = state.cacheSize,
                    icon = R.drawable.ic_sweep,
                    onClick = { showCacheDialog = true }
                )
            }
            item {
                SettingsNavigationRow(
                    title = stringResource(R.string.activity_settings_text_06),
                    icon = R.drawable.ic_document,
                    onClick = onOpenLogs
                )
            }
            item { HorizontalDivider() }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.activity_settings_text_07),
                    description = stringResource(R.string.activity_settings_text_08),
                    checked = state.checkUpdatesOnStartup,
                    onCheckedChange = onCheckUpdatesChanged
                )
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.activity_settings_text_17),
                    description = stringResource(R.string.activity_settings_text_18),
                    checked = state.preserveOutputDirectories,
                    onCheckedChange = onPreserveDirectoriesChanged
                )
            }
            item {
                Text(
                    text = stringResource(R.string.activity_settings_text_09),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.activity_settings_text_10),
                    description = stringResource(R.string.activity_settings_text_11),
                    checked = state.loopSelectedSubtitle,
                    onCheckedChange = onLoopSelectedChanged
                )
            }
            item {
                HorizontalDivider()
                SettingsNavigationRow(
                    title = stringResource(R.string.about),
                    icon = R.drawable.ic_info,
                    onClick = onOpenAbout
                )
            }
        }
    }

    if (showEncodingDialog) {
        ChoiceDialog(
            title = stringResource(R.string.activity_settings_text_01),
            choices = encodings.map { it.displayName },
            selected = state.encoding,
            onDismiss = { showEncodingDialog = false },
            onSelect = { index ->
                showEncodingDialog = false
                encodings.getOrNull(index)?.let(onEncodingSelected)
            }
        )
    }

    if (showThemeDialog) {
        val themes = listOf(
            "亮色主题" to SettingsManager.THEME_LIGHT,
            "深色主题" to SettingsManager.THEME_DARK,
            "跟随系统" to SettingsManager.THEME_SYSTEM
        )
        ChoiceDialog(
            title = "主题",
            choices = themes.map { it.first },
            selected = themes.firstOrNull { it.second == state.themeMode }?.first.orEmpty(),
            onDismiss = { showThemeDialog = false },
            onSelect = { index ->
                showThemeDialog = false
                themes.getOrNull(index)?.second?.let(onThemeSelected)
            }
        )
    }

    if (showCacheDialog) {
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            title = { Text("清除缓存") },
            text = {
                Column {
                    state.cacheItems.forEach { item ->
                        TextButton(
                            onClick = {
                                showCacheDialog = false
                                if (item.sizeBytes == 0L) onEmptyCacheClear(item)
                                else pendingCacheItem = item
                            },
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Text("${item.label}（${item.displaySize()}）", modifier = Modifier.fillMaxWidth())
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

    pendingCacheItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingCacheItem = null },
            title = { Text("清除${item.label}") },
            text = { Text(item.confirmationMessage) },
            confirmButton = {
                TextButton(onClick = {
                    pendingCacheItem = null
                    onCacheClear(item)
                }) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCacheItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    icon: Int,
    value: String? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = value?.takeIf(String::isNotEmpty)?.let { { Text(it) } },
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    choices: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(choices.size) { index ->
                    val choice = choices[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = choice == selected, onClick = { onSelect(index) })
                        Text(choice, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

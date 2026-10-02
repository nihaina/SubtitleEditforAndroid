package com.subtitleedit.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import com.subtitleedit.R
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import java.util.Locale

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
                modifier = Modifier.height(56.dp),
                    title = { Text(stringResource(R.string.menu_main_title_02)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SettingsNavigationRow(
                title = stringResource(R.string.activity_settings_text_01),
                value = state.encoding,
                showArrow = true,
                boldTitle = true,
                onClick = { showEncodingDialog = true }
            )
            Spacer(Modifier.height(12.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.activity_settings_text_02),
                value = state.themeLabel,
                icon = R.drawable.ic_theme_moon,
                onClick = { showThemeDialog = true }
            )
            Spacer(Modifier.height(20.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.activity_settings_ai_entry),
                icon = R.drawable.ic_ai_translate,
                onClick = onOpenAiSettings
            )
            Spacer(Modifier.height(4.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.activity_settings_text_04),
                icon = R.drawable.ic_model,
                onClick = onOpenModelManagement
            )
            Spacer(Modifier.height(4.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.tts_settings),
                icon = R.drawable.ic_tts,
                onClick = onOpenTtsSettings
            )
            Spacer(Modifier.height(4.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.activity_settings_text_05),
                value = state.cacheSize,
                icon = R.drawable.ic_sweep,
                onClick = { showCacheDialog = true }
            )
            Spacer(Modifier.height(4.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.activity_settings_text_06),
                icon = R.drawable.ic_document,
                onClick = onOpenLogs
            )
            SettingsSwitchRow(
                title = stringResource(R.string.activity_settings_text_07),
                description = stringResource(R.string.activity_settings_text_08),
                checked = state.checkUpdatesOnStartup,
                topPadding = 12,
                bottomPadding = 8,
                onCheckedChange = onCheckUpdatesChanged
            )
            SettingsSwitchRow(
                title = stringResource(R.string.activity_settings_text_17),
                description = stringResource(R.string.activity_settings_text_18),
                checked = state.preserveOutputDirectories,
                topPadding = 4,
                bottomPadding = 8,
                onCheckedChange = onPreserveDirectoriesChanged
            )
            Text(
                text = stringResource(R.string.activity_settings_text_09),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            SettingsSwitchRow(
                title = stringResource(R.string.activity_settings_text_10),
                description = stringResource(R.string.activity_settings_text_11),
                checked = state.loopSelectedSubtitle,
                topPadding = 4,
                bottomPadding = 4,
                onCheckedChange = onLoopSelectedChanged
            )
            Spacer(Modifier.height(4.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.about),
                icon = R.drawable.ic_info,
                showArrow = true,
                onClick = onOpenAbout
            )
        }
    }

    if (showEncodingDialog) {
        ChoiceDialog(
            title = stringResource(R.string.activity_settings_text_01),
            choices = encodings.map { it.displayName },
            selected = state.encoding,
            showCancel = true,
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
            showCancel = false,
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
                                else if (!confirmCacheClear) onCacheClear(item)
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
    icon: Int? = null,
    value: String? = null,
    showArrow: Boolean = false,
    boldTitle: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = if (boldTitle) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (!value.isNullOrEmpty()) {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                modifier = if (showArrow) Modifier.padding(end = 8.dp) else Modifier
            )
        }
        if (showArrow) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    topPadding: Int,
    bottomPadding: Int,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding.dp, bottom = bottomPadding.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    choices: List<String>,
    selected: String,
    showCancel: Boolean,
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
            if (showCancel) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

package com.subtitleedit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.ui.settings.SettingsTileCard
import com.subtitleedit.ui.settings.settingsOutlinedTextFieldColors
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.settings.settingsPrimaryTextColor
import com.subtitleedit.ui.settings.settingsSecondaryTextColor
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/** System TTS engine selection page. */
class TtsSettingsActivity : AppComposeActivity() {
    private val viewModel: TtsSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                TtsSettingsScreen(
                    engineOptions = state.engineOptions,
                    selectedEngine = state.selectedEngine,
                    selectedLanguage = state.selectedLanguage,
                    installedEngineCount = state.installedEngineCount,
                    onEngineSelected = viewModel::selectEngine,
                    onLanguageSelected = viewModel::selectLanguage,
                    onSystemSettings = ::openSystemTtsSettings,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }

    private fun openSystemTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            // Some customized system builds do not expose a standalone TTS settings page.
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}

private data class LanguageOption(val label: String, val value: String)

@Composable
internal fun TtsSettingsScreen(
    engineOptions: List<TtsEngineOption>,
    selectedEngine: String,
    selectedLanguage: String,
    installedEngineCount: Int,
    onEngineSelected: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onSystemSettings: () -> Unit,
    onBack: () -> Unit
) {
    val languageOptions = listOf(
        LanguageOption(stringResource(R.string.tts_settings_language_auto), SettingsManager.TTS_LANGUAGE_AUTO),
        LanguageOption(stringResource(R.string.tts_settings_language_system), SettingsManager.TTS_LANGUAGE_SYSTEM),
        LanguageOption(stringResource(R.string.tts_settings_language_japanese), SettingsManager.TTS_LANGUAGE_JAPANESE),
        LanguageOption(stringResource(R.string.tts_settings_language_chinese), SettingsManager.TTS_LANGUAGE_CHINESE),
        LanguageOption(stringResource(R.string.tts_settings_language_english), SettingsManager.TTS_LANGUAGE_ENGLISH)
    )
    // Selection lives in TtsSettingsViewModel, so it already survives recreation.
    val currentEngine = selectedEngine
    val currentLanguage = languageOptions.firstOrNull { it.value == selectedLanguage }?.value
        ?: SettingsManager.TTS_LANGUAGE_AUTO
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val pageBackground = settingsPageBackground()
    AppToolScaffold(
        title = stringResource(R.string.tts_settings),
        onBack = onBack,
        imePadding = true,
        contentBackground = pageBackground,
        topBarContainerColor = pageBackground,
        topBarScrolledContainerColor = pageBackground
    ) {
        TtsSection(
            title = stringResource(R.string.tts_engine),
            description = stringResource(R.string.tts_engine_description),
            action = {
                IconButton(onClick = onSystemSettings, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.tts_system_settings),
                        tint = settingsSecondaryTextColor()
                    )
                }
            }
        ) {
            TtsDropdown(
                label = stringResource(R.string.tts_engine),
                options = engineOptions.map { it.label to it.packageName },
                selectedValue = currentEngine,
                onSelected = onEngineSelected
            )
            Text(
                text = if (installedEngineCount == 0) {
                    stringResource(R.string.tts_no_engines)
                } else {
                    stringResource(R.string.tts_engine_count, installedEngineCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = settingsSecondaryTextColor()
            )
        }

        TtsSection(
            title = stringResource(R.string.tts_language),
            description = stringResource(R.string.tts_language_description)
        ) {
            TtsDropdown(
                label = stringResource(R.string.tts_language),
                options = languageOptions.map { it.label to it.value },
                selectedValue = currentLanguage,
                onSelected = onLanguageSelected
            )
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = { showHelp = true }) {
                Text(
                    text = stringResource(R.string.tts_help),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    if (showHelp) {
        AppDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.tts_help)) },
            text = { Text(stringResource(R.string.tts_help_message)) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }
}

/** Settings-page tile: title row (optional trailing action), description, then the controls. */
@Composable
private fun TtsSection(
    title: String,
    description: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsTileCard {
        CompositionLocalProvider(LocalContentColor provides settingsPrimaryTextColor()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = settingsPrimaryTextColor(),
                    modifier = Modifier.weight(1f)
                )
                action?.invoke()
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = settingsSecondaryTextColor(),
                modifier = Modifier.padding(top = 4.dp)
            )
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.second == selectedValue }?.first
        ?: options.firstOrNull()?.first.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it && options.isNotEmpty() }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = MaterialTheme.shapes.large,
            colors = settingsOutlinedTextFieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (optionLabel, value) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        if (value != selectedValue) onSelected(value)
                    }
                )
            }
        }
    }
}

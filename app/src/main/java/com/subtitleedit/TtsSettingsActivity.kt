package com.subtitleedit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

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

    AppToolScaffold(
        title = stringResource(R.string.tts_settings),
        onBack = onBack,
        imePadding = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.tts_engine),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSystemSettings, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.tts_system_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = stringResource(R.string.tts_engine_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            TtsDropdown(
                options = engineOptions.map { it.label to it.packageName },
                selectedValue = currentEngine,
                onSelected = onEngineSelected
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (installedEngineCount == 0) {
                    stringResource(R.string.tts_no_engines)
                } else {
                    stringResource(R.string.tts_engine_count, installedEngineCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.tts_language),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.tts_language_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            TtsDropdown(
                options = languageOptions.map { it.label to it.value },
                selectedValue = currentLanguage,
                onSelected = onLanguageSelected
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(48.dp)
                    .clickable { showHelp = true }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = stringResource(R.string.tts_help),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End
                )
            }
        }
    }

    if (showHelp) {
        AlertDialog(
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

@Composable
private fun TtsDropdown(
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelected: (String) -> Unit
) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        factory = { context ->
            Spinner(context).apply {
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_item,
                    options.map { it.first }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                val initial = options.indexOfFirst { it.second == selectedValue }.coerceAtLeast(0)
                setSelection(initial, false)
                var lastSelectedValue = options.getOrNull(initial)?.second.orEmpty()
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long
                    ) {
                        val value = options.getOrNull(position)?.second ?: return
                        if (value != lastSelectedValue) {
                            lastSelectedValue = value
                            onSelected(value)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
        },
        update = { spinner ->
            val selected = options.indexOfFirst { it.second == selectedValue }.coerceAtLeast(0)
            if (spinner.selectedItemPosition != selected) spinner.setSelection(selected)
        }
    )
}

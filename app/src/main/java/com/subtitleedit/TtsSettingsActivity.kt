package com.subtitleedit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsManager = SettingsManager.getInstance(this)
        val engineOptions = loadEngineOptions()
        val savedEngine = settingsManager.getTtsEngine()
        // Keep the legacy fallback: a missing/uninstalled saved engine resets to
        // the system default instead of silently selecting the first installed one.
        val initialEngine = engineOptions.firstOrNull { it.packageName == savedEngine }
            ?: engineOptions.first()
        if (initialEngine.packageName != savedEngine) {
            settingsManager.setTtsEngine("")
        }
        val savedLanguage = settingsManager.getTtsLanguage()
        val initialLanguage = savedLanguage.takeIf { it in supportedLanguageValues }
            ?: SettingsManager.TTS_LANGUAGE_AUTO

        setContent {
            SubtitleEditComposeTheme {
                TtsSettingsScreen(
                    engineOptions = engineOptions,
                    selectedEngine = initialEngine.packageName,
                    selectedLanguage = initialLanguage,
                    installedEngineCount = engineOptions.size - 1,
                    onEngineSelected = settingsManager::setTtsEngine,
                    onLanguageSelected = settingsManager::setTtsLanguage,
                    onSystemSettings = ::openSystemTtsSettings,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadEngineOptions(): List<TtsEngineOption> {
        val installed = packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_ALL
        ).mapNotNull { info ->
            val serviceInfo = info.serviceInfo ?: return@mapNotNull null
            TtsEngineOption(
                label = info.loadLabel(packageManager)?.toString()?.ifBlank { serviceInfo.packageName }
                    ?: serviceInfo.packageName,
                packageName = serviceInfo.packageName
            )
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

        return listOf(TtsEngineOption("系统默认", "")) + installed
    }

    private fun openSystemTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            // Some customized system builds do not expose a standalone TTS settings page.
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private companion object {
        val supportedLanguageValues = setOf(
            SettingsManager.TTS_LANGUAGE_AUTO,
            SettingsManager.TTS_LANGUAGE_SYSTEM,
            SettingsManager.TTS_LANGUAGE_JAPANESE,
            SettingsManager.TTS_LANGUAGE_CHINESE,
            SettingsManager.TTS_LANGUAGE_ENGLISH
        )
    }
}

private data class TtsEngineOption(val label: String, val packageName: String)

private data class LanguageOption(val label: String, val value: String)

@Composable
private fun TtsSettingsScreen(
    engineOptions: List<TtsEngineOption>,
    selectedEngine: String,
    selectedLanguage: String,
    installedEngineCount: Int,
    onEngineSelected: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onSystemSettings: () -> Unit,
    onBack: () -> Unit
) {
    val languageOptions = remember {
        listOf(
            LanguageOption("自动判断", SettingsManager.TTS_LANGUAGE_AUTO),
            LanguageOption("跟随系统", SettingsManager.TTS_LANGUAGE_SYSTEM),
            LanguageOption("日语（日本）", SettingsManager.TTS_LANGUAGE_JAPANESE),
            LanguageOption("中文（简体）", SettingsManager.TTS_LANGUAGE_CHINESE),
            LanguageOption("英语（美国）", SettingsManager.TTS_LANGUAGE_ENGLISH)
        )
    }
    var currentEngine by rememberSaveable { mutableStateOf(selectedEngine) }
    var currentLanguage by rememberSaveable {
        mutableStateOf(languageOptions.firstOrNull { it.value == selectedLanguage }?.value
            ?: SettingsManager.TTS_LANGUAGE_AUTO)
    }
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
                onSelected = { packageName ->
                    currentEngine = packageName
                    onEngineSelected(packageName)
                }
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
                onSelected = { language ->
                    currentLanguage = language
                    onLanguageSelected(language)
                }
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

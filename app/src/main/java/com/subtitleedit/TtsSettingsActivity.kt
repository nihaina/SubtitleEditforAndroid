package com.subtitleedit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

/** System TTS engine selection page. */
class TtsSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsManager = SettingsManager.getInstance(this)
        val engineOptions = loadEngineOptions()
        val savedEngine = settingsManager.getTtsEngine()
        val initialEngine = engineOptions.firstOrNull { it.packageName == savedEngine }
            ?: engineOptions.first()
        if (initialEngine.packageName != savedEngine) {
            settingsManager.setTtsEngine(initialEngine.packageName)
        }

        setContent {
            SubtitleEditComposeTheme {
                TtsSettingsScreen(
                    engineOptions = engineOptions,
                    selectedEngine = initialEngine.packageName,
                    selectedLanguage = settingsManager.getTtsLanguage(),
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
}

private data class TtsEngineOption(val label: String, val packageName: String)

private data class LanguageOption(val label: String, val value: String)

@OptIn(ExperimentalMaterial3Api::class)
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
    var currentEngine by remember { mutableStateOf(selectedEngine) }
    var currentLanguage by remember {
        mutableStateOf(languageOptions.firstOrNull { it.value == selectedLanguage }?.value
            ?: SettingsManager.TTS_LANGUAGE_AUTO)
    }
    var showHelp by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tts_settings)) },
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
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.tts_engine),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSystemSettings) {
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
                fontSize = 13.sp
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
                    "未检测到可用的朗读引擎，请先在系统中安装或启用 TTS 引擎。"
                } else {
                    "检测到 $installedEngineCount 个朗读引擎；选择后立即保存。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.tts_language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.tts_language_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showHelp = true }) {
                    Text(stringResource(R.string.tts_help))
                }
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
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.second == selectedValue }?.first
        ?: options.firstOrNull()?.first.orEmpty()

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    modifier = Modifier.rotate(90f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    }
                )
            }
        }
    }
}

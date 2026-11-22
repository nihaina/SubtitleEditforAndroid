package com.subtitleedit.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

data class WhisperSettingsState(
    val dynamicPaddingEnabled: Boolean = true,
    val threads: Int = 4,
    val hotwordsEnabled: Boolean = false,
    val hotwords: String = "",
    val hotwordsScore: Float = 1.5f
)

@Composable
fun WhisperSettingsScreen(
    state: WhisperSettingsState,
    onBack: () -> Unit,
    onOpenVadSettings: () -> Unit,
    onDynamicPaddingEnabledChanged: (Boolean) -> Unit,
    onThreadsChanged: (Float) -> Unit,
    onHotwordsEnabledChanged: (Boolean) -> Unit,
    onHotwordsChanged: (String) -> Unit,
    onSaveHotwords: () -> Unit,
    onHotwordsScoreChanged: (Float) -> Unit
) {
    val locale = LocalLocale.current.platformLocale
    ModelSettingsScaffold(
        title = stringResource(R.string.activity_whisper_settings_title),
        onBack = onBack,
    ) {
        ModelSettingsGroup(title = stringResource(R.string.activity_whisper_settings_vad_title)) {
            SettingsTileSwitchRow(
                title = stringResource(R.string.activity_speech_to_subtitle_settings_text_14),
                description = stringResource(R.string.activity_speech_to_subtitle_settings_text_15),
                checked = state.dynamicPaddingEnabled,
                onCheckedChange = onDynamicPaddingEnabledChanged,
            )
            SettingsTileRow(title = stringResource(R.string.asr_vad_settings_entry), onClick = onOpenVadSettings)
        }
        ModelSettingsSliderTile(
            title = stringResource(R.string.activity_whisper_settings_threads_title),
            description = stringResource(R.string.activity_whisper_settings_threads_hint),
            value = state.threads.toFloat(),
            valueLabel = String.format(locale, "%d", state.threads),
            onValueChange = onThreadsChanged,
            valueRange = 1f..8f,
            steps = 6,
            grouped = false,
        )
        SettingsTileGroup {
            SettingsTileSwitchRow(
                title = stringResource(R.string.activity_whisper_settings_hotwords_title),
                description = stringResource(R.string.activity_whisper_settings_hotwords_hint),
                checked = state.hotwordsEnabled,
                onCheckedChange = onHotwordsEnabledChanged,
            )
            SettingsTileCard(grouped = true) {
                OutlinedTextField(
                    value = state.hotwords,
                    onValueChange = onHotwordsChanged,
                    label = { Text(stringResource(R.string.activity_whisper_settings_hotwords_input_hint)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = settingsOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                ModelSettingsHint(
                    stringResource(R.string.activity_whisper_settings_hotwords_format_hint),
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = onSaveHotwords, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.activity_whisper_settings_hotwords_save))
                }
            }
            ModelSettingsSliderTile(
                title = stringResource(R.string.activity_whisper_settings_hotwords_score_title),
                value = state.hotwordsScore,
                valueLabel = String.format(locale, "%.1f", state.hotwordsScore),
                onValueChange = onHotwordsScoreChanged,
                valueRange = 0.5f..5f,
                steps = 44,
            )
        }
    }
}

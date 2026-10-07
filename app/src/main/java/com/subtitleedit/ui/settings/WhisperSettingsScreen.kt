package com.subtitleedit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppSlider
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SectionHeader
import com.subtitleedit.ui.components.SettingsSwitchRow

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
    AppToolScaffold(
        title = stringResource(R.string.activity_whisper_settings_title),
        onBack = onBack,
        imePadding = true
    ) {
            AppSection {
                Text(
                    text = stringResource(R.string.activity_whisper_settings_vad_title),
                    style = MaterialTheme.typography.titleMedium
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_14),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_15),
                    checked = state.dynamicPaddingEnabled,
                    onCheckedChange = onDynamicPaddingEnabledChanged,
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = onOpenVadSettings,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(stringResource(R.string.asr_vad_settings_entry))
                }
            }

            AppSection {
                SectionHeader(stringResource(R.string.activity_whisper_settings_threads_title))
                Text(
                    text = stringResource(R.string.activity_whisper_settings_threads_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppSlider(
                        value = state.threads.toFloat(),
                        onValueChange = onThreadsChanged,
                        valueRange = 1f..8f,
                        steps = 6,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = String.format(locale, "%d", state.threads),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(56.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            AppSection {
                SettingsSwitchRow(
                    title = stringResource(R.string.activity_whisper_settings_hotwords_title),
                    description = stringResource(R.string.activity_whisper_settings_hotwords_hint),
                    checked = state.hotwordsEnabled,
                    onCheckedChange = onHotwordsEnabledChanged
                )
                OutlinedTextField(
                    value = state.hotwords,
                    onValueChange = onHotwordsChanged,
                    label = { Text(stringResource(R.string.activity_whisper_settings_hotwords_input_hint)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
                Text(
                    text = stringResource(R.string.activity_whisper_settings_hotwords_format_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                OutlinedButton(
                    onClick = onSaveHotwords,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.activity_whisper_settings_hotwords_save))
                }
                Text(
                    text = stringResource(R.string.activity_whisper_settings_hotwords_score_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppSlider(
                        value = state.hotwordsScore,
                        onValueChange = onHotwordsScoreChanged,
                        valueRange = 0.5f..5f,
                        steps = 44,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = String.format(locale, "%.1f", state.hotwordsScore),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(56.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
    }
}

package com.subtitleedit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSlider

data class WhisperSettingsState(
    val threads: Int = 4,
    val hotwordsEnabled: Boolean = false,
    val hotwords: String = "",
    val hotwordsScore: Float = 1.5f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperSettingsScreen(
    state: WhisperSettingsState,
    onBack: () -> Unit,
    onOpenVadSettings: () -> Unit,
    onThreadsChanged: (Float) -> Unit,
    onHotwordsEnabledChanged: (Boolean) -> Unit,
    onHotwordsChanged: (String) -> Unit,
    onSaveHotwords: () -> Unit,
    onHotwordsScoreChanged: (Float) -> Unit
) {
    val locale = LocalLocale.current.platformLocale
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.height(56.dp),
                title = { Text(stringResource(R.string.activity_whisper_settings_title)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            WhisperSettingsCard {
                Text(
                    text = stringResource(R.string.activity_whisper_settings_vad_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = onOpenVadSettings,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(stringResource(R.string.asr_vad_settings_entry))
                }
            }

            WhisperSettingsCard {
                Text(
                    text = stringResource(R.string.activity_whisper_settings_threads_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
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

            WhisperSettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.hotwordsEnabled,
                            role = Role.Switch,
                            onValueChange = onHotwordsEnabledChanged
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.activity_whisper_settings_hotwords_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = state.hotwordsEnabled, onCheckedChange = null)
                }
                Text(
                    text = stringResource(R.string.activity_whisper_settings_hotwords_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
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
}

@Composable
private fun WhisperSettingsCard(content: @Composable () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            content()
        }
    }
}

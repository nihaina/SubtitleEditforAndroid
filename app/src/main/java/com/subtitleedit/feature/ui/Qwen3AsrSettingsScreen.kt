package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.ui.components.AppCard
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Qwen3AsrSettingsScreen(
    settingsManager: SettingsManager,
    forcedAlignmentAvailable: Boolean,
    refreshKey: Int,
    onNavigateBack: () -> Unit,
    onOpenVadSettings: () -> Unit
) {
    val initial = remember(refreshKey) {
        val gap = ((settingsManager.getSpeechTokenTimestampMergeGapMs() + 25) / 50) * 50
        Qwen3AsrSettingsValues(
            useVadTimestamp = settingsManager.isAsrVadTimestampEnabled(SettingsManager.ASR_MODEL_QWEN3_ASR),
            forcedAlignment = forcedAlignmentAvailable && settingsManager.isSpeechTokenTimestampEnabled(),
            mergeSegments = settingsManager.isSpeechTokenTimestampMergeEnabled(),
            smartMerge = settingsManager.isSpeechTokenTimestampSmartMergeEnabled(),
            filterLongMerge = settingsManager.isSpeechTokenTimestampLongSegmentFilterEnabled(),
            maxCharacters = settingsManager.getSpeechTokenTimestampMergeMaxCharacters(),
            mergeGapMs = gap
        )
    }

    var useVadTimestamp by remember(refreshKey) { mutableStateOf(initial.useVadTimestamp) }
    var forcedAlignment by remember(refreshKey) { mutableStateOf(initial.forcedAlignment) }
    var mergeSegments by remember(refreshKey) { mutableStateOf(initial.mergeSegments) }
    var smartMerge by remember(refreshKey) { mutableStateOf(initial.smartMerge) }
    var filterLongMerge by remember(refreshKey) { mutableStateOf(initial.filterLongMerge) }
    var maxCharacters by remember(refreshKey) { mutableIntStateOf(initial.maxCharacters) }
    var mergeGapMs by remember(refreshKey) { mutableIntStateOf(initial.mergeGapMs) }
    var mergeGapText by remember(refreshKey) { mutableStateOf(initial.mergeGapMs.toString()) }

    val alignerEnabled = forcedAlignmentAvailable && forcedAlignment
    val mergeEnabled = alignerEnabled && mergeSegments
    val fixedGapEnabled = mergeEnabled && !smartMerge
    val maxCharactersEnabled = mergeEnabled && filterLongMerge

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.height(56.dp),
                title = { Text(stringResource(R.string.qwen3_asr_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回"
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
            SettingsCard {
                SectionTitle(R.string.qwen3_asr_vad_title)
                SwitchSettingRow(
                    title = stringResource(R.string.qwen3_asr_vad_switch),
                    checked = useVadTimestamp,
                    onCheckedChange = { enabled ->
                        useVadTimestamp = enabled
                        settingsManager.setAsrVadTimestampEnabled(
                            SettingsManager.ASR_MODEL_QWEN3_ASR,
                            enabled
                        )
                        if (!enabled) {
                            forcedAlignment = forcedAlignmentAvailable &&
                                settingsManager.isSpeechTokenTimestampEnabled()
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp)
                )
                SupportingText(
                    text = stringResource(R.string.qwen3_asr_vad_hint),
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (useVadTimestamp) {
                    OutlinedButton(
                        onClick = onOpenVadSettings,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text(stringResource(R.string.asr_vad_settings_entry))
                    }
                }
            }

            if (!useVadTimestamp) {
                SettingsCard {
                    SectionTitle(R.string.activity_speech_to_subtitle_settings_text_55)
                    SwitchSettingRow(
                        title = stringResource(R.string.qwen3_asr_forced_alignment_switch),
                        checked = forcedAlignment,
                        enabled = forcedAlignmentAvailable,
                        onCheckedChange = { enabled ->
                            forcedAlignment = enabled
                            settingsManager.setSpeechTokenTimestampEnabled(enabled)
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    SupportingText(
                        text = stringResource(R.string.qwen3_asr_forced_alignment_hint),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (!forcedAlignmentAvailable) {
                        Text(
                            text = stringResource(R.string.qwen3_asr_forced_alignment_missing),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    SwitchSettingRow(
                        title = stringResource(R.string.activity_speech_to_subtitle_settings_text_50),
                        checked = mergeSegments,
                        enabled = alignerEnabled,
                        opacity = if (alignerEnabled) 1f else 0.5f,
                        onCheckedChange = { enabled ->
                            mergeSegments = enabled
                            settingsManager.setSpeechTokenTimestampMergeEnabled(enabled)
                        },
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    SupportingText(
                        text = stringResource(R.string.qwen3_asr_merge_hint),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .alpha(if (alignerEnabled) 1f else 0.5f)
                    )

                    SwitchSettingRow(
                        title = stringResource(R.string.speech_merge_smart),
                        checked = smartMerge,
                        enabled = mergeEnabled,
                        opacity = if (mergeEnabled) 1f else 0.5f,
                        onCheckedChange = { enabled ->
                            smartMerge = enabled
                            settingsManager.setSpeechTokenTimestampSmartMergeEnabled(enabled)
                        }
                    )
                    SupportingText(
                        text = stringResource(R.string.speech_merge_smart_hint),
                        modifier = Modifier.alpha(if (mergeEnabled) 1f else 0.5f)
                    )

                    Text(
                        text = stringResource(R.string.activity_speech_to_subtitle_settings_text_52),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .alpha(if (fixedGapEnabled) 1f else 0.5f)
                    )
                    SupportingText(
                        text = stringResource(R.string.activity_speech_to_subtitle_settings_text_53),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .alpha(if (fixedGapEnabled) 1f else 0.5f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (fixedGapEnabled) 1f else 0.5f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = mergeGapMs.toFloat(),
                            onValueChange = { value ->
                                val gap = ((value.roundToInt().coerceIn(0, 5000) + 25) / 50) * 50
                                mergeGapMs = gap
                                mergeGapText = gap.toString()
                                settingsManager.setSpeechTokenTimestampMergeGapMs(gap)
                            },
                            valueRange = 0f..5000f,
                            steps = 99,
                            enabled = fixedGapEnabled,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = mergeGapText,
                            onValueChange = { input ->
                                mergeGapText = input
                                input.toIntOrNull()?.let { value ->
                                    val snapped = ((value.coerceIn(0, 5000) + 25) / 50) * 50
                                    mergeGapMs = snapped
                                    if (input != snapped.toString()) {
                                        mergeGapText = snapped.toString()
                                    }
                                    settingsManager.setSpeechTokenTimestampMergeGapMs(snapped)
                                }
                            },
                            enabled = fixedGapEnabled,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            suffix = { Text("毫秒", style = MaterialTheme.typography.bodySmall) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                textAlign = TextAlign.Center
                            ),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .width(104.dp)
                        )
                    }

                    SwitchSettingRow(
                        title = stringResource(R.string.speech_merge_filter_long),
                        checked = filterLongMerge,
                        enabled = mergeEnabled,
                        opacity = if (mergeEnabled) 1f else 0.5f,
                        onCheckedChange = { enabled ->
                            filterLongMerge = enabled
                            settingsManager.setSpeechTokenTimestampLongSegmentFilterEnabled(enabled)
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (maxCharactersEnabled) 1f else 0.5f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.speech_merge_max_characters),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = maxCharacters.toFloat(),
                            onValueChange = { value ->
                                val count = value.roundToInt().coerceIn(15, 50)
                                maxCharacters = count
                                settingsManager.setSpeechTokenTimestampMergeMaxCharacters(count)
                            },
                            valueRange = 15f..50f,
                            steps = 34,
                            enabled = maxCharactersEnabled,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = maxCharacters.toString(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(40.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(resourceId: Int) {
    Text(
        text = stringResource(resourceId),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall
    )
}

@Composable
private fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
    )
}

@Composable
private fun SwitchSettingRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    opacity: Float = 1f,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .alpha(opacity)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
    }
}

private data class Qwen3AsrSettingsValues(
    val useVadTimestamp: Boolean,
    val forcedAlignment: Boolean,
    val mergeSegments: Boolean,
    val smartMerge: Boolean,
    val filterLongMerge: Boolean,
    val maxCharacters: Int,
    val mergeGapMs: Int
)

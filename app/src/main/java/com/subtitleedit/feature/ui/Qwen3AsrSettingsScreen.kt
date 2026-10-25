package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppSlider
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SectionHeader
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.ui.settings.AsrPunctuationSplitSettings
import com.subtitleedit.util.SettingsManager
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
        val gap = ((settingsManager.getSpeechTokenTimestampMergeGapMs(SettingsManager.ASR_MODEL_QWEN3_ASR) + 25) / 50) * 50
        Qwen3AsrSettingsValues(
            useVadTimestamp = settingsManager.isAsrVadTimestampEnabled(SettingsManager.ASR_MODEL_QWEN3_ASR),
            dynamicPaddingEnabled = settingsManager.isSpeechVadDynamicPaddingEnabled(
                SettingsManager.ASR_MODEL_QWEN3_ASR
            ),
            forcedAlignment = forcedAlignmentAvailable && settingsManager.isSpeechTokenTimestampEnabled(
                SettingsManager.ASR_MODEL_QWEN3_ASR
            ),
            mergeSegments = settingsManager.isSpeechTokenTimestampMergeEnabled(
                SettingsManager.ASR_MODEL_QWEN3_ASR
            ),
            smartMerge = settingsManager.isSpeechTokenTimestampSmartMergeEnabled(
                SettingsManager.ASR_MODEL_QWEN3_ASR
            ),
            filterLongMerge = settingsManager.isSpeechTokenTimestampLongSegmentFilterEnabled(
                SettingsManager.ASR_MODEL_QWEN3_ASR
            ),
            punctuationSplit = settingsManager.isSpeechTokenTimestampPunctuationSplitEnabled(
                SettingsManager.ASR_MODEL_QWEN3_ASR
            ),
            splitPunctuation = settingsManager.getSpeechTokenTimestampSplitPunctuation(
                SettingsManager.ASR_MODEL_QWEN3_ASR
            ),
            maxCharacters = settingsManager.getSpeechTokenTimestampMergeMaxCharacters(
                SettingsManager.ASR_MODEL_QWEN3_ASR
            ),
            mergeGapMs = gap
        )
    }

    var useVadTimestamp by rememberSaveable(refreshKey) { mutableStateOf(initial.useVadTimestamp) }
    var dynamicPaddingEnabled by rememberSaveable(refreshKey) { mutableStateOf(initial.dynamicPaddingEnabled) }
    var forcedAlignment by rememberSaveable(refreshKey) { mutableStateOf(initial.forcedAlignment) }
    var mergeSegments by rememberSaveable(refreshKey) { mutableStateOf(initial.mergeSegments) }
    var smartMerge by rememberSaveable(refreshKey) { mutableStateOf(initial.smartMerge) }
    var filterLongMerge by rememberSaveable(refreshKey) { mutableStateOf(initial.filterLongMerge) }
    var punctuationSplit by rememberSaveable(refreshKey) { mutableStateOf(initial.punctuationSplit) }
    var splitPunctuation by rememberSaveable(refreshKey) { mutableStateOf(initial.splitPunctuation) }
    var maxCharacters by rememberSaveable(refreshKey) { mutableIntStateOf(initial.maxCharacters) }
    var mergeGapMs by rememberSaveable(refreshKey) { mutableIntStateOf(initial.mergeGapMs) }
    var mergeGapText by rememberSaveable(refreshKey) { mutableStateOf(initial.mergeGapMs.toString()) }

    val alignerEnabled = forcedAlignmentAvailable && forcedAlignment
    val mergeEnabled = alignerEnabled && mergeSegments
    val maxGapEnabled = mergeEnabled
    val smartOptionsEnabled = mergeEnabled && smartMerge
    val maxCharactersEnabled = smartOptionsEnabled && filterLongMerge

    AppToolScaffold(
        title = stringResource(R.string.qwen3_asr_settings_title),
        onBack = onNavigateBack,
        imePadding = true
    ) {
            AppSection {
                SectionHeader(stringResource(R.string.qwen3_asr_vad_title))
                SettingsSwitchRow(
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
                                settingsManager.isSpeechTokenTimestampEnabled(SettingsManager.ASR_MODEL_QWEN3_ASR)
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_14),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_15),
                    checked = dynamicPaddingEnabled,
                    onCheckedChange = { enabled ->
                        dynamicPaddingEnabled = enabled
                        settingsManager.setSpeechVadDynamicPaddingEnabled(
                            enabled,
                            SettingsManager.ASR_MODEL_QWEN3_ASR
                        )
                    },
                    modifier = Modifier.padding(top = 8.dp)
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
                AppSection {
                    SectionHeader(stringResource(R.string.activity_speech_to_subtitle_settings_text_55))
                    SettingsSwitchRow(
                        title = stringResource(R.string.qwen3_asr_forced_alignment_switch),
                        checked = forcedAlignment,
                        enabled = forcedAlignmentAvailable,
                        onCheckedChange = { enabled ->
                            forcedAlignment = enabled
                            settingsManager.setSpeechTokenTimestampEnabled(
                                enabled,
                                SettingsManager.ASR_MODEL_QWEN3_ASR
                            )
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

                    SettingsSwitchRow(
                        title = stringResource(R.string.activity_speech_to_subtitle_settings_text_50),
                        checked = mergeSegments,
                        enabled = alignerEnabled,
                        opacity = if (alignerEnabled) 1f else 0.5f,
                        onCheckedChange = { enabled ->
                            mergeSegments = enabled
                            settingsManager.setSpeechTokenTimestampMergeEnabled(
                                enabled,
                                SettingsManager.ASR_MODEL_QWEN3_ASR
                            )
                        },
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    SupportingText(
                        text = stringResource(R.string.qwen3_asr_merge_hint),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .alpha(if (alignerEnabled) 1f else 0.5f)
                    )

                    Text(
                        text = stringResource(R.string.activity_speech_to_subtitle_settings_text_52),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .alpha(if (maxGapEnabled) 1f else 0.5f)
                    )
                    SupportingText(
                        text = stringResource(R.string.activity_speech_to_subtitle_settings_text_53),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .alpha(if (maxGapEnabled) 1f else 0.5f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (maxGapEnabled) 1f else 0.5f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppSlider(
                            value = mergeGapMs.toFloat(),
                            onValueChange = { value ->
                                val gap = ((value.roundToInt().coerceIn(0, 5000) + 25) / 50) * 50
                                mergeGapMs = gap
                                mergeGapText = gap.toString()
                                settingsManager.setSpeechTokenTimestampMergeGapMs(
                                    gap,
                                    SettingsManager.ASR_MODEL_QWEN3_ASR
                                )
                            },
                            valueRange = 0f..5000f,
                            steps = 99,
                            enabled = maxGapEnabled,
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
                                    settingsManager.setSpeechTokenTimestampMergeGapMs(
                                        snapped,
                                        SettingsManager.ASR_MODEL_QWEN3_ASR
                                    )
                                }
                            },
                            enabled = maxGapEnabled,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            suffix = { Text(stringResource(R.string.unit_milliseconds), style = MaterialTheme.typography.bodySmall) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                textAlign = TextAlign.Center
                            ),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .width(104.dp)
                        )
                    }

                    SettingsSwitchRow(
                        title = stringResource(R.string.speech_merge_smart),
                        checked = smartMerge,
                        enabled = mergeEnabled,
                        opacity = if (mergeEnabled) 1f else 0.5f,
                        onCheckedChange = { enabled ->
                            smartMerge = enabled
                            settingsManager.setSpeechTokenTimestampSmartMergeEnabled(
                                enabled,
                                SettingsManager.ASR_MODEL_QWEN3_ASR
                            )
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    SupportingText(
                        text = stringResource(R.string.speech_merge_smart_hint),
                        modifier = Modifier.alpha(if (mergeEnabled) 1f else 0.5f)
                    )

                    SettingsSwitchRow(
                        title = stringResource(R.string.speech_merge_filter_long),
                        checked = filterLongMerge,
                        enabled = smartOptionsEnabled,
                        opacity = if (smartOptionsEnabled) 1f else 0.5f,
                        onCheckedChange = { enabled ->
                            filterLongMerge = enabled
                            settingsManager.setSpeechTokenTimestampLongSegmentFilterEnabled(
                                enabled,
                                SettingsManager.ASR_MODEL_QWEN3_ASR
                            )
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
                        AppSlider(
                            value = maxCharacters.toFloat(),
                            onValueChange = { value ->
                                val count = value.roundToInt().coerceIn(15, 50)
                                maxCharacters = count
                                settingsManager.setSpeechTokenTimestampMergeMaxCharacters(
                                    count,
                                    SettingsManager.ASR_MODEL_QWEN3_ASR
                                )
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
                    AsrPunctuationSplitSettings(
                        checked = punctuationSplit,
                        selectedPunctuation = splitPunctuation,
                        enabled = smartOptionsEnabled,
                        onCheckedChange = { enabled ->
                            punctuationSplit = enabled
                            settingsManager.setSpeechTokenTimestampPunctuationSplitEnabled(
                                enabled,
                                SettingsManager.ASR_MODEL_QWEN3_ASR
                            )
                        },
                        onPunctuationChange = { punctuation ->
                            splitPunctuation = punctuation
                            settingsManager.setSpeechTokenTimestampSplitPunctuation(
                                punctuation,
                                SettingsManager.ASR_MODEL_QWEN3_ASR
                            )
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
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

private data class Qwen3AsrSettingsValues(
    val useVadTimestamp: Boolean,
    val dynamicPaddingEnabled: Boolean,
    val forcedAlignment: Boolean,
    val mergeSegments: Boolean,
    val smartMerge: Boolean,
    val filterLongMerge: Boolean,
    val punctuationSplit: Boolean,
    val splitPunctuation: List<String>,
    val maxCharacters: Int,
    val mergeGapMs: Int
)

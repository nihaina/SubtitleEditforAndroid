package com.subtitleedit.feature.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import com.subtitleedit.ui.settings.AsrMergeGapTile
import com.subtitleedit.ui.settings.AsrMergeMaxCharactersTile
import com.subtitleedit.ui.settings.AsrPunctuationSplitSettings
import com.subtitleedit.ui.settings.ModelSettingsGroup
import com.subtitleedit.ui.settings.ModelSettingsScaffold
import com.subtitleedit.ui.settings.SettingsTileCard
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.util.SettingsManager
import kotlin.math.roundToInt

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
    val smartOptionsEnabled = mergeEnabled && smartMerge
    val maxCharactersEnabled = smartOptionsEnabled && filterLongMerge

    ModelSettingsScaffold(
        title = stringResource(R.string.qwen3_asr_settings_title),
        onBack = onNavigateBack,
    ) {
        ModelSettingsGroup(
            title = stringResource(R.string.qwen3_asr_vad_title),
            description = stringResource(R.string.qwen3_asr_vad_hint),
        ) {
            SettingsTileSwitchRow(
                title = stringResource(R.string.qwen3_asr_vad_switch),
                checked = useVadTimestamp,
                onCheckedChange = { enabled ->
                    useVadTimestamp = enabled
                    settingsManager.setAsrVadTimestampEnabled(SettingsManager.ASR_MODEL_QWEN3_ASR, enabled)
                    if (!enabled) {
                        forcedAlignment = forcedAlignmentAvailable &&
                            settingsManager.isSpeechTokenTimestampEnabled(SettingsManager.ASR_MODEL_QWEN3_ASR)
                    }
                },
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.activity_speech_to_subtitle_settings_text_14),
                description = stringResource(R.string.activity_speech_to_subtitle_settings_text_15),
                checked = dynamicPaddingEnabled,
                onCheckedChange = { enabled ->
                    dynamicPaddingEnabled = enabled
                    settingsManager.setSpeechVadDynamicPaddingEnabled(enabled, SettingsManager.ASR_MODEL_QWEN3_ASR)
                },
            )
            if (useVadTimestamp) {
                SettingsTileRow(title = stringResource(R.string.asr_vad_settings_entry), onClick = onOpenVadSettings)
            }
        }

        if (!useVadTimestamp) {
            ModelSettingsGroup(title = stringResource(R.string.activity_speech_to_subtitle_settings_text_55)) {
                SettingsTileSwitchRow(
                    title = stringResource(R.string.qwen3_asr_forced_alignment_switch),
                    description = stringResource(R.string.qwen3_asr_forced_alignment_hint),
                    checked = forcedAlignment,
                    enabled = forcedAlignmentAvailable,
                    onCheckedChange = { enabled ->
                        forcedAlignment = enabled
                        settingsManager.setSpeechTokenTimestampEnabled(enabled, SettingsManager.ASR_MODEL_QWEN3_ASR)
                    },
                )
                if (!forcedAlignmentAvailable) {
                    SettingsTileCard(grouped = true) {
                        Text(
                            text = stringResource(R.string.qwen3_asr_forced_alignment_missing),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                SettingsTileSwitchRow(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_50),
                    description = stringResource(R.string.qwen3_asr_merge_hint),
                    checked = mergeSegments,
                    enabled = alignerEnabled,
                    onCheckedChange = { enabled ->
                        mergeSegments = enabled
                        settingsManager.setSpeechTokenTimestampMergeEnabled(enabled, SettingsManager.ASR_MODEL_QWEN3_ASR)
                    },
                )
                AsrMergeGapTile(
                    value = mergeGapMs,
                    valueText = mergeGapText,
                    enabled = mergeEnabled,
                    onValueChange = { value ->
                        val gap = ((value.roundToInt().coerceIn(0, 5000) + 25) / 50) * 50
                        mergeGapMs = gap
                        mergeGapText = gap.toString()
                        settingsManager.setSpeechTokenTimestampMergeGapMs(gap, SettingsManager.ASR_MODEL_QWEN3_ASR)
                    },
                    onTextChange = { input ->
                        mergeGapText = input
                        input.toIntOrNull()?.let { value ->
                            val snapped = ((value.coerceIn(0, 5000) + 25) / 50) * 50
                            mergeGapMs = snapped
                            if (input != snapped.toString()) {
                                mergeGapText = snapped.toString()
                            }
                            settingsManager.setSpeechTokenTimestampMergeGapMs(snapped, SettingsManager.ASR_MODEL_QWEN3_ASR)
                        }
                    },
                )
                SettingsTileSwitchRow(
                    title = stringResource(R.string.speech_merge_smart),
                    description = stringResource(R.string.speech_merge_smart_hint),
                    checked = smartMerge,
                    enabled = mergeEnabled,
                    onCheckedChange = { enabled ->
                        smartMerge = enabled
                        settingsManager.setSpeechTokenTimestampSmartMergeEnabled(enabled, SettingsManager.ASR_MODEL_QWEN3_ASR)
                    },
                )
                SettingsTileSwitchRow(
                    title = stringResource(R.string.speech_merge_filter_long),
                    checked = filterLongMerge,
                    enabled = smartOptionsEnabled,
                    onCheckedChange = { enabled ->
                        filterLongMerge = enabled
                        settingsManager.setSpeechTokenTimestampLongSegmentFilterEnabled(enabled, SettingsManager.ASR_MODEL_QWEN3_ASR)
                    },
                )
                AsrMergeMaxCharactersTile(
                    value = maxCharacters,
                    enabled = maxCharactersEnabled,
                    onValueChange = { value ->
                        val count = value.roundToInt().coerceIn(15, 50)
                        maxCharacters = count
                        settingsManager.setSpeechTokenTimestampMergeMaxCharacters(count, SettingsManager.ASR_MODEL_QWEN3_ASR)
                    },
                )
                AsrPunctuationSplitSettings(
                    checked = punctuationSplit,
                    selectedPunctuation = splitPunctuation,
                    enabled = smartOptionsEnabled,
                    onCheckedChange = { enabled ->
                        punctuationSplit = enabled
                        settingsManager.setSpeechTokenTimestampPunctuationSplitEnabled(enabled, SettingsManager.ASR_MODEL_QWEN3_ASR)
                    },
                    onPunctuationChange = { punctuation ->
                        splitPunctuation = punctuation
                        settingsManager.setSpeechTokenTimestampSplitPunctuation(punctuation, SettingsManager.ASR_MODEL_QWEN3_ASR)
                    },
                )
            }
        }
    }
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

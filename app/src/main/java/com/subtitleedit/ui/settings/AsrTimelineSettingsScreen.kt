package com.subtitleedit.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import com.subtitleedit.util.AsrPunctuationOptions

data class AsrTimelineSettingsState(
    val useVadTimestamp: Boolean = false,
    val dynamicPaddingEnabled: Boolean = true,
    val fixedSegmentSeconds: Int = 10,
    val fixedSegmentSecondsText: String = "10",
    val fixedVadSegmentation: Boolean = true,
    val tokenTimestampModelAvailable: Boolean = false,
    val tokenTimestampEnabled: Boolean = false,
    val tokenTimestampMerge: Boolean = true,
    val smartMerge: Boolean = true,
    val filterLongMerge: Boolean = true,
    val punctuationSplit: Boolean = true,
    val splitPunctuation: List<String> = AsrPunctuationOptions.defaults.toList(),
    val mergeMaxCharacters: Int = 25,
    val mergeGapMs: Int = 500,
    val mergeGapText: String = "500"
)

@Composable
fun AsrTimelineSettingsScreen(
    title: String,
    state: AsrTimelineSettingsState,
    onBack: () -> Unit,
    onOpenVadSettings: () -> Unit,
    onUseVadTimestampChanged: (Boolean) -> Unit,
    onDynamicPaddingEnabledChanged: (Boolean) -> Unit,
    onFixedSegmentSecondsChanged: (Float) -> Unit,
    onFixedSegmentSecondsTextChanged: (String) -> Unit,
    onFixedVadSegmentationChanged: (Boolean) -> Unit,
    onTokenTimestampEnabledChanged: (Boolean) -> Unit,
    onTokenTimestampMergeChanged: (Boolean) -> Unit,
    onSmartMergeChanged: (Boolean) -> Unit,
    onFilterLongMergeChanged: (Boolean) -> Unit,
    onPunctuationSplitChanged: (Boolean) -> Unit,
    onSplitPunctuationChanged: (List<String>) -> Unit,
    onMergeMaxCharactersChanged: (Float) -> Unit,
    onMergeGapChanged: (Float) -> Unit,
    onMergeGapTextChanged: (String) -> Unit
) {
    ModelSettingsScaffold(title = title, onBack = onBack) {
        ModelSettingsGroup(
            title = stringResource(R.string.asr_timeline_vad_title),
            description = stringResource(R.string.asr_timeline_vad_hint),
        ) {
            SettingsTileSwitchRow(
                title = stringResource(R.string.qwen3_asr_vad_switch),
                checked = state.useVadTimestamp,
                onCheckedChange = onUseVadTimestampChanged,
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.activity_speech_to_subtitle_settings_text_14),
                description = stringResource(R.string.activity_speech_to_subtitle_settings_text_15),
                checked = state.dynamicPaddingEnabled,
                onCheckedChange = onDynamicPaddingEnabledChanged,
            )
            if (state.useVadTimestamp) {
                SettingsTileRow(title = stringResource(R.string.asr_vad_settings_entry), onClick = onOpenVadSettings)
            }
        }

        if (!state.useVadTimestamp) {
            val mergeEnabled = state.tokenTimestampEnabled && state.tokenTimestampMerge
            val smartOptionsEnabled = mergeEnabled && state.smartMerge
            ModelSettingsGroup(
                title = stringResource(R.string.activity_speech_to_subtitle_settings_text_41),
                description = stringResource(R.string.activity_speech_to_subtitle_settings_text_55),
            ) {
                SettingsTileSwitchRow(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_42),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_43),
                    checked = state.tokenTimestampEnabled,
                    enabled = state.tokenTimestampModelAvailable,
                    onCheckedChange = onTokenTimestampEnabledChanged,
                )
                if (!state.tokenTimestampModelAvailable) {
                    SettingsTileCard(grouped = true) {
                        Text(
                            text = stringResource(R.string.activity_speech_to_subtitle_settings_text_47),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                SettingsTileSwitchRow(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_50),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_51),
                    checked = state.tokenTimestampMerge,
                    enabled = state.tokenTimestampEnabled,
                    onCheckedChange = onTokenTimestampMergeChanged,
                )
                AsrMergeGapTile(
                    value = state.mergeGapMs,
                    valueText = state.mergeGapText,
                    enabled = mergeEnabled,
                    onValueChange = onMergeGapChanged,
                    onTextChange = onMergeGapTextChanged,
                )
                SettingsTileSwitchRow(
                    title = stringResource(R.string.speech_merge_smart),
                    description = stringResource(R.string.speech_merge_smart_hint),
                    checked = state.smartMerge,
                    enabled = mergeEnabled,
                    onCheckedChange = onSmartMergeChanged,
                )
                SettingsTileSwitchRow(
                    title = stringResource(R.string.speech_merge_filter_long),
                    checked = state.filterLongMerge,
                    enabled = smartOptionsEnabled,
                    onCheckedChange = onFilterLongMergeChanged,
                )
                AsrMergeMaxCharactersTile(
                    value = state.mergeMaxCharacters,
                    enabled = smartOptionsEnabled && state.filterLongMerge,
                    onValueChange = onMergeMaxCharactersChanged,
                )
                AsrPunctuationSplitSettings(
                    checked = state.punctuationSplit,
                    selectedPunctuation = state.splitPunctuation,
                    enabled = smartOptionsEnabled,
                    onCheckedChange = onPunctuationSplitChanged,
                    onPunctuationChange = onSplitPunctuationChanged,
                )
                SettingsTileSwitchRow(
                    title = stringResource(R.string.asr_timeline_fixed_vad_title),
                    description = stringResource(R.string.asr_timeline_fixed_vad_hint),
                    checked = state.fixedVadSegmentation,
                    onCheckedChange = onFixedVadSegmentationChanged,
                )
                ModelSettingsSliderTile(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_02),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_03),
                    value = state.fixedSegmentSeconds.toFloat(),
                    onValueChange = onFixedSegmentSecondsChanged,
                    valueRange = 5f..120f,
                    steps = 22,
                    input = {
                        AsrNumberInput(
                            value = state.fixedSegmentSecondsText,
                            onValueChange = onFixedSegmentSecondsTextChanged,
                            unit = stringResource(R.string.unit_seconds),
                        )
                    },
                )
            }
        }
    }
}

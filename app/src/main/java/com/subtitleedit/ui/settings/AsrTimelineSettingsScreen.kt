package com.subtitleedit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppSlider
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SectionHeader
import com.subtitleedit.ui.components.SettingsSwitchRow

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
    val mergeMaxCharacters: Int = 25,
    val mergeGapMs: Int = 150,
    val mergeGapText: String = "150"
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
    onMergeMaxCharactersChanged: (Float) -> Unit,
    onMergeGapChanged: (Float) -> Unit,
    onMergeGapTextChanged: (String) -> Unit
) {
    AppToolScaffold(
        title = title,
        onBack = onBack,
        imePadding = true
    ) {
            AppSection {
                SectionHeader(stringResource(R.string.asr_timeline_vad_title))
                SettingsSwitchRow(
                    title = stringResource(R.string.qwen3_asr_vad_switch),
                    checked = state.useVadTimestamp,
                    onCheckedChange = onUseVadTimestampChanged
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_14),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_15),
                    checked = state.dynamicPaddingEnabled,
                    onCheckedChange = onDynamicPaddingEnabledChanged,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = stringResource(R.string.asr_timeline_vad_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                if (state.useVadTimestamp) {
                    OutlinedButton(
                        onClick = onOpenVadSettings,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.asr_vad_settings_entry))
                    }
                }
            }

            if (!state.useVadTimestamp) {
                AppSection {
                    SectionHeader(stringResource(R.string.activity_speech_to_subtitle_settings_text_41))
                    SectionHeader(
                        text = stringResource(R.string.activity_speech_to_subtitle_settings_text_55),
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.activity_speech_to_subtitle_settings_text_42),
                        description = stringResource(R.string.activity_speech_to_subtitle_settings_text_43),
                        checked = state.tokenTimestampEnabled,
                        enabled = state.tokenTimestampModelAvailable,
                        onCheckedChange = onTokenTimestampEnabledChanged,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (!state.tokenTimestampModelAvailable) {
                        Text(
                            text = stringResource(R.string.activity_speech_to_subtitle_settings_text_47),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                    }
                    SettingsSwitchRow(
                        title = stringResource(R.string.activity_speech_to_subtitle_settings_text_50),
                        description = stringResource(R.string.activity_speech_to_subtitle_settings_text_51),
                        checked = state.tokenTimestampMerge,
                        enabled = state.tokenTimestampEnabled,
                        onCheckedChange = onTokenTimestampMergeChanged,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    val mergeEnabled = state.tokenTimestampEnabled && state.tokenTimestampMerge
                    Column(modifier = Modifier.alpha(if (mergeEnabled) 1f else 0.5f)) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.speech_merge_smart),
                            description = stringResource(R.string.speech_merge_smart_hint),
                            checked = state.smartMerge,
                            enabled = mergeEnabled,
                            onCheckedChange = onSmartMergeChanged
                        )
                        Text(
                            text = stringResource(R.string.activity_speech_to_subtitle_settings_text_52),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (mergeEnabled && !state.smartMerge) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            },
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp)
                        )
                        Text(
                            text = stringResource(R.string.activity_speech_to_subtitle_settings_text_53),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (mergeEnabled && !state.smartMerge) 1f else 0.5f
                            ),
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (mergeEnabled && !state.smartMerge) 1f else 0.5f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppSlider(
                                value = state.mergeGapMs.toFloat(),
                                onValueChange = onMergeGapChanged,
                                valueRange = 0f..5000f,
                                steps = 99,
                                enabled = mergeEnabled && !state.smartMerge,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.mergeGapText,
                                onValueChange = onMergeGapTextChanged,
                                enabled = mergeEnabled && !state.smartMerge,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                suffix = { Text(stringResource(R.string.unit_milliseconds)) },
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .width(104.dp)
                            )
                        }
                        SettingsSwitchRow(
                            title = stringResource(R.string.speech_merge_filter_long),
                            checked = state.filterLongMerge,
                            enabled = mergeEnabled,
                            onCheckedChange = onFilterLongMergeChanged,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (mergeEnabled && state.filterLongMerge) 1f else 0.5f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.speech_merge_max_characters),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(72.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Clip
                            )
                            AppSlider(
                                value = state.mergeMaxCharacters.toFloat(),
                                onValueChange = onMergeMaxCharactersChanged,
                                valueRange = 15f..50f,
                                steps = 34,
                                enabled = mergeEnabled && state.filterLongMerge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = state.mergeMaxCharacters.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(40.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    SettingsSwitchRow(
                    title = stringResource(R.string.asr_timeline_fixed_vad_title),
                    description = stringResource(R.string.asr_timeline_fixed_vad_hint),
                    checked = state.fixedVadSegmentation,
                    enabled = !state.useVadTimestamp,
                    onCheckedChange = onFixedVadSegmentationChanged,
                    modifier = Modifier.padding(top = 12.dp),
                    dimDescriptionWhenDisabled = false
                    )
                    Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_settings_text_02),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp)
                    )
                    Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_settings_text_03),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                    Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppSlider(
                        value = state.fixedSegmentSeconds.toFloat(),
                        onValueChange = onFixedSegmentSecondsChanged,
                        valueRange = 5f..120f,
                        steps = 22,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.fixedSegmentSecondsText,
                        onValueChange = onFixedSegmentSecondsTextChanged,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        suffix = { Text(stringResource(R.string.unit_seconds)) },
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .width(96.dp)
                    )
                    }
                }
            }
        }
}

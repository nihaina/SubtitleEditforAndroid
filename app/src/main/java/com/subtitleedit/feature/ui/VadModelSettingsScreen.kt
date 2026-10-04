package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppSlider
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SectionHeader
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.util.SettingsManager
import kotlin.math.roundToInt

data class VadSettingsState(
    val mergeEnabled: Boolean = false,
    val mergeGapMs: Int = 200,
    val threshold: Float = 0.3f,
    val minSilence: Float = 0.3f,
    val minSpeech: Float = 0.25f,
    val maxSpeech: Float = 10f,
    val secondaryMode: String = SettingsManager.SECONDARY_VAD_MODE_NONE,
    val secondaryMergeEnabled: Boolean = false,
    val secondaryMergeGapMs: Int = 200,
    val secondaryThreshold: Float = 0.2f,
    val secondaryMinSilence: Float = 0.1f,
    val secondaryMinSpeech: Float = 0.1f,
    val secondaryMaxSpeech: Float = 5f
) {
    companion object {
        fun read(settings: SettingsManager) = VadSettingsState(
            mergeEnabled = settings.isSpeechVadMergeEnabled(),
            mergeGapMs = settings.getSpeechVadMergeGapMs(),
            threshold = settings.getVadThreshold(),
            minSilence = settings.getVadMinSilenceDuration(),
            minSpeech = settings.getVadMinSpeechDuration(),
            maxSpeech = settings.getVadMaxSpeechDuration(),
            secondaryMode = settings.getSpeechSecondaryVadMode(),
            secondaryMergeEnabled = settings.isSpeechSecondaryVadMergeEnabled(),
            secondaryMergeGapMs = settings.getSpeechSecondaryVadMergeGapMs(),
            secondaryThreshold = settings.getSpeechSecondaryVadThreshold(),
            secondaryMinSilence = settings.getSpeechSecondaryVadMinSilenceDuration(),
            secondaryMinSpeech = settings.getSpeechSecondaryVadMinSpeechDuration(),
            secondaryMaxSpeech = settings.getSpeechSecondaryVadMaxSpeechDuration()
        )
    }
}

@Composable
fun VadModelSettingsScreen(
    state: VadSettingsState,
    onStateChange: (VadSettingsState) -> Unit,
    onBack: () -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.vad_settings_title),
        onBack = onBack,
        imePadding = true
    ) {
            AppSection {
                SectionHeader(stringResource(R.string.activity_speech_to_subtitle_settings_text_01))
                SettingsSwitchRow(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_35),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_40),
                    checked = state.mergeEnabled,
                    onCheckedChange = { onStateChange(state.copy(mergeEnabled = it)) },
                    modifier = Modifier.padding(top = 16.dp)
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_37,
                    description = R.string.activity_speech_to_subtitle_settings_text_38,
                    value = state.mergeGapMs.toFloat(),
                    valueRange = 0f..5000f,
                    steps = 99,
                    unit = ParameterUnit.MILLISECONDS,
                    enabled = state.mergeEnabled,
                    initialText = R.string.activity_speech_to_subtitle_settings_text_39,
                    onValueChange = { value ->
                        onStateChange(
                            state.copy(mergeGapMs = (value / 50f).roundToInt() * 50)
                        )
                    }
                )
                SectionHeader(
                    stringResource(R.string.activity_speech_to_subtitle_settings_text_56),
                    modifier = Modifier.padding(top = 16.dp)
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_57,
                    description = R.string.activity_speech_to_subtitle_settings_text_58,
                    value = state.threshold,
                    valueRange = 0.01f..0.9f,
                    steps = 88,
                    initialText = R.string.activity_model_settings_text_21,
                    onValueChange = { onStateChange(state.copy(threshold = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_59,
                    description = R.string.activity_speech_to_subtitle_settings_text_60,
                    value = state.minSilence,
                    valueRange = 0.01f..2f,
                    steps = 198,
                    unit = ParameterUnit.SECONDS,
                    initialText = R.string.activity_model_settings_text_21,
                    onValueChange = { onStateChange(state.copy(minSilence = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_61,
                    description = R.string.activity_speech_to_subtitle_settings_text_62,
                    value = state.minSpeech,
                    valueRange = 0.01f..1f,
                    steps = 98,
                    unit = ParameterUnit.SECONDS,
                    initialText = R.string.activity_model_settings_text_26,
                    onValueChange = { onStateChange(state.copy(minSpeech = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_63,
                    description = R.string.activity_speech_to_subtitle_settings_text_64,
                    value = state.maxSpeech,
                    valueRange = 1f..60f,
                    steps = 58,
                    unit = ParameterUnit.SECONDS,
                    initialText = R.string.activity_model_settings_text_29,
                    onValueChange = { onStateChange(state.copy(maxSpeech = it)) }
                )
            }

            AppSection {
                SectionHeader(stringResource(R.string.activity_speech_to_subtitle_settings_text_16))
                Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_settings_text_17),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.activity_speech_to_subtitle_settings_text_18),
                        checked = state.secondaryMode == SettingsManager.SECONDARY_VAD_MODE_UNCOVERED,
                        onCheckedChange = {
                            onStateChange(state.copy(
                                secondaryMode = if (it) SettingsManager.SECONDARY_VAD_MODE_UNCOVERED
                                else SettingsManager.SECONDARY_VAD_MODE_NONE
                            ))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.activity_speech_to_subtitle_settings_text_20),
                        checked = state.secondaryMode == SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS,
                        onCheckedChange = {
                            onStateChange(state.copy(
                                secondaryMode = if (it) SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS
                                else SettingsManager.SECONDARY_VAD_MODE_NONE
                            ))
                        },
                        modifier = Modifier.padding(start = 8.dp).weight(1f)
                    )
                }
                Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_settings_text_19),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_settings_text_21),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_35),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_36),
                    checked = state.secondaryMergeEnabled,
                    onCheckedChange = {
                        onStateChange(state.copy(secondaryMergeEnabled = it))
                    },
                    modifier = Modifier.padding(top = 16.dp)
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_37,
                    description = R.string.activity_speech_to_subtitle_settings_text_38,
                    value = state.secondaryMergeGapMs.toFloat(),
                    valueRange = 0f..5000f,
                    steps = 99,
                    unit = ParameterUnit.MILLISECONDS,
                    enabled = state.secondaryMergeEnabled,
                    initialText = R.string.activity_speech_to_subtitle_settings_text_39,
                    onValueChange = { value ->
                        onStateChange(
                            state.copy(secondaryMergeGapMs = (value / 50f).roundToInt() * 50)
                        )
                    }
                )
                SectionHeader(
                    stringResource(R.string.activity_speech_to_subtitle_settings_text_22),
                    modifier = Modifier.padding(top = 16.dp)
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_23,
                    description = R.string.activity_speech_to_subtitle_settings_text_24,
                    value = state.secondaryThreshold,
                    valueRange = 0.01f..0.9f,
                    steps = 88,
                    initialText = R.string.activity_speech_to_subtitle_settings_text_25,
                    onValueChange = { onStateChange(state.copy(secondaryThreshold = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_26,
                    description = R.string.activity_speech_to_subtitle_settings_text_27,
                    value = state.secondaryMinSilence,
                    valueRange = 0.01f..2f,
                    steps = 198,
                    unit = ParameterUnit.SECONDS,
                    initialText = R.string.activity_speech_to_subtitle_settings_text_28,
                    onValueChange = { onStateChange(state.copy(secondaryMinSilence = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_29,
                    description = R.string.activity_speech_to_subtitle_settings_text_30,
                    value = state.secondaryMinSpeech,
                    valueRange = 0.01f..1f,
                    steps = 98,
                    unit = ParameterUnit.SECONDS,
                    initialText = R.string.activity_speech_to_subtitle_settings_text_31,
                    onValueChange = { onStateChange(state.copy(secondaryMinSpeech = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_32,
                    description = R.string.activity_speech_to_subtitle_settings_text_33,
                    value = state.secondaryMaxSpeech,
                    valueRange = 1f..60f,
                    steps = 58,
                    unit = ParameterUnit.SECONDS,
                    initialText = R.string.activity_speech_to_subtitle_settings_text_34,
                    onValueChange = { onStateChange(state.copy(secondaryMaxSpeech = it)) }
                )
            }
        }
}

@Composable
private fun ParameterSlider(
    title: Int,
    description: Int,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: ParameterUnit = ParameterUnit.NONE,
    enabled: Boolean = true,
    initialText: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit
) {
    val defaultText = stringResource(initialText)
    var valueText by remember { mutableStateOf(formatSliderValue(value, unit, defaultText)) }
    var editingText by remember { mutableStateOf(false) }
    LaunchedEffect(value, editingText) {
        if (!editingText) valueText = formatSliderValue(value, unit, defaultText)
    }
    val fieldWidth: Dp = when (unit) {
        ParameterUnit.MILLISECONDS -> 104.dp
        ParameterUnit.SECONDS -> 90.dp
        ParameterUnit.NONE -> 80.dp
    }
    Column(modifier = modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSlider(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    // A slider interaction takes precedence over any in-progress text edit.
                    valueText = formatSliderValue(it, unit, defaultText)
                },
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = valueText,
                onValueChange = { input ->
                    valueText = input
                    if (!input.isBlank() && !input.endsWith('.')) input.toFloatOrNull()?.let { parsed ->
                        val normalized = when (unit) {
                            ParameterUnit.MILLISECONDS -> parsed.roundToInt().coerceIn(0, 5000).toFloat()
                            else -> {
                                val step = (valueRange.endInclusive - valueRange.start) / (steps + 1)
                                (valueRange.start +
                                    ((parsed.coerceIn(valueRange.start, valueRange.endInclusive) - valueRange.start) /
                                        step).roundToInt() * step)
                                    .coerceIn(valueRange.start, valueRange.endInclusive)
                            }
                        }
                        onValueChange(normalized)
                        valueText = formatSliderValue(normalized, unit, defaultText)
                    }
                },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .width(fieldWidth)
                    .onFocusChanged { editingText = it.isFocused },
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (unit == ParameterUnit.MILLISECONDS) KeyboardType.Number else KeyboardType.Decimal
                ),
                suffix = { unit.labelRes?.let { Text(stringResource(it)) } }
            )
        }
    }
}

private enum class ParameterUnit(val labelRes: Int?) {
    NONE(null),
    MILLISECONDS(R.string.unit_milliseconds),
    SECONDS(R.string.unit_seconds)
}

private fun formatSliderValue(value: Float, unit: ParameterUnit, fallback: String): String = when {
    unit == ParameterUnit.MILLISECONDS -> value.roundToInt().toString()
    unit == ParameterUnit.SECONDS && value >= 1f -> value.roundToInt().toString()
    unit == ParameterUnit.SECONDS -> String.format(java.util.Locale.US, "%.2f", value)
    // Threshold sliders have no unit, but their displayed value still needs to
    // follow the current slider position rather than the initial hint text.
    else -> String.format(java.util.Locale.US, "%.2f", value)
}

package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.util.SettingsManager
import kotlin.math.roundToInt

data class VadSettingsState(
    val mergeEnabled: Boolean,
    val mergeGapMs: Int,
    val threshold: Float,
    val minSilence: Float,
    val minSpeech: Float,
    val maxSpeech: Float,
    val secondaryMode: String,
    val secondaryMergeEnabled: Boolean,
    val secondaryMergeGapMs: Int,
    val secondaryThreshold: Float,
    val secondaryMinSilence: Float,
    val secondaryMinSpeech: Float,
    val secondaryMaxSpeech: Float
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VadModelSettingsScreen(
    state: VadSettingsState,
    onStateChange: (VadSettingsState) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VAD 配置") },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VadSettingsCard {
                SectionHeading(stringResource(R.string.activity_speech_to_subtitle_settings_text_01))
                SettingsSwitch(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_35),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_40),
                    checked = state.mergeEnabled,
                    onCheckedChange = { onStateChange(state.copy(mergeEnabled = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_37,
                    description = R.string.activity_speech_to_subtitle_settings_text_38,
                    value = state.mergeGapMs.toFloat(),
                    valueRange = 0f..5000f,
                    steps = 99,
                    suffix = "毫秒",
                    decimals = 0,
                    enabled = state.mergeEnabled,
                    onValueChange = { value ->
                        onStateChange(state.copy(mergeGapMs = (value / 50f).roundToInt() * 50))
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionHeading(stringResource(R.string.activity_speech_to_subtitle_settings_text_56))
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_57,
                    description = R.string.activity_speech_to_subtitle_settings_text_58,
                    value = state.threshold,
                    valueRange = 0.01f..0.9f,
                    steps = 88,
                    decimals = 2,
                    onValueChange = { onStateChange(state.copy(threshold = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_59,
                    description = R.string.activity_speech_to_subtitle_settings_text_60,
                    value = state.minSilence,
                    valueRange = 0.01f..2f,
                    steps = 198,
                    suffix = "秒",
                    decimals = 2,
                    onValueChange = { onStateChange(state.copy(minSilence = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_61,
                    description = R.string.activity_speech_to_subtitle_settings_text_62,
                    value = state.minSpeech,
                    valueRange = 0.01f..1f,
                    steps = 98,
                    suffix = "秒",
                    decimals = 2,
                    onValueChange = { onStateChange(state.copy(minSpeech = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_63,
                    description = R.string.activity_speech_to_subtitle_settings_text_64,
                    value = state.maxSpeech,
                    valueRange = 1f..60f,
                    steps = 58,
                    suffix = "秒",
                    decimals = 1,
                    onValueChange = { onStateChange(state.copy(maxSpeech = it)) }
                )
            }

            VadSettingsCard {
                SectionHeading(stringResource(R.string.activity_speech_to_subtitle_settings_text_16))
                Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_settings_text_17),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsSwitch(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_18),
                    checked = state.secondaryMode == SettingsManager.SECONDARY_VAD_MODE_UNCOVERED,
                    onCheckedChange = {
                        val mode = if (it) SettingsManager.SECONDARY_VAD_MODE_UNCOVERED
                        else SettingsManager.SECONDARY_VAD_MODE_NONE
                        onStateChange(state.copy(secondaryMode = mode))
                    }
                )
                SettingsSwitch(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_20),
                    checked = state.secondaryMode == SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS,
                    onCheckedChange = {
                        val mode = if (it) SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS
                        else SettingsManager.SECONDARY_VAD_MODE_NONE
                        onStateChange(state.copy(secondaryMode = mode))
                    }
                )
                Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_settings_text_19) + "\n" +
                        stringResource(R.string.activity_speech_to_subtitle_settings_text_21),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsSwitch(
                    title = stringResource(R.string.activity_speech_to_subtitle_settings_text_35),
                    description = stringResource(R.string.activity_speech_to_subtitle_settings_text_36),
                    checked = state.secondaryMergeEnabled,
                    onCheckedChange = {
                        onStateChange(state.copy(secondaryMergeEnabled = it))
                    }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_37,
                    description = R.string.activity_speech_to_subtitle_settings_text_38,
                    value = state.secondaryMergeGapMs.toFloat(),
                    valueRange = 0f..5000f,
                    steps = 99,
                    suffix = "毫秒",
                    decimals = 0,
                    enabled = state.secondaryMergeEnabled,
                    onValueChange = { value ->
                        onStateChange(state.copy(secondaryMergeGapMs = (value / 50f).roundToInt() * 50))
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionHeading(stringResource(R.string.activity_speech_to_subtitle_settings_text_22))
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_23,
                    description = R.string.activity_speech_to_subtitle_settings_text_24,
                    value = state.secondaryThreshold,
                    valueRange = 0.01f..0.9f,
                    steps = 88,
                    decimals = 2,
                    onValueChange = { onStateChange(state.copy(secondaryThreshold = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_26,
                    description = R.string.activity_speech_to_subtitle_settings_text_27,
                    value = state.secondaryMinSilence,
                    valueRange = 0.01f..2f,
                    steps = 198,
                    suffix = "秒",
                    decimals = 2,
                    onValueChange = { onStateChange(state.copy(secondaryMinSilence = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_29,
                    description = R.string.activity_speech_to_subtitle_settings_text_30,
                    value = state.secondaryMinSpeech,
                    valueRange = 0.01f..1f,
                    steps = 98,
                    suffix = "秒",
                    decimals = 2,
                    onValueChange = { onStateChange(state.copy(secondaryMinSpeech = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_32,
                    description = R.string.activity_speech_to_subtitle_settings_text_33,
                    value = state.secondaryMaxSpeech,
                    valueRange = 1f..60f,
                    steps = 58,
                    suffix = "秒",
                    decimals = 1,
                    onValueChange = { onStateChange(state.copy(secondaryMaxSpeech = it)) }
                )
            }
        }
    }
}

@Composable
private fun VadSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingsSwitch(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ParameterSlider(
    title: Int,
    description: Int,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    suffix: String = "",
    decimals: Int,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    var valueText by remember(value, decimals) { mutableStateOf(formatValue(value, decimals)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = { changed ->
                    if (enabled) {
                        val snapped = snapValue(changed, valueRange, decimals, steps)
                        valueText = formatValue(snapped, decimals)
                        onValueChange(snapped)
                    }
                },
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = valueText,
                onValueChange = { text ->
                    valueText = text
                    text.toFloatOrNull()?.takeIf { it in valueRange }?.let(onValueChange)
                },
                enabled = enabled,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .width(if (suffix.isEmpty()) 88.dp else 112.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                suffix = { if (suffix.isNotEmpty()) Text(suffix) }
            )
        }
    }
}

private fun snapValue(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int,
    steps: Int
): Float {
    val bounded = value.coerceIn(range)
    if (decimals == 0) return bounded.roundToInt().toFloat()
    val step = (range.endInclusive - range.start) / (steps + 1)
    return (range.start + ((bounded - range.start) / step).roundToInt() * step)
        .coerceIn(range)
}

private fun formatValue(value: Float, decimals: Int): String = when (decimals) {
    0 -> value.roundToInt().toString()
    1 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}

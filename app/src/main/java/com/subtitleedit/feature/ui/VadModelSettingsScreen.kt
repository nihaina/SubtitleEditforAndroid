package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
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
                modifier = Modifier.height(56.dp),
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // vad_settings_content.xml gives the first card a 16dp top margin
            // in addition to the 16dp parent padding.
            Spacer(Modifier.height(16.dp))
            VadSettingsCard {
                SectionHeading(stringResource(R.string.activity_speech_to_subtitle_settings_text_01))
                SettingsSwitch(
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
                    suffix = "毫秒",
                    enabled = state.mergeEnabled,
                    initialText = R.string.activity_speech_to_subtitle_settings_text_39,
                    onValueChange = { value ->
                        onStateChange(
                            state.copy(mergeGapMs = (value / 50f).roundToInt() * 50)
                        )
                    }
                )
                SectionHeading(
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
                    suffix = "秒",
                    initialText = R.string.activity_model_settings_text_21,
                    onValueChange = { onStateChange(state.copy(minSilence = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_61,
                    description = R.string.activity_speech_to_subtitle_settings_text_62,
                    value = state.minSpeech,
                    valueRange = 0.01f..1f,
                    steps = 98,
                    suffix = "秒",
                    initialText = R.string.activity_model_settings_text_26,
                    onValueChange = { onStateChange(state.copy(minSpeech = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_63,
                    description = R.string.activity_speech_to_subtitle_settings_text_64,
                    value = state.maxSpeech,
                    valueRange = 1f..60f,
                    steps = 58,
                    suffix = "秒",
                    initialText = R.string.activity_model_settings_text_29,
                    onValueChange = { onStateChange(state.copy(maxSpeech = it)) }
                )
            }

            VadSettingsCard {
                SectionHeading(stringResource(R.string.activity_speech_to_subtitle_settings_text_16))
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
                    SettingsSwitch(
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
                    SettingsSwitch(
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
                SettingsSwitch(
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
                    suffix = "毫秒",
                    enabled = state.secondaryMergeEnabled,
                    initialText = R.string.activity_speech_to_subtitle_settings_text_39,
                    onValueChange = { value ->
                        onStateChange(
                            state.copy(secondaryMergeGapMs = (value / 50f).roundToInt() * 50)
                        )
                    }
                )
                SectionHeading(
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
                    suffix = "秒",
                    initialText = R.string.activity_speech_to_subtitle_settings_text_28,
                    onValueChange = { onStateChange(state.copy(secondaryMinSilence = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_29,
                    description = R.string.activity_speech_to_subtitle_settings_text_30,
                    value = state.secondaryMinSpeech,
                    valueRange = 0.01f..1f,
                    steps = 98,
                    suffix = "秒",
                    initialText = R.string.activity_speech_to_subtitle_settings_text_31,
                    onValueChange = { onStateChange(state.copy(secondaryMinSpeech = it)) }
                )
                ParameterSlider(
                    title = R.string.activity_speech_to_subtitle_settings_text_32,
                    description = R.string.activity_speech_to_subtitle_settings_text_33,
                    value = state.secondaryMaxSpeech,
                    valueRange = 1f..60f,
                    steps = 58,
                    suffix = "秒",
                    initialText = R.string.activity_speech_to_subtitle_settings_text_34,
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
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingsSwitch(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = null)
        }
        description?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
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
    suffix: String = "",
    enabled: Boolean = true,
    initialText: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit
) {
    val defaultText = stringResource(initialText)
    var valueText by remember { mutableStateOf(formatSliderValue(value, suffix, defaultText)) }
    var editingText by remember { mutableStateOf(false) }
    LaunchedEffect(value, editingText) {
        if (!editingText) valueText = formatSliderValue(value, suffix, defaultText)
    }
    val fieldWidth: Dp = when {
        suffix == "毫秒" -> 104.dp
        suffix.isNotEmpty() -> 90.dp
        else -> 80.dp
    }
    Column(modifier = modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(stringResource(title), fontSize = 14.sp)
        Text(
            text = stringResource(description),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    if (!editingText) valueText = formatSliderValue(it, suffix, defaultText)
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
                        val normalized = when (suffix) {
                            "毫秒" -> parsed.roundToInt().coerceIn(0, 5000).toFloat()
                            else -> {
                                val step = (valueRange.endInclusive - valueRange.start) / (steps + 1)
                                (valueRange.start +
                                    ((parsed.coerceIn(valueRange.start, valueRange.endInclusive) - valueRange.start) /
                                        step).roundToInt() * step)
                                    .coerceIn(valueRange.start, valueRange.endInclusive)
                            }
                        }
                        onValueChange(normalized)
                        valueText = formatSliderValue(normalized, suffix, defaultText)
                    }
                },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .width(fieldWidth)
                    .onFocusChanged { editingText = it.isFocused },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (suffix == "毫秒") KeyboardType.Number else KeyboardType.Decimal
                ),
                suffix = { if (suffix.isNotEmpty()) Text(suffix) }
            )
        }
    }
}

private fun formatSliderValue(value: Float, suffix: String, fallback: String): String = when {
    suffix == "毫秒" -> value.roundToInt().toString()
    suffix == "秒" && value >= 1f -> value.roundToInt().toString()
    suffix == "秒" -> String.format(java.util.Locale.US, "%.2f", value)
    else -> fallback
}

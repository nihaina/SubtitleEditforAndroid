package com.subtitleedit.ui.settings

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

@Composable
internal fun AsrMergeGapTile(
    value: Int,
    valueText: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onTextChange: (String) -> Unit,
) {
    ModelSettingsSliderTile(
        title = stringResource(R.string.activity_speech_to_subtitle_settings_text_52),
        description = stringResource(R.string.activity_speech_to_subtitle_settings_text_53),
        value = value.toFloat(),
        onValueChange = onValueChange,
        valueRange = 0f..5000f,
        steps = 99,
        enabled = enabled,
        input = {
            AsrNumberInput(
                value = valueText,
                onValueChange = onTextChange,
                unit = stringResource(R.string.unit_milliseconds),
                enabled = enabled,
            )
        },
    )
}

@Composable
internal fun AsrMergeMaxCharactersTile(value: Int, enabled: Boolean, onValueChange: (Float) -> Unit) {
    ModelSettingsSliderTile(
        title = stringResource(R.string.speech_merge_max_characters),
        value = value.toFloat(),
        valueLabel = value.toString(),
        onValueChange = onValueChange,
        valueRange = 15f..50f,
        steps = 34,
        enabled = enabled,
    )
}

@Composable
internal fun AsrNumberInput(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String? = null,
    enabled: Boolean = true,
    width: Dp = 112.dp,
    keyboardType: KeyboardType = KeyboardType.Number,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.width(width),
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        suffix = unit?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
        colors = settingsOutlinedTextFieldColors(),
    )
}

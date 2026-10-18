package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppSlider
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SectionHeader
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.util.SettingsManager
import kotlin.math.roundToInt

@Composable
fun LlmSettingsScreen(
    settingsManager: SettingsManager,
    onNavigateBack: () -> Unit
) {
    var repackEnabled by remember { mutableStateOf(settingsManager.isLlmRepackEnabled()) }
    var contextSize by remember { mutableIntStateOf(settingsManager.getLlmContextSize()) }
    var thinkingBudget by remember { mutableIntStateOf(settingsManager.getLlmThinkingBudget()) }
    var temperature by remember { mutableFloatStateOf(settingsManager.getLlmTemperature()) }
    var topP by remember { mutableFloatStateOf(settingsManager.getLlmTopP()) }
    var repetitionPenalty by remember {
        mutableFloatStateOf(settingsManager.getLlmRepetitionPenalty())
    }
    var contextText by remember(contextSize) { mutableStateOf(contextSize.toString()) }
    var budgetText by remember(thinkingBudget) { mutableStateOf(thinkingBudget.toString()) }

    AppToolScaffold(
        title = stringResource(R.string.llm_settings_title),
        onBack = onNavigateBack
    ) {
        AppSection {
            SectionHeader(stringResource(R.string.llm_settings_section))
            SettingsSwitchRow(
                title = stringResource(R.string.llm_settings_repack_title),
                checked = repackEnabled,
                onCheckedChange = {
                    repackEnabled = it
                    settingsManager.setLlmRepackEnabled(it)
                },
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = stringResource(R.string.llm_settings_repack_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            OutlinedTextField(
                value = contextText,
                onValueChange = { value ->
                    val digits = value.filter(Char::isDigit)
                    contextText = digits
                    digits.toIntOrNull()?.takeIf { it in MIN_CONTEXT..MAX_CONTEXT }?.let { newSize ->
                        val oldSize = contextSize
                        val scaledBudget = if (oldSize > 0) {
                            (thinkingBudget.toLong() * newSize / oldSize).toInt()
                        } else {
                            newSize / 4
                        }
                        contextSize = newSize
                        thinkingBudget = scaledBudget.coerceIn(MIN_BUDGET, newSize)
                        budgetText = thinkingBudget.toString()
                        settingsManager.setLlmContextSize(newSize)
                        settingsManager.setLlmThinkingBudget(thinkingBudget)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                label = { Text(stringResource(R.string.llm_settings_context_size)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                text = stringResource(R.string.llm_settings_context_size_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            OutlinedTextField(
                value = budgetText,
                onValueChange = { value ->
                    val digits = value.filter(Char::isDigit)
                    budgetText = digits
                    digits.toIntOrNull()?.let { newBudget ->
                        val bounded = newBudget.coerceIn(MIN_BUDGET, contextSize)
                        thinkingBudget = bounded
                        budgetText = bounded.toString()
                        settingsManager.setLlmThinkingBudget(bounded)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                label = { Text(stringResource(R.string.llm_settings_thinking_budget)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                text = stringResource(R.string.llm_settings_thinking_budget_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            LlmFloatSlider(
                label = stringResource(R.string.llm_settings_temperature),
                value = temperature,
                valueRange = 0f..2f,
                valueText = "%.2f".format(temperature),
                onValueChange = {
                    temperature = (it * 100).roundToInt() / 100f
                    settingsManager.setLlmTemperature(temperature)
                }
            )
            LlmFloatSlider(
                label = stringResource(R.string.llm_settings_top_p),
                value = topP,
                valueRange = 0f..1f,
                valueText = "%.2f".format(topP),
                onValueChange = {
                    topP = (it * 100).roundToInt() / 100f
                    settingsManager.setLlmTopP(topP)
                },
                hint = if (topP >= 1f) stringResource(R.string.llm_settings_disabled) else null
            )
            LlmFloatSlider(
                label = stringResource(R.string.llm_settings_repetition_penalty),
                value = repetitionPenalty,
                valueRange = 1f..2f,
                valueText = "%.2f".format(repetitionPenalty),
                onValueChange = {
                    repetitionPenalty = (it * 100).roundToInt() / 100f
                    settingsManager.setLlmRepetitionPenalty(repetitionPenalty)
                },
                hint = if (repetitionPenalty <= 1f) {
                    stringResource(R.string.llm_settings_disabled)
                } else null
            )
        }
    }
}

@Composable
private fun LlmFloatSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    hint: String? = null,
    onValueChange: (Float) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        AppSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val MIN_CONTEXT = 1024
private const val MAX_CONTEXT = 32768
private const val MIN_BUDGET = 64

package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import com.subtitleedit.R
import com.subtitleedit.ui.settings.ModelSettingsGroup
import com.subtitleedit.ui.settings.ModelSettingsHint
import com.subtitleedit.ui.settings.ModelSettingsScaffold
import com.subtitleedit.ui.settings.ModelSettingsSliderTile
import com.subtitleedit.ui.settings.SettingsTileCard
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.settings.settingsOutlinedTextFieldColors
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

    ModelSettingsScaffold(
        title = stringResource(R.string.llm_settings_title),
        onBack = onNavigateBack,
    ) {
        ModelSettingsGroup(title = stringResource(R.string.llm_settings_section)) {
            SettingsTileSwitchRow(
                title = stringResource(R.string.llm_settings_repack_title),
                description = stringResource(R.string.llm_settings_repack_hint),
                checked = repackEnabled,
                onCheckedChange = {
                    repackEnabled = it
                    settingsManager.setLlmRepackEnabled(it)
                },
            )
            SettingsTileCard(grouped = true) {
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
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.llm_settings_context_size)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = settingsOutlinedTextFieldColors(),
                )
                ModelSettingsHint(stringResource(R.string.llm_settings_context_size_hint))
            }

            SettingsTileCard(grouped = true) {
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
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.llm_settings_thinking_budget)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = settingsOutlinedTextFieldColors(),
                )
                ModelSettingsHint(stringResource(R.string.llm_settings_thinking_budget_hint))
            }

            ModelSettingsSliderTile(
                title = stringResource(R.string.llm_settings_temperature),
                value = temperature,
                valueRange = 0f..2f,
                valueLabel = "%.2f".format(temperature),
                onValueChange = {
                    temperature = (it * 100).roundToInt() / 100f
                    settingsManager.setLlmTemperature(temperature)
                }
            )
            ModelSettingsSliderTile(
                title = stringResource(R.string.llm_settings_top_p),
                value = topP,
                valueRange = 0f..1f,
                valueLabel = "%.2f".format(topP),
                onValueChange = {
                    topP = (it * 100).roundToInt() / 100f
                    settingsManager.setLlmTopP(topP)
                },
                description = if (topP >= 1f) stringResource(R.string.llm_settings_disabled) else null
            )
            ModelSettingsSliderTile(
                title = stringResource(R.string.llm_settings_repetition_penalty),
                value = repetitionPenalty,
                valueRange = 1f..2f,
                valueLabel = "%.2f".format(repetitionPenalty),
                onValueChange = {
                    repetitionPenalty = (it * 100).roundToInt() / 100f
                    settingsManager.setLlmRepetitionPenalty(repetitionPenalty)
                },
                description = if (repetitionPenalty <= 1f) {
                    stringResource(R.string.llm_settings_disabled)
                } else null
            )
        }
    }
}

private const val MIN_CONTEXT = 1024
private const val MAX_CONTEXT = 32768
private const val MIN_BUDGET = 64

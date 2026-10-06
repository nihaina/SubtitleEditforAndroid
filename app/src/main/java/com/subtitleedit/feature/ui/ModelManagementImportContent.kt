package com.subtitleedit.feature.ui

import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppCard

data class AsrModelImportUiState(
    val modelTitleRes: Int = R.string.model_import_whisper_model,
    val encoderLabelRes: Int = R.string.model_import_encoder_model,
    val encoderValue: String = "",
    val encoderDurationSeconds: Int? = null,
    val encoderButtonLabelRes: Int = R.string.activity_model_settings_text_06,
    val decoderValue: String = "",
    val joinerLabelRes: Int = R.string.model_import_joiner_model,
    val joinerValue: String = "",
    val joinerButtonLabelRes: Int = R.string.model_import_select_joiner,
    val tokensLabelRes: Int = R.string.model_import_tokens_file,
    val tokensIsDirectory: Boolean = false,
    val tokensValue: String = "",
    val showDecoder: Boolean = true,
    val showJoiner: Boolean = false,
    val showForcedAligner: Boolean = false,
    val forcedAlignerComplete: Boolean = false,
    val forcedAlignerStatus: ForcedAlignerImportStatus = ForcedAlignerImportStatus.NOT_CONFIGURED,
    val forcedAlignerGraphName: String? = null,
    val forcedAlignerDataName: String? = null,
    val showModelDownload: Boolean = true,
    val showModelReset: Boolean = false,
    val showSenseVoiceProvider: Boolean = false,
    val useSenseVoiceNpu: Boolean = false,
    val npuAvailable: Boolean = true,
    val showParakeetVariant: Boolean = false,
    val parakeetCtcSelected: Boolean = false,
    val vadModelFileName: String? = null,
    val useBuiltInVad: Boolean = true,
    val actionsEnabled: Boolean = true
)

data class DemucsModelImportUiState(
    val useFtModels: Boolean = false,
    val generalModelValue: String? = null,
    val hasGeneralModel: Boolean = false,
    val ftModelValues: Map<String, String?> = emptyMap(),
    val actionsEnabled: Boolean = true
)

data class LlmModelImportUiState(
    val modelValue: String = "",
    val hasModel: Boolean = false,
    val modelFamily: LlmModelFamily = LlmModelFamily.INDEX_TRANSLATE,
    val showModelDownload: Boolean = true,
    val showModelReset: Boolean = false,
    val actionsEnabled: Boolean = true
)

/** Model family used to select one-click download variants for local LLMs. */
enum class LlmModelFamily(val labelRes: Int) {
    INDEX_TRANSLATE(R.string.model_mgmt_llm_type_index_translate),
    GEMMA4(R.string.model_mgmt_llm_type_gemma4)
}

enum class ForcedAlignerImportStatus {
    NOT_CONFIGURED,
    LOCAL_MODEL_AVAILABLE,
    INCOMPLETE,
    CONFIGURED
}

enum class AsrModelImportAction {
    SelectModelType,
    SelectEncoder,
    DownloadModel,
    ResetModel,
    ConfigureWhisper,
    SelectDecoder,
    SelectJoiner,
    SelectTokens,
    SelectForcedAligner,
    DownloadForcedAligner,
    ResetForcedAligner,
    ConfigureVad,
    SelectVad,
    SelectSenseVoiceCpu,
    SelectSenseVoiceNpu,
    SelectParakeetTdt,
    SelectParakeetCtc,
    ShowGuide
}

private data class ModelChoiceOption(
    val id: AsrModelImportAction,
    val label: String,
    val selected: Boolean
)

enum class DemucsModelImportAction {
    Configure,
    SelectModelType,
    SelectGeneralModel,
    DownloadGeneralModel,
    ResetGeneralModel,
    SelectVocalsModel,
    SelectDrumsModel,
    SelectBassModel,
    SelectOtherModel,
    ShowGuide
}

enum class LlmModelImportAction {
    Configure,
    SelectModelType,
    SelectModel,
    DownloadModel,
    ResetModel
}

@Composable
fun ModelManagementImportContent(
    asr: AsrModelImportUiState,
    llm: LlmModelImportUiState,
    demucs: DemucsModelImportUiState,
    onAsrAction: (AsrModelImportAction) -> Unit,
    onBuiltInVadChanged: (Boolean) -> Unit,
    onLlmAction: (LlmModelImportAction) -> Unit,
    onDemucsAction: (DemucsModelImportAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AsrModelImportCard(
            asr,
            onAsrAction,
            onBuiltInVadChanged,
            llm,
            onLlmAction,
            modifier = Modifier.padding(top = 16.dp)
        )
        DemucsModelImportCard(demucs, onDemucsAction)
    }
}

@Composable
private fun AsrModelImportCard(
    state: AsrModelImportUiState,
    onAction: (AsrModelImportAction) -> Unit,
    onBuiltInVadChanged: (Boolean) -> Unit,
    llm: LlmModelImportUiState,
    onLlmAction: (LlmModelImportAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ModelImportCard {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(state.modelTitleRes),
                        style = MaterialTheme.typography.titleMedium
                    )
                    ModelFileControl(
                        label = stringResource(state.encoderLabelRes),
                        value = when {
                            state.encoderValue.isBlank() -> stringResource(R.string.model_import_not_selected)
                            state.encoderDurationSeconds != null -> stringResource(
                                R.string.model_import_npu_duration,
                                state.encoderValue,
                                state.encoderDurationSeconds
                            )
                            else -> state.encoderValue
                        },
                        selectLabel = stringResource(state.encoderButtonLabelRes),
                        onSelect = { onAction(AsrModelImportAction.SelectEncoder) },
                        enabled = state.actionsEnabled,
                        fillSelectButton = true,
                        showDownload = state.showModelDownload,
                        showReset = state.showModelReset,
                        downloadDescription = stringResource(
                            R.string.model_import_download_and_import_model,
                            stringResource(state.modelTitleRes)
                        ),
                        onDownload = { onAction(AsrModelImportAction.DownloadModel) },
                        onReset = { onAction(AsrModelImportAction.ResetModel) },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                Column(
                    modifier = Modifier.padding(start = 12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    TextButton(
                        onClick = { onAction(AsrModelImportAction.ConfigureWhisper) },
                        modifier = Modifier.height(36.dp)
                    ) { Text(stringResource(R.string.activity_model_settings_text_08)) }
                    OutlinedButton(
                        onClick = { onAction(AsrModelImportAction.SelectModelType) },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            stringResource(R.string.activity_model_settings_text_09),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (state.showSenseVoiceProvider) {
                        ChoiceOptions(
                            options = listOf(
                                ModelChoiceOption(AsrModelImportAction.SelectSenseVoiceCpu, "CPU", !state.useSenseVoiceNpu),
                                ModelChoiceOption(AsrModelImportAction.SelectSenseVoiceNpu, "NPU", state.useSenseVoiceNpu)
                            ),
                            enabled = state.actionsEnabled,
                            secondOptionAvailable = state.npuAvailable,
                            onSelected = onAction
                        )
                    }
                    if (state.showParakeetVariant) {
                        ChoiceOptions(
                            options = listOf(
                                ModelChoiceOption(AsrModelImportAction.SelectParakeetTdt, "TDT", !state.parakeetCtcSelected),
                                ModelChoiceOption(AsrModelImportAction.SelectParakeetCtc, "CTC", state.parakeetCtcSelected)
                            ),
                            enabled = state.actionsEnabled,
                            onSelected = onAction
                        )
                    }
                }
            }

            if (state.showDecoder) {
                ModelFileControl(
                    label = stringResource(R.string.model_import_decoder_model),
                    value = state.decoderValue,
                    selectLabel = stringResource(R.string.model_import_select_decoder),
                    onSelect = { onAction(AsrModelImportAction.SelectDecoder) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            if (state.showJoiner) {
                ModelFileControl(
                    label = stringResource(state.joinerLabelRes),
                    value = state.joinerValue,
                    selectLabel = stringResource(state.joinerButtonLabelRes),
                    onSelect = { onAction(AsrModelImportAction.SelectJoiner) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            ModelFileControl(
                    label = stringResource(state.tokensLabelRes),
                    value = state.tokensValue,
                    selectLabel = stringResource(
                        if (state.tokensIsDirectory) R.string.model_import_select_tokenizer_folder
                        else R.string.model_import_select_tokens
                    ),
                onSelect = { onAction(AsrModelImportAction.SelectTokens) },
                enabled = state.actionsEnabled,
                modifier = Modifier.padding(top = 12.dp)
            )

            if (state.showForcedAligner) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = stringResource(R.string.activity_model_management_qwen_aligner_title),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.activity_model_management_qwen_aligner_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onAction(AsrModelImportAction.SelectForcedAligner) },
                            enabled = state.actionsEnabled,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                stringResource(R.string.activity_model_management_qwen_aligner_select),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        if (!state.forcedAlignerComplete) {
                            ModelDownloadButton(
                                onClick = { onAction(AsrModelImportAction.DownloadForcedAligner) },
                                enabled = state.actionsEnabled,
                                description = stringResource(R.string.activity_model_management_qwen_aligner_download)
                            )
                        } else {
                            ModelResetButton(
                                onClick = { onAction(AsrModelImportAction.ResetForcedAligner) },
                                enabled = state.actionsEnabled
                            )
                        }
                    }
                    Text(
                        text = forcedAlignerStatusText(state),
                        color = if (state.forcedAlignerComplete) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            HelpLink(onClick = { onAction(AsrModelImportAction.ShowGuide) })
        }

        if (Process.is64Bit()) {
            LlmModelImportCard(llm, onLlmAction)
        }

        ModelImportCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.activity_model_settings_text_14),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = { onAction(AsrModelImportAction.ConfigureVad) }) {
                    Text(stringResource(R.string.activity_model_settings_text_08))
                }
            }
            Text(
                text = when {
                    state.useBuiltInVad -> stringResource(R.string.model_import_builtin_vad_value)
                    state.vadModelFileName.isNullOrBlank() -> stringResource(R.string.model_import_vad_not_selected)
                    else -> state.vadModelFileName.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { onAction(AsrModelImportAction.SelectVad) }) {
                    Text(stringResource(R.string.model_import_select_vad))
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                        .toggleable(
                            value = state.useBuiltInVad,
                            role = Role.Checkbox,
                            onValueChange = onBuiltInVadChanged
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = state.useBuiltInVad, onCheckedChange = null)
                    Text(stringResource(R.string.model_import_use_builtin_vad), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun LlmModelImportCard(
    state: LlmModelImportUiState,
    onAction: (LlmModelImportAction) -> Unit
) {
    ModelImportCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.model_mgmt_llm_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(state.modelFamily.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp)
                )
                ModelFileControl(
                    label = stringResource(R.string.model_mgmt_llm_file_label),
                    value = state.modelValue.ifBlank { stringResource(R.string.model_import_not_selected) },
                    selectLabel = stringResource(R.string.model_mgmt_llm_select_file),
                    onSelect = { onAction(LlmModelImportAction.SelectModel) },
                    enabled = state.actionsEnabled,
                    fillSelectButton = true,
                    showDownload = state.showModelDownload,
                    showReset = state.showModelReset,
                    downloadDescription = stringResource(R.string.model_mgmt_llm_download_title),
                    onDownload = { onAction(LlmModelImportAction.DownloadModel) },
                    onReset = { onAction(LlmModelImportAction.ResetModel) },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Column(
                modifier = Modifier.padding(start = 12.dp),
                horizontalAlignment = Alignment.End
            ) {
                TextButton(
                    onClick = { onAction(LlmModelImportAction.Configure) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        stringResource(R.string.activity_model_settings_text_08),
                        maxLines = 1,
                        softWrap = false
                    )
                }
                OutlinedButton(
                    onClick = { onAction(LlmModelImportAction.SelectModelType) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.model_mgmt_llm_switch_type),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.model_mgmt_llm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun DemucsModelImportCard(
    state: DemucsModelImportUiState,
    onAction: (DemucsModelImportAction) -> Unit
) {
    ModelImportCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    if (state.useFtModels) R.string.model_import_demucs_ft
                    else R.string.model_import_demucs_general
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(
                onClick = { onAction(DemucsModelImportAction.Configure) },
                modifier = Modifier.height(36.dp)
            ) { Text(stringResource(R.string.activity_model_settings_text_08)) }
        }
        if (!state.useFtModels) {
            DemucsValueWithSwitch(
                value = state.generalModelValue ?: stringResource(R.string.model_import_general_not_selected),
                enabled = state.actionsEnabled,
                onSwitch = { onAction(DemucsModelImportAction.SelectModelType) },
                modifier = Modifier.padding(top = 12.dp)
            )
            ModelSelectRow(
                selectLabel = stringResource(R.string.activity_vocal_separation_settings_text_03),
                onSelect = { onAction(DemucsModelImportAction.SelectGeneralModel) },
                enabled = state.actionsEnabled,
                fillSelectButton = true,
                showDownload = !state.hasGeneralModel,
                showReset = state.hasGeneralModel,
                downloadDescription = stringResource(R.string.activity_vocal_separation_settings_contentdescription_01),
                onDownload = { onAction(DemucsModelImportAction.DownloadGeneralModel) },
                onReset = { onAction(DemucsModelImportAction.ResetGeneralModel) }
            )
            Text(
                text = stringResource(R.string.activity_vocal_separation_settings_text_04),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            DemucsValueWithSwitch(
                value = state.ftModelValues["vocals"]
                    ?: stringResource(R.string.model_import_ft_not_selected, "Vocals"),
                enabled = state.actionsEnabled,
                onSwitch = { onAction(DemucsModelImportAction.SelectModelType) },
                modifier = Modifier.padding(top = 12.dp)
            )
            OutlinedButton(
                onClick = { onAction(DemucsModelImportAction.SelectVocalsModel) }
            ) { Text(stringResource(R.string.activity_vocal_separation_settings_text_06)) }
            listOf(
                Triple("drums", R.string.activity_vocal_separation_settings_text_08, DemucsModelImportAction.SelectDrumsModel),
                Triple("bass", R.string.activity_vocal_separation_settings_text_10, DemucsModelImportAction.SelectBassModel),
                Triple("other", R.string.activity_vocal_separation_settings_text_12, DemucsModelImportAction.SelectOtherModel)
            ).forEach { (key, label, action) ->
                SelectionContainer(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = state.ftModelValues[key]
                            ?: stringResource(
                                R.string.model_import_ft_not_selected,
                                key.replaceFirstChar(Char::uppercase)
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { onAction(action) }) {
                    Text(stringResource(label))
                }
            }
        }
        Text(
            text = stringResource(R.string.activity_vocal_separation_settings_text_13),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        HelpLink(onClick = { onAction(DemucsModelImportAction.ShowGuide) })
    }
}

@Composable
private fun forcedAlignerStatusText(state: AsrModelImportUiState): String = when (state.forcedAlignerStatus) {
    ForcedAlignerImportStatus.NOT_CONFIGURED ->
        stringResource(R.string.model_import_forced_aligner_not_configured)
    ForcedAlignerImportStatus.LOCAL_MODEL_AVAILABLE ->
        stringResource(R.string.model_import_forced_aligner_detected)
    ForcedAlignerImportStatus.INCOMPLETE ->
        stringResource(R.string.model_import_forced_aligner_incomplete)
    ForcedAlignerImportStatus.CONFIGURED -> stringResource(
        R.string.model_import_forced_aligner_configured,
        state.forcedAlignerGraphName.orEmpty(),
        state.forcedAlignerDataName.orEmpty()
    )
}

@Composable
private fun ModelImportCard(content: @Composable () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun ChoiceOptions(
    options: List<ModelChoiceOption>,
    enabled: Boolean,
    secondOptionAvailable: Boolean = true,
    onSelected: (AsrModelImportAction) -> Unit
) {
    val npuContentDescription = stringResource(
        if (secondOptionAvailable) {
            R.string.model_import_sensevoice_npu_content_description
        } else {
            R.string.model_import_sensevoice_npu_unavailable
        }
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { index, option ->
            Text(
                text = option.label,
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .height(36.dp)
                    .clickable(
                        enabled = enabled
                    ) { onSelected(option.id) }
                    .alpha(if (index == 1 && !secondOptionAvailable) 0.55f else 1f)
                    .then(
                        if (option.id == AsrModelImportAction.SelectSenseVoiceNpu) Modifier.semantics {
                            contentDescription = npuContentDescription
                        } else Modifier
                    ),
                textAlign = TextAlign.Center,
                style = if (option.selected) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.bodySmall,
                color = if (option.selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModelFileControl(
    label: String,
    value: String,
    selectLabel: String,
    onSelect: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    fillSelectButton: Boolean = false,
    showDownload: Boolean = false,
    showReset: Boolean = false,
    downloadDescription: String? = null,
    onDownload: () -> Unit = {},
    onReset: () -> Unit = {}
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        ModelSelectRow(
            selectLabel = selectLabel,
            onSelect = onSelect,
            enabled = enabled,
            fillSelectButton = fillSelectButton,
            showDownload = showDownload,
            showReset = showReset,
            downloadDescription = downloadDescription
                ?: stringResource(R.string.activity_model_settings_contentdescription_01),
            onDownload = onDownload,
            onReset = onReset,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ModelSelectRow(
    selectLabel: String,
    onSelect: () -> Unit,
    enabled: Boolean,
    fillSelectButton: Boolean = false,
    showDownload: Boolean = false,
    showReset: Boolean = false,
    downloadDescription: String,
    onDownload: () -> Unit = {},
    onReset: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onSelect,
            enabled = enabled,
            modifier = (if (fillSelectButton) Modifier.weight(1f) else Modifier)
                .height(36.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text(selectLabel, maxLines = 1, softWrap = false)
        }
        if (showDownload) {
            ModelDownloadButton(onClick = onDownload, enabled = enabled, description = downloadDescription)
        }
        if (showReset) {
            ModelResetButton(onClick = onReset, enabled = enabled)
        }
    }
}

@Composable
private fun ModelDownloadButton(onClick: () -> Unit, enabled: Boolean, description: String) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_download),
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ModelResetButton(onClick: () -> Unit, enabled: Boolean) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .widthIn(min = 52.dp)
            .height(28.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.activity_model_settings_text_07),
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DemucsValueWithSwitch(
    value: String,
    enabled: Boolean,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = value.substringBefore('\n'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
        }
        OutlinedButton(
            onClick = onSwitch,
            enabled = enabled,
            modifier = Modifier.padding(start = 12.dp).height(36.dp)
        ) { Text(stringResource(R.string.activity_model_settings_text_09), maxLines = 1) }
    }
}

@Composable
private fun HelpLink(onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
        Text(
            text = stringResource(R.string.tts_help),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

data class AsrModelImportUiState(
    val modelTitle: String = "Whisper 模型",
    val encoderLabel: String = "Encoder 模型",
    val encoderValue: String = "未选择",
    val encoderButtonLabel: String = "选择 Encoder",
    val decoderValue: String = "未选择",
    val joinerLabel: String = "Joiner 模型",
    val joinerValue: String = "未选择",
    val joinerButtonLabel: String = "选择 Joiner",
    val tokensLabel: String = "Tokens 文件",
    val tokensValue: String = "未选择",
    val showDecoder: Boolean = true,
    val showJoiner: Boolean = false,
    val showForcedAligner: Boolean = false,
    val forcedAlignerComplete: Boolean = false,
    val forcedAlignerStatus: String = "尚未配置 ForcedAligner 模型及权重",
    val showModelDownload: Boolean = true,
    val showModelReset: Boolean = false,
    val showSenseVoiceProvider: Boolean = false,
    val useSenseVoiceNpu: Boolean = false,
    val npuAvailable: Boolean = true,
    val showParakeetVariant: Boolean = false,
    val parakeetCtcSelected: Boolean = false,
    val vadValue: String = "silero_vad.onnx（内置）",
    val useBuiltInVad: Boolean = true,
    val actionsEnabled: Boolean = true
)

data class DemucsModelImportUiState(
    val useFtModels: Boolean = false,
    val generalModelValue: String = "未选择通用四轨模型",
    val hasGeneralModel: Boolean = false,
    val ftModelValues: Map<String, String> = emptyMap(),
    val actionsEnabled: Boolean = true
)

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

@Composable
fun ModelManagementImportContent(
    asr: AsrModelImportUiState,
    demucs: DemucsModelImportUiState,
    onAsrAction: (AsrModelImportAction) -> Unit,
    onBuiltInVadChanged: (Boolean) -> Unit,
    onDemucsAction: (DemucsModelImportAction) -> Unit
) {
    Column(
        modifier = Modifier.padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AsrModelImportCard(asr, onAsrAction, onBuiltInVadChanged)
        DemucsModelImportCard(demucs, onDemucsAction)
    }
}

@Composable
private fun AsrModelImportCard(
    state: AsrModelImportUiState,
    onAction: (AsrModelImportAction) -> Unit,
    onBuiltInVadChanged: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.modelTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        ModelFileControl(
                            label = state.encoderLabel,
                            value = state.encoderValue,
                            selectLabel = state.encoderButtonLabel,
                            onSelect = { onAction(AsrModelImportAction.SelectEncoder) },
                            enabled = state.actionsEnabled,
                            showDownload = state.showModelDownload,
                            showReset = state.showModelReset,
                            onDownload = { onAction(AsrModelImportAction.DownloadModel) },
                            onReset = { onAction(AsrModelImportAction.ResetModel) },
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    Column(
                        modifier = Modifier.padding(start = 12.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = { onAction(AsrModelImportAction.ConfigureWhisper) }) {
                            Text(stringResource(R.string.activity_model_settings_text_08), maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onAction(AsrModelImportAction.SelectModelType) },
                            enabled = state.actionsEnabled
                        ) {
                            Text(stringResource(R.string.activity_model_settings_text_09), maxLines = 1)
                        }
                        if (state.showSenseVoiceProvider) {
                            ChoiceChips(
                                options = listOf("CPU" to !state.useSenseVoiceNpu, "NPU" to state.useSenseVoiceNpu),
                                enabled = state.actionsEnabled,
                                onSelected = { if (it == "NPU") onAction(AsrModelImportAction.SelectSenseVoiceNpu)
                                    else onAction(AsrModelImportAction.SelectSenseVoiceCpu) }
                            )
                            if (!state.npuAvailable) {
                                Text(
                                    "SenseVoice NPU 需要安装 QNN 版",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (state.showParakeetVariant) {
                            ChoiceChips(
                                options = listOf("TDT" to !state.parakeetCtcSelected, "CTC" to state.parakeetCtcSelected),
                                enabled = state.actionsEnabled,
                                onSelected = { if (it == "CTC") onAction(AsrModelImportAction.SelectParakeetCtc)
                                    else onAction(AsrModelImportAction.SelectParakeetTdt) }
                            )
                        }
                    }
                }
                if (state.showDecoder) {
                    ModelFileControl(
                        label = stringResource(R.string.activity_model_settings_text_10),
                        value = state.decoderValue,
                        selectLabel = stringResource(R.string.activity_model_settings_text_11),
                        onSelect = { onAction(AsrModelImportAction.SelectDecoder) },
                        enabled = state.actionsEnabled
                    )
                }
                if (state.showJoiner) {
                    ModelFileControl(
                        label = state.joinerLabel,
                        value = state.joinerValue,
                        selectLabel = state.joinerButtonLabel,
                        onSelect = { onAction(AsrModelImportAction.SelectJoiner) },
                        enabled = state.actionsEnabled
                    )
                }
                ModelFileControl(
                    label = state.tokensLabel,
                    value = state.tokensValue,
                    selectLabel = if (state.tokensLabel == "Tokenizer 文件夹") "选择 Tokenizer 文件夹"
                    else stringResource(R.string.activity_model_settings_text_13),
                    onSelect = { onAction(AsrModelImportAction.SelectTokens) },
                    enabled = state.actionsEnabled
                )
                if (state.showForcedAligner) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.activity_model_management_qwen_aligner_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.activity_model_management_qwen_aligner_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            state.forcedAlignerStatus,
                            color = if (state.forcedAlignerComplete) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onAction(AsrModelImportAction.SelectForcedAligner) },
                                enabled = state.actionsEnabled,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    stringResource(R.string.activity_model_management_qwen_aligner_select),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!state.forcedAlignerComplete) {
                                IconButton(
                                    onClick = { onAction(AsrModelImportAction.DownloadForcedAligner) },
                                    enabled = state.actionsEnabled
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_download),
                                        contentDescription = stringResource(
                                            R.string.activity_model_management_qwen_aligner_download
                                        )
                                    )
                                }
                            } else {
                                TextButton(
                                    onClick = { onAction(AsrModelImportAction.ResetForcedAligner) },
                                    enabled = state.actionsEnabled
                                ) { Text("重置") }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { onAction(AsrModelImportAction.ShowGuide) }) {
                        Text(stringResource(R.string.tts_help), maxLines = 1)
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.activity_model_settings_text_14),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(state.vadValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onAction(AsrModelImportAction.ConfigureVad) }) {
                        Text(stringResource(R.string.activity_model_settings_text_08), maxLines = 1)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onAction(AsrModelImportAction.SelectVad) }) {
                        Text("选择 VAD 模型", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.useBuiltInVad,
                            onCheckedChange = onBuiltInVadChanged
                        )
                        Text(
                            stringResource(R.string.activity_model_settings_text_15),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DemucsModelImportCard(
    state: DemucsModelImportUiState,
    onAction: (DemucsModelImportAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.useFtModels) "FT 单音轨模型"
                    else stringResource(R.string.activity_vocal_separation_settings_text_01),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { onAction(DemucsModelImportAction.Configure) }) {
                    Text(stringResource(R.string.activity_model_settings_text_08), maxLines = 1)
                }
            }
            if (!state.useFtModels) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            state.generalModelValue,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = { onAction(DemucsModelImportAction.SelectModelType) },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Text(stringResource(R.string.activity_model_settings_text_09), maxLines = 1)
                    }
                }
                ModelFileControl(
                    label = "",
                    value = "",
                    selectLabel = stringResource(R.string.activity_vocal_separation_settings_text_03),
                    onSelect = { onAction(DemucsModelImportAction.SelectGeneralModel) },
                    enabled = state.actionsEnabled,
                    showLabel = false,
                    showValue = false,
                    showDownload = !state.hasGeneralModel,
                    showReset = state.hasGeneralModel,
                    onDownload = { onAction(DemucsModelImportAction.DownloadGeneralModel) },
                    onReset = { onAction(DemucsModelImportAction.ResetGeneralModel) }
                )
                Text(
                    stringResource(R.string.activity_vocal_separation_settings_text_04),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                listOf(
                    Triple("Vocals", "vocals", DemucsModelImportAction.SelectVocalsModel),
                    Triple("Drums", "drums", DemucsModelImportAction.SelectDrumsModel),
                    Triple("Bass", "bass", DemucsModelImportAction.SelectBassModel),
                    Triple("Other", "other", DemucsModelImportAction.SelectOtherModel)
                ).forEachIndexed { index, (title, key, action) ->
                    val value = state.ftModelValues[key] ?: "未选择 $title specialist 模型"
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (index == 0) {
                                OutlinedButton(
                                    onClick = { onAction(DemucsModelImportAction.SelectModelType) },
                                    enabled = state.actionsEnabled,
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Text(stringResource(R.string.activity_model_settings_text_09), maxLines = 1)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { onAction(action) },
                            enabled = state.actionsEnabled
                        ) {
                            Text("选择 $title 模型", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(
                    stringResource(R.string.activity_vocal_separation_settings_text_13),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onAction(DemucsModelImportAction.ShowGuide) }) {
                    Text(stringResource(R.string.tts_help), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ChoiceChips(
    options: List<Pair<String, Boolean>>,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, selected) ->
            FilterChip(
                selected = selected,
                onClick = { onSelected(label) },
                enabled = enabled,
                label = { Text(label) }
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
    showLabel: Boolean = true,
    showValue: Boolean = true,
    showDownload: Boolean = false,
    showReset: Boolean = false,
    onDownload: () -> Unit = {},
    onReset: () -> Unit = {},
    valueMaxLines: Int = 1,
    valueOverflow: TextOverflow = TextOverflow.MiddleEllipsis
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabel) Text(label, style = MaterialTheme.typography.labelLarge)
        if (showValue) {
            SelectionContainer {
                Text(
                    value,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = valueMaxLines,
                    overflow = valueOverflow
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onSelect,
                modifier = Modifier.weight(1f),
                enabled = enabled
            ) {
                Text(selectLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showDownload) {
                IconButton(onClick = onDownload, enabled = enabled) {
                    Icon(
                        painterResource(R.drawable.ic_download),
                        contentDescription = "下载并导入模型"
                    )
                }
            }
            if (showReset) {
                TextButton(onClick = onReset, enabled = enabled) { Text("重置") }
            }
        }
    }
}

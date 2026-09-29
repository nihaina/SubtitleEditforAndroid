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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

data class AsrModelImportUiState(
    val modelTitle: String = "语音识别模型",
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
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.modelTitle,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { onAction(AsrModelImportAction.ShowGuide) }
                    ) { Text("帮助") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onAction(AsrModelImportAction.SelectModelType) },
                        enabled = state.actionsEnabled
                    ) { Text("模型类型") }
                    TextButton(
                        onClick = { onAction(AsrModelImportAction.ConfigureWhisper) }
                    ) { Text("配置") }
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

                ModelFileControl(
                    label = state.encoderLabel,
                    value = state.encoderValue,
                    selectLabel = state.encoderButtonLabel,
                    onSelect = { onAction(AsrModelImportAction.SelectEncoder) },
                    enabled = state.actionsEnabled,
                    showDownload = state.showModelDownload,
                    showReset = state.showModelReset,
                    onDownload = { onAction(AsrModelImportAction.DownloadModel) },
                    onReset = { onAction(AsrModelImportAction.ResetModel) }
                )
                if (state.showDecoder) {
                    ModelFileControl(
                        label = "Decoder 模型",
                        value = state.decoderValue,
                        selectLabel = "选择 Decoder",
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
                    selectLabel = if (state.tokensLabel == "Tokenizer 文件夹") "选择 Tokenizer 文件夹" else "选择 Tokens",
                    onSelect = { onAction(AsrModelImportAction.SelectTokens) },
                    enabled = state.actionsEnabled
                )
                if (state.showForcedAligner) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Qwen3 ForcedAligner", style = MaterialTheme.typography.titleSmall)
                        Text(
                            state.forcedAlignerStatus,
                            color = if (state.forcedAlignerComplete) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onAction(AsrModelImportAction.SelectForcedAligner) },
                                enabled = state.actionsEnabled
                            ) { Text("选择模型") }
                            if (!state.forcedAlignerComplete) {
                                IconButton(
                                    onClick = { onAction(AsrModelImportAction.DownloadForcedAligner) },
                                    enabled = state.actionsEnabled
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_download),
                                        contentDescription = "下载 ForcedAligner 模型"
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
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("VAD 模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(state.vadValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onAction(AsrModelImportAction.ConfigureVad) }) { Text("配置") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { onAction(AsrModelImportAction.SelectVad) }
                    ) { Text("选择 VAD 模型") }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = state.useBuiltInVad,
                            onCheckedChange = onBuiltInVadChanged
                        )
                        Text("使用内置模型", style = MaterialTheme.typography.bodyMedium)
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
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.useFtModels) "FT 单音轨模型" else "通用四轨模型",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { onAction(DemucsModelImportAction.ShowGuide) }) { Text("帮助") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onAction(DemucsModelImportAction.SelectModelType) },
                    enabled = state.actionsEnabled
                ) { Text("模型类型") }
                TextButton(onClick = { onAction(DemucsModelImportAction.Configure) }) { Text("配置") }
            }
            if (!state.useFtModels) {
                ModelFileControl(
                    label = "通用四轨模型",
                    value = state.generalModelValue,
                    selectLabel = "选择模型",
                    onSelect = { onAction(DemucsModelImportAction.SelectGeneralModel) },
                    enabled = state.actionsEnabled,
                    showDownload = !state.hasGeneralModel,
                    showReset = state.hasGeneralModel,
                    onDownload = { onAction(DemucsModelImportAction.DownloadGeneralModel) },
                    onReset = { onAction(DemucsModelImportAction.ResetGeneralModel) }
                )
                Text(
                    "通用模型可一次输出 Vocals、Drums、Bass、Other 多个音轨。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                listOf(
                    Triple("Vocals", "vocals", DemucsModelImportAction.SelectVocalsModel),
                    Triple("Drums", "drums", DemucsModelImportAction.SelectDrumsModel),
                    Triple("Bass", "bass", DemucsModelImportAction.SelectBassModel),
                    Triple("Other", "other", DemucsModelImportAction.SelectOtherModel)
                ).forEach { (title, key, action) ->
                    ModelFileControl(
                        label = "$title specialist 模型",
                        value = state.ftModelValues[key] ?: "未选择 $title specialist 模型",
                        selectLabel = "选择 $title 模型",
                        onSelect = { onAction(action) },
                        enabled = true
                    )
                }
                Text(
                    "FT 模型用于对应单音轨任务；未配置时回退通用模型。多音轨任务仍需要通用模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    showDownload: Boolean = false,
    showReset: Boolean = false,
    onDownload: () -> Unit = {},
    onReset: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(
                value,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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

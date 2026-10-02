package com.subtitleedit.feature.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AsrModelImportCard(
            asr,
            onAsrAction,
            onBuiltInVadChanged,
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
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ModelImportCard {
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
                        fillSelectButton = true,
                        showDownload = state.showModelDownload,
                        showReset = state.showModelReset,
                        downloadDescription = "一键下载并导入 ${state.modelTitle}",
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
                    ) { Text("配置") }
                    OutlinedButton(
                        onClick = { onAction(AsrModelImportAction.SelectModelType) },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.height(36.dp)
                    ) { Text(stringResource(R.string.activity_model_settings_text_09), maxLines = 1) }
                    if (state.showSenseVoiceProvider) {
                        ChoiceOptions(
                            options = listOf("CPU" to !state.useSenseVoiceNpu, "NPU" to state.useSenseVoiceNpu),
                            enabled = state.actionsEnabled,
                            secondOptionAvailable = state.npuAvailable,
                            onSelected = {
                                onAction(
                                    if (it == "NPU") AsrModelImportAction.SelectSenseVoiceNpu
                                    else AsrModelImportAction.SelectSenseVoiceCpu
                                )
                            }
                        )
                    }
                    if (state.showParakeetVariant) {
                        ChoiceOptions(
                            options = listOf("TDT" to !state.parakeetCtcSelected, "CTC" to state.parakeetCtcSelected),
                            enabled = state.actionsEnabled,
                            onSelected = {
                                onAction(
                                    if (it == "CTC") AsrModelImportAction.SelectParakeetCtc
                                    else AsrModelImportAction.SelectParakeetTdt
                                )
                            }
                        )
                    }
                }
            }

            if (state.showDecoder) {
                ModelFileControl(
                    label = "Decoder 模型",
                    value = state.decoderValue,
                    selectLabel = "选择 Decoder",
                    onSelect = { onAction(AsrModelImportAction.SelectDecoder) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            if (state.showJoiner) {
                ModelFileControl(
                    label = state.joinerLabel,
                    value = state.joinerValue,
                    selectLabel = state.joinerButtonLabel,
                    onSelect = { onAction(AsrModelImportAction.SelectJoiner) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            ModelFileControl(
                label = state.tokensLabel,
                value = state.tokensValue,
                selectLabel = if (state.tokensLabel == "Tokenizer 文件夹") "选择 Tokenizer 文件夹" else "选择 Tokens",
                onSelect = { onAction(AsrModelImportAction.SelectTokens) },
                enabled = state.actionsEnabled,
                modifier = Modifier.padding(top = 12.dp)
            )

            if (state.showForcedAligner) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = stringResource(R.string.activity_model_management_qwen_aligner_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
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
                        text = state.forcedAlignerStatus,
                        color = if (state.forcedAlignerComplete) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            HelpLink(onClick = { onAction(AsrModelImportAction.ShowGuide) })
        }

        ModelImportCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.activity_model_settings_text_14),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { onAction(AsrModelImportAction.ConfigureVad) }) { Text("配置") }
            }
            Text(
                text = state.vadValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { onAction(AsrModelImportAction.SelectVad) }) {
                    Text("选择 VAD 模型")
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
                    Text("使用内置模型", style = MaterialTheme.typography.bodyMedium)
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
    ModelImportCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.useFtModels) "FT 单音轨模型" else "通用四轨模型",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(
                onClick = { onAction(DemucsModelImportAction.Configure) },
                modifier = Modifier.height(36.dp)
            ) { Text("配置") }
        }
        if (!state.useFtModels) {
            DemucsValueWithSwitch(
                value = state.generalModelValue,
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
                value = state.ftModelValues["vocals"] ?: "未选择 Vocals specialist 模型",
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
                        text = state.ftModelValues[key] ?: "未选择 $key specialist 模型",
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
private fun ModelImportCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun ChoiceOptions(
    options: List<Pair<String, Boolean>>,
    enabled: Boolean,
    secondOptionAvailable: Boolean = true,
    onSelected: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { index, (label, selected) ->
            Text(
                text = label,
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .height(36.dp)
                    .clickable(
                        enabled = enabled
                    ) { onSelected(label) }
                    .alpha(if (index == 1 && !secondOptionAvailable) 0.55f else 1f)
                    .then(
                        if (label == "NPU") Modifier.semantics {
                            contentDescription = if (secondOptionAvailable) {
                                "选择 SenseVoice NPU"
                            } else {
                                "SenseVoice NPU，需要安装 QNN 版"
                            }
                        } else Modifier
                    ),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
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
    downloadDescription: String = "下载并导入模型",
    onDownload: () -> Unit = {},
    onReset: () -> Unit = {}
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
            downloadDescription = downloadDescription,
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
    downloadDescription: String = "下载并导入模型",
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

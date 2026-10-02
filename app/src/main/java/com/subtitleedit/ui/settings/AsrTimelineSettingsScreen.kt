package com.subtitleedit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.style.TextOverflow
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSlider

data class AsrTimelineSettingsState(
    val useVadTimestamp: Boolean = false,
    val fixedSegmentSeconds: Int = 10,
    val fixedSegmentSecondsText: String = "10",
    val fixedVadSegmentation: Boolean = true,
    val tokenTimestampModelAvailable: Boolean = false,
    val tokenTimestampEnabled: Boolean = false,
    val tokenTimestampMerge: Boolean = true,
    val smartMerge: Boolean = true,
    val filterLongMerge: Boolean = true,
    val mergeMaxCharacters: Int = 25,
    val mergeGapMs: Int = 150,
    val mergeGapText: String = "150"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrTimelineSettingsScreen(
    title: String,
    state: AsrTimelineSettingsState,
    onBack: () -> Unit,
    onOpenVadSettings: () -> Unit,
    onUseVadTimestampChanged: (Boolean) -> Unit,
    onFixedSegmentSecondsChanged: (Float) -> Unit,
    onFixedSegmentSecondsTextChanged: (String) -> Unit,
    onFixedVadSegmentationChanged: (Boolean) -> Unit,
    onTokenTimestampEnabledChanged: (Boolean) -> Unit,
    onTokenTimestampMergeChanged: (Boolean) -> Unit,
    onSmartMergeChanged: (Boolean) -> Unit,
    onFilterLongMergeChanged: (Boolean) -> Unit,
    onMergeMaxCharactersChanged: (Float) -> Unit,
    onMergeGapChanged: (Float) -> Unit,
    onMergeGapTextChanged: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.height(56.dp),
                title = { Text(title) },
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
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TimelineSettingsCard {
                Text(
                    text = "使用 VAD 打轴",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                SettingsSwitchRow(
                    title = "使用 VAD 检测并划分语音段",
                    checked = state.useVadTimestamp,
                    onCheckedChange = onUseVadTimestampChanged
                )
                Text(
                    text = "开启后使用 VAD 打轴；关闭后可使用下方实验功能或固定时长分段",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                if (state.useVadTimestamp) {
                    OutlinedButton(
                        onClick = onOpenVadSettings,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.asr_vad_settings_entry))
                    }
                }
            }

            if (!state.useVadTimestamp) {
                TimelineSettingsCard {
                    Text(
                        text = stringResource(R.string.activity_speech_to_subtitle_settings_text_41),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.activity_speech_to_subtitle_settings_text_55),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.activity_speech_to_subtitle_settings_text_42),
                        description = stringResource(R.string.activity_speech_to_subtitle_settings_text_43),
                        checked = state.tokenTimestampEnabled,
                        enabled = state.tokenTimestampModelAvailable,
                        onCheckedChange = onTokenTimestampEnabledChanged,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (!state.tokenTimestampModelAvailable) {
                        Text(
                            text = stringResource(R.string.activity_speech_to_subtitle_settings_text_47),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                    }
                    SettingsSwitchRow(
                        title = stringResource(R.string.activity_speech_to_subtitle_settings_text_50),
                        description = stringResource(R.string.activity_speech_to_subtitle_settings_text_51),
                        checked = state.tokenTimestampMerge,
                        enabled = state.tokenTimestampEnabled,
                        onCheckedChange = onTokenTimestampMergeChanged,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    val mergeEnabled = state.tokenTimestampEnabled && state.tokenTimestampMerge
                    Column(modifier = Modifier.alpha(if (mergeEnabled) 1f else 0.5f)) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.speech_merge_smart),
                            description = stringResource(R.string.speech_merge_smart_hint),
                            checked = state.smartMerge,
                            enabled = mergeEnabled,
                            onCheckedChange = onSmartMergeChanged
                        )
                        Text(
                            text = stringResource(R.string.activity_speech_to_subtitle_settings_text_52),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (mergeEnabled && !state.smartMerge) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            },
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp)
                        )
                        Text(
                            text = stringResource(R.string.activity_speech_to_subtitle_settings_text_53),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (mergeEnabled && !state.smartMerge) 1f else 0.5f
                            ),
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (mergeEnabled && !state.smartMerge) 1f else 0.5f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppSlider(
                                value = state.mergeGapMs.toFloat(),
                                onValueChange = onMergeGapChanged,
                                valueRange = 0f..5000f,
                                steps = 99,
                                enabled = mergeEnabled && !state.smartMerge,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.mergeGapText,
                                onValueChange = onMergeGapTextChanged,
                                enabled = mergeEnabled && !state.smartMerge,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                suffix = { Text("毫秒") },
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .width(104.dp)
                            )
                        }
                        SettingsSwitchRow(
                            title = stringResource(R.string.speech_merge_filter_long),
                            checked = state.filterLongMerge,
                            enabled = mergeEnabled,
                            onCheckedChange = onFilterLongMergeChanged,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (mergeEnabled && state.filterLongMerge) 1f else 0.5f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.speech_merge_max_characters),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(72.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Clip
                            )
                            AppSlider(
                                value = state.mergeMaxCharacters.toFloat(),
                                onValueChange = onMergeMaxCharactersChanged,
                                valueRange = 15f..50f,
                                steps = 34,
                                enabled = mergeEnabled && state.filterLongMerge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = state.mergeMaxCharacters.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(40.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    SettingsSwitchRow(
                    title = "VAD 分段：在固定时长附近的相对静音处切分",
                    description = "使用当前 VAD 模型，在固定切点前后半个分段时长内寻找语音概率最低的连续区间；无需达到固定静音阈值（模型最大时长仍会限制搜索范围）",
                    checked = state.fixedVadSegmentation,
                    enabled = !state.useVadTimestamp,
                    onCheckedChange = onFixedVadSegmentationChanged,
                    modifier = Modifier.padding(top = 12.dp),
                    dimDescriptionWhenDisabled = false
                    )
                    Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_settings_text_02),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp)
                    )
                    Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_settings_text_03),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                    Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppSlider(
                        value = state.fixedSegmentSeconds.toFloat(),
                        onValueChange = onFixedSegmentSecondsChanged,
                        valueRange = 5f..120f,
                        steps = 22,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.fixedSegmentSecondsText,
                        onValueChange = onFixedSegmentSecondsTextChanged,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        suffix = { Text("秒") },
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .width(96.dp)
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineSettingsCard(content: @Composable () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    dimDescriptionWhenDisabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f)
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled || !dimDescriptionWhenDisabled) 1f else 0.5f
                    ),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
    }
}

package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import com.subtitleedit.ui.settings.ModelSettingsGroup
import com.subtitleedit.ui.settings.ModelSettingsHint
import com.subtitleedit.ui.settings.ModelSettingsScaffold
import com.subtitleedit.ui.settings.ModelSettingsSliderTile
import com.subtitleedit.ui.settings.SettingsTileCard
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import kotlin.math.roundToInt

/** 人声分离运行参数；Demucs 模型选择与导入已集中到模型管理页。 */
class VocalSeparationSettingsActivity : AppComposeActivity() {
    private val viewModel: VocalSeparationSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                VocalSeparationSettingsScreen(
                    intraOpThreads = state.intraOpThreads,
                    onIntraOpThreadsChanged = viewModel::setIntraOpThreads,
                    chunkParallelEnabled = state.chunkParallelEnabled,
                    onChunkParallelChanged = viewModel::setChunkParallelEnabled,
                    graphOptimizationEnabled = state.graphOptimizationEnabled,
                    cpuArenaEnabled = state.cpuArenaEnabled,
                    onGraphOptimizationChanged = viewModel::setGraphOptimizationEnabled,
                    onCpuArenaChanged = viewModel::setCpuArenaEnabled,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}

@Composable
internal fun VocalSeparationSettingsScreen(
    intraOpThreads: Int,
    onIntraOpThreadsChanged: (Int) -> Unit,
    chunkParallelEnabled: Boolean,
    onChunkParallelChanged: (Boolean) -> Unit,
    graphOptimizationEnabled: Boolean,
    cpuArenaEnabled: Boolean,
    onGraphOptimizationChanged: (Boolean) -> Unit,
    onCpuArenaChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val intraOpThreadsDraft = remember(intraOpThreads) { mutableStateOf(intraOpThreads.toFloat()) }
    ModelSettingsScaffold(
        title = stringResource(R.string.activity_vocal_separation_settings_title),
        onBack = onBack,
        imePadding = false,
    ) {
        ModelSettingsGroup(title = stringResource(R.string.activity_vocal_separation_settings_text_14)) {
            SettingsTileSwitchRow(
                title = stringResource(R.string.activity_vocal_separation_settings_text_15),
                description = stringResource(R.string.activity_vocal_separation_settings_text_16),
                checked = graphOptimizationEnabled,
                onCheckedChange = onGraphOptimizationChanged
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.activity_vocal_separation_settings_text_17),
                description = stringResource(R.string.activity_vocal_separation_settings_text_18),
                checked = cpuArenaEnabled,
                onCheckedChange = onCpuArenaChanged
            )
            ModelSettingsSliderTile(
                title = stringResource(R.string.activity_vocal_separation_settings_text_20),
                description = stringResource(R.string.activity_vocal_separation_settings_text_21),
                valueLabel = stringResource(
                    R.string.activity_vocal_separation_settings_text_22,
                    intraOpThreadsDraft.value.roundToInt(),
                ),
                value = intraOpThreadsDraft.value,
                onValueChange = { intraOpThreadsDraft.value = it },
                valueRange = 1f..8f,
                steps = 6,
                onValueChangeFinished = {
                    onIntraOpThreadsChanged(intraOpThreadsDraft.value.roundToInt())
                },
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.activity_vocal_separation_settings_text_23),
                description = stringResource(R.string.activity_vocal_separation_settings_text_24),
                checked = chunkParallelEnabled,
                onCheckedChange = onChunkParallelChanged
            )
            SettingsTileCard(grouped = true) {
                ModelSettingsHint(stringResource(R.string.activity_vocal_separation_settings_text_19))
            }
        }
    }
}

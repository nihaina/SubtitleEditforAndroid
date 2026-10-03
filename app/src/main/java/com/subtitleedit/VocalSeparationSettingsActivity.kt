package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

/** 人声分离运行参数；Demucs 模型选择与导入已集中到模型管理页。 */
class VocalSeparationSettingsActivity : AppComposeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsManager.getInstance(this)

        setContent {
            SubtitleEditComposeTheme {
                var graphOptimizationEnabled by rememberSaveable {
                    mutableStateOf(settings.isDemixOrtGraphOptimizationEnabled())
                }
                var cpuArenaEnabled by rememberSaveable {
                    mutableStateOf(settings.isDemixOrtCpuArenaEnabled())
                }

                VocalSeparationSettingsScreen(
                    graphOptimizationEnabled = graphOptimizationEnabled,
                    cpuArenaEnabled = cpuArenaEnabled,
                    onGraphOptimizationChanged = { enabled ->
                        graphOptimizationEnabled = enabled
                        settings.setDemixOrtGraphOptimizationEnabled(enabled)
                    },
                    onCpuArenaChanged = { enabled ->
                        cpuArenaEnabled = enabled
                        settings.setDemixOrtCpuArenaEnabled(enabled)
                    },
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}

@Composable
private fun VocalSeparationSettingsScreen(
    graphOptimizationEnabled: Boolean,
    cpuArenaEnabled: Boolean,
    onGraphOptimizationChanged: (Boolean) -> Unit,
    onCpuArenaChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.activity_vocal_separation_settings_title),
        onBack = onBack
    ) {
        AppSection(title = stringResource(R.string.activity_vocal_separation_settings_text_14)) {
            SettingsSwitchRow(
                title = stringResource(R.string.activity_vocal_separation_settings_text_15),
                description = stringResource(R.string.activity_vocal_separation_settings_text_16),
                checked = graphOptimizationEnabled,
                onCheckedChange = onGraphOptimizationChanged
            )
            SettingsSwitchRow(
                title = stringResource(R.string.activity_vocal_separation_settings_text_17),
                description = stringResource(R.string.activity_vocal_separation_settings_text_18),
                checked = cpuArenaEnabled,
                onCheckedChange = onCpuArenaChanged
            )
            Text(
                text = stringResource(R.string.activity_vocal_separation_settings_text_19),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

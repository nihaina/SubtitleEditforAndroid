package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

/** 人声分离运行参数；Demucs 模型选择与导入已集中到模型管理页。 */
class VocalSeparationSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsManager.getInstance(this)

        setContent {
            SubtitleEditComposeTheme {
                var graphOptimizationEnabled by remember {
                    mutableStateOf(settings.isDemixOrtGraphOptimizationEnabled())
                }
                var cpuArenaEnabled by remember {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VocalSeparationSettingsScreen(
    graphOptimizationEnabled: Boolean,
    cpuArenaEnabled: Boolean,
    onGraphOptimizationChanged: (Boolean) -> Unit,
    onCpuArenaChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人声分离设置") },
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
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.activity_vocal_separation_settings_text_14),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                    SwitchSetting(
                        title = stringResource(R.string.activity_vocal_separation_settings_text_15),
                        description = stringResource(R.string.activity_vocal_separation_settings_text_16),
                        checked = graphOptimizationEnabled,
                        onCheckedChange = onGraphOptimizationChanged
                    )
                    SwitchSetting(
                        title = stringResource(R.string.activity_vocal_separation_settings_text_17),
                        description = stringResource(R.string.activity_vocal_separation_settings_text_18),
                        checked = cpuArenaEnabled,
                        onCheckedChange = onCpuArenaChanged
                    )
                }
            }

            Text(
                text = stringResource(R.string.activity_vocal_separation_settings_text_19),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

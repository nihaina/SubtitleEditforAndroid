package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                modifier = Modifier.height(56.dp),
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
            Spacer(Modifier.height(16.dp))
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.activity_vocal_separation_settings_text_14),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    SwitchSetting(
                        title = stringResource(R.string.activity_vocal_separation_settings_text_15),
                        checked = graphOptimizationEnabled,
                        onCheckedChange = onGraphOptimizationChanged
                    )
                    Text(
                        text = stringResource(R.string.activity_vocal_separation_settings_text_16),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    SwitchSetting(
                        title = stringResource(R.string.activity_vocal_separation_settings_text_17),
                        checked = cpuArenaEnabled,
                        onCheckedChange = onCpuArenaChanged
                    )
                    Text(
                        text = stringResource(R.string.activity_vocal_separation_settings_text_18),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.activity_vocal_separation_settings_text_19),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
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
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

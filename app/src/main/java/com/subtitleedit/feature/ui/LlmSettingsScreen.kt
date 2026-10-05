package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.ui.components.SectionHeader
import com.subtitleedit.util.SettingsManager

@Composable
fun LlmSettingsScreen(
    settingsManager: SettingsManager,
    onNavigateBack: () -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.llm_settings_title),
        onBack = onNavigateBack
    ) {
        AppSection {
            SectionHeader(stringResource(R.string.llm_settings_section))
            SettingsSwitchRow(
                title = stringResource(R.string.llm_settings_repack_title),
                checked = settingsManager.isLlmRepackEnabled(),
                onCheckedChange = settingsManager::setLlmRepackEnabled,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = stringResource(R.string.llm_settings_repack_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

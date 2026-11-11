package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppCard
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.AppSpacing

@Composable
fun SubtitleFormatSelectScreen(
    selectedFileName: String?,
    onSelectFile: () -> Unit,
    onConfirm: () -> Unit,
    onNavigateBack: () -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.formatting_tool_title),
        onBack = onNavigateBack,
        bottomBar = {
            AppPrimaryButton(
                text = stringResource(R.string.activity_subtitle_format_select_text_05),
                onClick = onConfirm,
                enabled = selectedFileName != null,
                modifier = Modifier.padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
            )
        }
    ) {
        AppSection(
            title = stringResource(R.string.activity_subtitle_format_select_text_01),
            subtitle = stringResource(R.string.activity_subtitle_format_select_text_02)
        ) {
            AppCard(onClick = onSelectFile, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        selectedFileName ?: stringResource(R.string.activity_subtitle_format_select_text_04),
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                }
            }
        }
    }
}

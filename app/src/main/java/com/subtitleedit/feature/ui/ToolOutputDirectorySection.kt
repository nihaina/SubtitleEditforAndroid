package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.theme.AppSpacing
import androidx.compose.ui.res.stringResource

@Composable
internal fun ToolOutputDirectorySection(
    outputDirectory: String,
    saveToSourceDirectory: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onSelectOutputDirectory: () -> Unit,
    onSaveToSourceDirectoryChange: (Boolean) -> Unit
) {
    AppSection(
        title = stringResource(R.string.activity_tool_output_directory),
        modifier = modifier
    ) {
        Text(
            outputDirectory,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onSelectOutputDirectory,
                enabled = enabled && !saveToSourceDirectory
            ) {
                Text(stringResource(R.string.activity_tool_select_directory))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.save_to_source_folder),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = enabled) {
                        onSaveToSourceDirectoryChange(!saveToSourceDirectory)
                    }
                )
                Checkbox(
                    checked = saveToSourceDirectory,
                    onCheckedChange = onSaveToSourceDirectoryChange,
                    enabled = enabled
                )
            }
        }
    }
}

package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.model.FileBrowserViewMode

@Composable
internal fun ToolPendingFileRow(
    fileName: String,
    fileSizeLabel: String,
    onRemove: (() -> Unit)? = null,
    removeEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrowserFileItem(
            entry = BrowserFileEntry(name = fileName, isDirectory = false),
            viewMode = FileBrowserViewMode.DETAILED,
            modifier = Modifier.weight(1f),
            directoryPageStyle = true,
            summaryOverride = fileSizeLabel,
            supportingContent = supportingContent,
            horizontalContentPadding = 0.dp
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove, enabled = removeEnabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.delete),
                    tint = if (removeEnabled) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

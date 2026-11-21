package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppImeDialog

internal val LocalCompactMediaConvertDialog = staticCompositionLocalOf { false }

/** Shared container for conversion dialogs with a scrollable body and separate actions. */
@Composable
internal fun MediaConvertDialogSurface(
    onDismiss: () -> Unit,
    withImePadding: Boolean = false,
    heightFraction: Float? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val panel: @Composable () -> Unit = {
        var modifier = Modifier.padding(vertical = if (withImePadding) 4.dp else 12.dp)
            .widthIn(max = 600.dp).fillMaxWidth(0.95f)
        if (heightFraction != null) modifier = modifier.fillMaxHeight(heightFraction)
        Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            BoxWithConstraints {
                CompositionLocalProvider(LocalCompactMediaConvertDialog provides (maxHeight < 240.dp)) {
                    Column(content = content)
                }
            }
        }
    }
    if (withImePadding) {
        AppImeDialog(onDismissRequest = onDismiss, content = panel)
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
            content = panel,
        )
    }
}

@Composable
internal fun MediaConvertDialogTitle(title: String) {
    val verticalPadding = if (LocalCompactMediaConvertDialog.current) 4.dp else 20.dp
    Text(title, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = verticalPadding), style = MaterialTheme.typography.titleMedium)
    HorizontalDivider()
}

@Composable
internal fun MediaConvertDialogActions(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    canConfirm: Boolean = true,
    confirmModifier: Modifier = Modifier
) {
    val verticalPadding = if (LocalCompactMediaConvertDialog.current) 0.dp else 8.dp
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = onConfirm, enabled = canConfirm, modifier = confirmModifier) {
                Text(stringResource(R.string.media_convert_bitrate_done))
            }
        }
    }
}

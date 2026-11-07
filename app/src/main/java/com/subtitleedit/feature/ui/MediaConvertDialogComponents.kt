package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.R

/** Shared container of the media convert option dialogs; the caller lays out the title, scroll area and actions. A null heightFraction wraps the content. */
@Composable
internal fun MediaConvertDialogSurface(
    onDismiss: () -> Unit,
    withImePadding: Boolean = false,
    heightFraction: Float? = 0.9f,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.95f)
        if (heightFraction != null) modifier = modifier.fillMaxHeight(heightFraction)
        if (withImePadding) modifier = modifier.imePadding()
        Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(content = content)
        }
    }
}

@Composable
internal fun MediaConvertDialogTitle(title: String) {
    Text(title, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), style = MaterialTheme.typography.titleMedium)
    HorizontalDivider()
}

@Composable
internal fun MediaConvertDialogActions(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    canConfirm: Boolean = true,
    confirmModifier: Modifier = Modifier
) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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

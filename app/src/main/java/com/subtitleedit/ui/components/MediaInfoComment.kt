package com.subtitleedit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

@Composable
internal fun MediaInfoComment(
    value: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    dialogWindow: @Composable () -> Unit = {}
) {
    var truncated by remember(value) { mutableStateOf(false) }
    var showFull by remember(value) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = value,
            style = style,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { truncated = it.didOverflowHeight }
        )
        if (truncated) {
            TextButton(onClick = { showFull = true }) {
                Text(stringResource(R.string.media_details_open_comment))
            }
        }
    }
    if (showFull) {
        AppDialog(
            onDismissRequest = { showFull = false },
            title = {
                dialogWindow()
                Text(stringResource(R.string.media_convert_info_comment))
            },
            text = {
                SelectionContainer(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(value)
                }
            },
            confirmButton = {
                TextButton(onClick = { showFull = false }) { Text(stringResource(R.string.player_info_done)) }
            }
        )
    }
}

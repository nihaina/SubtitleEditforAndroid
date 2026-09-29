package com.subtitleedit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.editor.EditorSearchUiState

@Composable
internal fun EditorSearchBar(
    state: EditorSearchUiState,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onToggleMatchCase: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onClose: () -> Unit
) {
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.visible) {
        if (state.visible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }
    if (!state.visible) return

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.resultCount,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FilterChip(
                    selected = state.matchCase,
                    onClick = onToggleMatchCase,
                    label = { Text("大小写", maxLines = 1) }
                )
                FilterChip(
                    selected = state.wholeWord,
                    onClick = onToggleWholeWord,
                    label = { Text("全词", maxLines = 1) }
                )
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "关闭搜索"
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1.15f).focusRequester(searchFocusRequester),
                    label = { Text(stringResource(R.string.search_find_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() })
                )
                OutlinedTextField(
                    value = state.replacement,
                    onValueChange = onReplacementChange,
                    modifier = Modifier.weight(0.85f),
                    label = { Text(stringResource(R.string.search_replace_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = onPrevious, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.search_previous), maxLines = 1)
                }
                TextButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.search_next), maxLines = 1)
                }
                TextButton(onClick = onReplace, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.activity_editor_text_01), maxLines = 1)
                }
                TextButton(onClick = onReplaceAll, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.activity_editor_text_02), maxLines = 1)
                }
            }
        }
    }
}

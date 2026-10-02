package com.subtitleedit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.editor.EditorSearchUiState

/**
 * Compose implementation of the editor's legacy three-row search card.
 *
 * The old XML deliberately used compact fixed heights and short option labels. Keeping those
 * constraints here prevents Material 3's default field/chip padding from changing the editor
 * layout while the search controller and callbacks remain unchanged.
 */
@Composable
internal fun EditorSearchBar(
    state: EditorSearchUiState,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSubmitSearch: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onToggleMatchCase: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onClose: () -> Unit
) {
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.visible, state.focusRequestSequence) {
        if (state.visible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }
    if (!state.visible) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.resultCount,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SearchOptionButton(
                    text = stringResource(R.string.search_match_case_short),
                    description = stringResource(R.string.search_match_case),
                    selected = state.matchCase,
                    onClick = onToggleMatchCase
                )
                SearchOptionButton(
                    text = stringResource(R.string.search_whole_word_short),
                    description = stringResource(R.string.search_whole_word),
                    selected = state.wholeWord,
                    onClick = onToggleWholeWord,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            SearchDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.search_find_label),
                    modifier = Modifier.padding(end = 8.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                LegacySearchInput(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1.15f)
                        .focusRequester(searchFocusRequester),
                    imeAction = ImeAction.Search,
                    keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() })
                )
                Text(
                    text = stringResource(R.string.search_replace_label),
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                LegacySearchInput(
                    value = state.replacement,
                    onValueChange = onReplacementChange,
                    modifier = Modifier.weight(0.85f),
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
            }

            SearchDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SearchActionButton(stringResource(R.string.search_previous), onPrevious)
                SearchActionButton(stringResource(R.string.search_next), onNext)
                SearchActionButton(stringResource(R.string.activity_editor_text_01), onReplace)
                SearchActionButton(stringResource(R.string.search_replace_all_short), onReplaceAll)
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.search_close),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchOptionButton(
    text: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState()
    ) {
        TextButton(
            onClick = onClick,
            modifier = modifier
                .size(width = 48.dp, height = 40.dp)
                .semantics { contentDescription = description },
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        ) {
            Text(text = text, fontSize = 16.sp, maxLines = 1)
        }
    }
}

@Composable
private fun LegacySearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    imeAction: ImeAction,
    keyboardActions: KeyboardActions
) {
    val primary = MaterialTheme.colorScheme.primary
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(44.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = primary,
                    start = Offset(0f, size.height - stroke),
                    end = Offset(size.width, size.height - stroke),
                    strokeWidth = stroke
                )
            },
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = imeAction,
            keyboardType = KeyboardType.Text
        ),
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(primary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                innerTextField()
            }
        }
    )
}

@Composable
private fun RowScope.SearchActionButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(48.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    ) {
        Text(text = text, fontSize = 14.sp, maxLines = 1)
    }
}

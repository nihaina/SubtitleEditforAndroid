package com.subtitleedit.feature.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.appcompat.R as AppCompatR
import com.subtitleedit.R
import com.subtitleedit.ui.theme.AppMotion

/** Directory browsing and file picking share the same in-toolbar search and title transitions. */
@Composable
internal fun DirectoryToolbarTitle(
    searchOpen: Boolean,
    query: String,
    title: String,
    onQueryChanged: (String) -> Unit,
    onCloseSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = searchOpen,
        modifier = modifier,
        transitionSpec = {
            fadeIn(AppMotion.enter()) togetherWith fadeOut(AppMotion.exit())
        },
        label = "directory-search"
    ) { searching ->
        if (searching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions.Default.copy(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search
                    ),
                    // Search submission leaves both the query and the action view open.
                    keyboardActions = KeyboardActions(onSearch = {}),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.file_search_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                IconButton(
                    onClick = {
                        if (query.isEmpty()) onCloseSearch() else onQueryChanged("")
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painterResource(AppCompatR.drawable.abc_ic_clear_material),
                        contentDescription = if (query.isEmpty()) {
                            stringResource(R.string.cancel)
                        } else {
                            "清除搜索"
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        } else {
            AnimatedContent(
                targetState = title,
                transitionSpec = {
                    fadeIn(animationSpec = AppMotion.fast()) +
                        slideInVertically(animationSpec = AppMotion.fast()) { 8 } togetherWith
                        fadeOut(animationSpec = AppMotion.fast())
                },
                label = "main-toolbar-title"
            ) { animatedTitle ->
                Text(
                    text = animatedTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

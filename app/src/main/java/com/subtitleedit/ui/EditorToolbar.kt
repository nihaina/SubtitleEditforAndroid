package com.subtitleedit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

internal data class EditorToolbarMenuAction(
    val title: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

internal data class EditorToolbarMenuGroup(
    val title: String? = null,
    val actions: List<EditorToolbarMenuAction>
)

@Composable
internal fun EditorComposeContent(
    searchContent: @Composable () -> Unit,
    isSourceViewMode: Boolean,
    subtitleContent: @Composable () -> Unit,
    mediaContent: @Composable () -> Unit = {},
    sourceContent: @Composable () -> Unit = {}
) {
    Column(Modifier.fillMaxSize()) {
        searchContent()
        mediaContent()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (isSourceViewMode) sourceContent() else subtitleContent()
        }
    }
}

@Composable
internal fun EditorListLoadingOverlay() {
    // The legacy FrameLayout had no background: it only blocked input while the centered
    // progress indicator was visible. Keep the document readable during a large refresh.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    var pointerDown: Boolean
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        pointerDown = event.changes.any { it.pressed }
                    } while (pointerDown)
                }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(48.dp))
            Text(
                text = "正在更新字幕…",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorToolbar(
    title: String,
    subtitle: String,
    isSelectionActive: Boolean,
    onNavigateUp: () -> Unit,
    prepareMenu: () -> List<EditorToolbarMenuGroup>
) {
    var expandedGroup by remember { mutableStateOf<Int?>(null) }
    var menuGroups by remember { mutableStateOf(emptyList<EditorToolbarMenuGroup>()) }

    TopAppBar(
        modifier = Modifier.height(48.dp),
        title = {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        navigationIcon = {
            ToolbarIconButton(
                // The legacy toolbar used the close affordance while rows were selected;
                // the host navigation callback cancels selection before leaving the editor.
                icon = if (isSelectionActive) R.drawable.ic_close else R.drawable.ic_back,
                description = if (isSelectionActive) "取消选择" else "返回",
                onClick = onNavigateUp
            )
        },
        actions = {
            if (isSelectionActive) {
                val selectionActions = prepareMenu().firstOrNull()?.actions.orEmpty()
                selectionActions.take(2).forEachIndexed { index, action ->
                    ToolbarIconButton(
                        icon = if (index == 0) R.drawable.ic_select_all else R.drawable.ic_select_range,
                        description = action.title,
                        onClick = action.onClick
                    )
                }
                Box {
                    IconButton(onClick = {
                        menuGroups = prepareMenu()
                        expandedGroup = 0
                    }) {
                        Icon(painterResource(R.drawable.ic_more_vertical), contentDescription = "更多选项")
                    }
                    EditorActionDropdown(
                        expanded = expandedGroup == 0,
                        actions = menuGroups.firstOrNull()?.actions?.drop(2).orEmpty(),
                        onDismiss = { expandedGroup = null }
                    )
                }
            } else {
                Box {
                    IconButton(onClick = {
                        menuGroups = prepareMenu()
                        expandedGroup = 0
                    }) {
                        Icon(painterResource(R.drawable.ic_file), contentDescription = "文件")
                    }
                    EditorActionDropdown(
                        expanded = expandedGroup == 0,
                        actions = menuGroups.getOrNull(0)?.actions.orEmpty(),
                        onDismiss = { expandedGroup = null }
                    )
                }
                Box {
                    TextButton(onClick = {
                        menuGroups = prepareMenu()
                        expandedGroup = 1
                    }) {
                        Text(text = menuGroups.getOrNull(1)?.title ?: "编辑")
                    }
                    EditorActionDropdown(
                        expanded = expandedGroup == 1,
                        actions = menuGroups.getOrNull(1)?.actions.orEmpty(),
                        onDismiss = { expandedGroup = null }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun EditorActionDropdown(
    expanded: Boolean,
    actions: List<EditorToolbarMenuAction>,
    onDismiss: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.title) },
                    enabled = action.enabled,
                    onClick = {
                        onDismiss()
                        action.onClick()
                    }
                )
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(painter = painterResource(icon), contentDescription = description)
    }
}

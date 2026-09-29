package com.subtitleedit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    sourceContent: @Composable () -> Unit = {},
    listLoading: Boolean = false
) {
    Column(Modifier.fillMaxSize()) {
        searchContent()
        mediaContent()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (isSourceViewMode) sourceContent() else subtitleContent()
            if (listLoading) EditorListLoadingOverlay()
        }
    }
}

@Composable
private fun EditorListLoadingOverlay() {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator()
            Text(
                text = "正在更新字幕…",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
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
    var expanded by remember { mutableStateOf(false) }
    var menuGroups by remember { mutableStateOf(emptyList<EditorToolbarMenuGroup>()) }

    TopAppBar(
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
                icon = if (isSelectionActive) R.drawable.ic_close else R.drawable.ic_back,
                description = if (isSelectionActive) "取消选择" else "返回",
                onClick = onNavigateUp
            )
        },
        actions = {
            IconButton(
                onClick = {
                    menuGroups = prepareMenu()
                    expanded = true
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vertical),
                    contentDescription = "更多选项"
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    menuGroups.forEachIndexed { index, group ->
                        if (group.title != null) {
                            if (index > 0) HorizontalDivider()
                            Text(
                                text = group.title,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        group.actions.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.title) },
                                enabled = action.enabled,
                                onClick = {
                                    expanded = false
                                    action.onClick()
                                }
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
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

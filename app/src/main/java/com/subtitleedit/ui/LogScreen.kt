package com.subtitleedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.util.RuntimeLogManager

data class LogSection(
    val title: String,
    val startedAt: String,
    val content: String,
    val lineCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    sections: List<LogSection>,
    pageOptions: List<String>,
    pageFilter: String,
    displayMode: RuntimeLogManager.DisplayMode,
    infoText: String,
    isRefreshing: Boolean,
    isExportEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onDisplayModeChange: (RuntimeLogManager.DisplayMode) -> Unit,
    onPageFilterChange: (String) -> Unit
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val expandedSections = remember(sections) { mutableStateMapOf<Int, Boolean>() }
    val visibleSections = sections.mapIndexedNotNull { index, section ->
        if (pageFilter == "全部页面" || section.title.substringBefore(" - ") == pageFilter) {
            index to section
        } else {
            null
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                        Text(if (isRefreshing) "读取中" else "刷新")
                    }
                    IconButton(onClick = onExport, enabled = isExportEnabled) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = "导出"
                        )
                    }
                    Box {
                        IconButton(onClick = { actionsExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vertical),
                                contentDescription = "更多选项"
                            )
                        }
                        DropdownMenu(
                            expanded = actionsExpanded,
                            onDismissRequest = { actionsExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("清空日志") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete_normal),
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    actionsExpanded = false
                                    onClear()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Text(
                text = infoText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = displayMode == RuntimeLogManager.DisplayMode.SIMPLE,
                    onClick = { onDisplayModeChange(RuntimeLogManager.DisplayMode.SIMPLE) },
                    label = { Text("简单") }
                )
                FilterChip(
                    selected = displayMode == RuntimeLogManager.DisplayMode.DETAILED,
                    onClick = { onDisplayModeChange(RuntimeLogManager.DisplayMode.DETAILED) },
                    label = { Text("详细") }
                )
            }
            PageFilterMenu(
                options = pageOptions,
                selected = pageFilter,
                onSelected = onPageFilterChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (visibleSections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            isRefreshing -> "正在读取日志..."
                            sections.isEmpty() -> "暂无可读取的日志"
                            else -> "此页面暂无日志"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(visibleSections, key = { it.first }) { (index, section) ->
                        LogSectionRow(
                            section = section,
                            expanded = expandedSections[index] == true,
                            onToggleExpanded = {
                                expandedSections[index] = expandedSections[index] != true
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageFilterMenu(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = "页面：$selected",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = "选择页面",
                modifier = Modifier
                    .size(18.dp)
                    .rotate(90f)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = if (option == selected) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun LogSectionRow(
    section: LogSection,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = if (expanded) "收起日志" else "展开日志",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${section.startedAt} · ${section.lineCount} 行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    text = section.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(10.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

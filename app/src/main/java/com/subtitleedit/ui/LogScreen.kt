package com.subtitleedit.ui

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
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
    showClearedPlaceholder: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onDisplayModeChange: (RuntimeLogManager.DisplayMode) -> Unit,
    onPageFilterChange: (String) -> Unit
) {
    val expandedSections = remember(sections) { mutableStateMapOf<Int, Boolean>() }
    var placeholderExpanded by remember(showClearedPlaceholder) { mutableStateOf(false) }
    // The legacy RecyclerView adapter was detached while a refresh was running,
    // even though the Activity retained the last parsed sections in memory.
    val visibleSections = if (isRefreshing) {
        emptyList()
    } else {
        sections.mapIndexedNotNull { index, section ->
            if (pageFilter == "全部页面" || section.title.substringBefore(" - ") == pageFilter) {
                index to section
            } else {
                null
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = Modifier.height(56.dp),
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                },
                actions = { TextButton(onClick = onClear) { Text("清空") } }
            )
        }
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            Text(
                text = infoText,
                    modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onRefresh, enabled = !isRefreshing) { Text("刷新") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onExport, enabled = isExportEnabled) { Text("导出") }
            }
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            ) {
                val modes = listOf(
                    "简单" to RuntimeLogManager.DisplayMode.SIMPLE,
                    "详细" to RuntimeLogManager.DisplayMode.DETAILED
                )
                modes.forEachIndexed { index, (label, mode) ->
                    SegmentedButton(
                        selected = displayMode == mode,
                        onClick = { onDisplayModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        colors = androidx.compose.material3.SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        icon = {}
                    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                }
            }
            PageFilterMenu(pageOptions, pageFilter, onPageFilterChange)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(visibleSections, key = { it.first }) { (index, section) ->
                    LogSectionRow(
                        section,
                        expandedSections[index] == true,
                        { expandedSections[index] = expandedSections[index] != true }
                    )
                }
                if (showClearedPlaceholder && sections.isEmpty()) {
                    item {
                        LogSectionRow(
                            section = LogSection("暂无可读取的日志", "", "", 0),
                            expanded = placeholderExpanded,
                            onToggleExpanded = { placeholderExpanded = !placeholderExpanded }
                        )
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
    val currentOnSelected = androidx.compose.runtime.rememberUpdatedState(onSelected)
    AndroidView(
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp).height(40.dp),
        factory = { context ->
            Spinner(context).apply {
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        parent?.getItemAtPosition(position)?.toString()?.let {
                            currentOnSelected.value(it)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_item,
                    options
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            }
        },
        update = { spinner ->
            val selectedIndex = options.indexOf(selected).takeIf { it >= 0 } ?: 0
            val adapter = spinner.adapter as? ArrayAdapter<*>
            val needsAdapterUpdate = adapter == null || adapter.count != options.size ||
                options.indices.any { index -> adapter.getItem(index)?.toString() != options[index] }
            if (needsAdapterUpdate) {
                spinner.adapter = ArrayAdapter(
                    spinner.context,
                    android.R.layout.simple_spinner_item,
                    options
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            }
            if (spinner.selectedItemPosition != selectedIndex) {
                spinner.setSelection(selectedIndex, false)
            }
        }
    )
}

@Composable
private fun LogSectionRow(
    section: LogSection,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                    .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "▼" else "▶",
                modifier = Modifier.width(24.dp),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f)) {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${section.startedAt} · ${section.lineCount} 行",
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
                    section.content,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small).padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    // item_log_section.xml uses 12sp plus 2dp lineSpacingExtra.
                    lineHeight = 16.sp
                )
            }
        }
    }
}

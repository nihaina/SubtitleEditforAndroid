package com.subtitleedit.feature.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.util.PunctuationReplacementScope

data class SubtitleFormatEditorRow(
    val entryPosition: Int,
    val text: String,
    val selected: Boolean = true
)

data class SubtitleFormatEditorOptions(
    val removeSpaces: Boolean,
    val innerPunctuation: Set<Char>,
    val startPunctuation: Set<Char>,
    val endPunctuation: Set<Char>,
    val replaceFrom: String,
    val replaceTo: String,
    val replacementScope: PunctuationReplacementScope,
    val addEndPunctuation: String
)

private val punctuationOptions = listOf(
    '，', ',', '、', '。', '．', '.', '？', '?', '！', '!', '：', ':', '；', ';',
    '“', '”', '‘', '’', '「', '」', '『', '』', '"', '\'',
    '（', '）', '(', ')', '【', '】', '[', ']', '—', '–', '-', '…', '·'
)
private val endPunctuationDefaults = "。．.，,、？?！!：:；;…".toSet()
private val addEndPunctuationOptions = listOf("", "。", ".", "！", "!", "？", "?", "，", ",", "…")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleFormatEditorScreen(
    fileName: String,
    fileInfo: String,
    items: List<SubtitleFormatEditorRow>,
    isLoading: Boolean,
    isApplying: Boolean,
    showSaveConfirmation: Boolean,
    showDiscardConfirmation: Boolean,
    onBack: () -> Unit,
    onSaveRequest: () -> Unit,
    onConfirmSave: () -> Unit,
    onDismissSaveConfirmation: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscardConfirmation: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectRange: (Int, Int) -> Unit,
    onSelectionChanged: (Int, Boolean) -> Unit,
    onEditItem: (Int, String) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onApply: (SubtitleFormatEditorOptions) -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var removeSpaces by rememberSaveable { mutableStateOf(false) }
    var innerPunctuation by rememberSaveable { mutableStateOf("") }
    var startPunctuation by rememberSaveable { mutableStateOf("") }
    var endPunctuation by rememberSaveable { mutableStateOf(endPunctuationDefaults.joinToString("")) }
    var replaceFrom by rememberSaveable { mutableStateOf("") }
    var replaceTo by rememberSaveable { mutableStateOf("") }
    var replacementScope by rememberSaveable { mutableStateOf(PunctuationReplacementScope.INNER) }
    var addEndPunctuationIndex by rememberSaveable { mutableStateOf(0) }
    var editingPosition by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectingRange by rememberSaveable { mutableStateOf(false) }

    val options = SubtitleFormatEditorOptions(
        removeSpaces = removeSpaces,
        innerPunctuation = innerPunctuation.toSet(),
        startPunctuation = startPunctuation.toSet(),
        endPunctuation = endPunctuation.toSet(),
        replaceFrom = replaceFrom,
        replaceTo = replaceTo,
        replacementScope = replacementScope,
        addEndPunctuation = addEndPunctuationOptions[addEndPunctuationIndex]
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "字幕格式化",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSelectAll, enabled = items.isNotEmpty() && !isLoading) {
                        Icon(
                            painter = painterResource(R.drawable.ic_select_all),
                            contentDescription = if (items.isNotEmpty() && items.all { it.selected }) {
                                "取消全选"
                            } else {
                                "全选"
                            },
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = { selectingRange = true },
                        enabled = items.isNotEmpty() && !isLoading
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_select_range),
                            contentDescription = "区间选择",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onSaveRequest, enabled = !isLoading && !isApplying) {
                        Icon(
                            painter = painterResource(R.drawable.ic_save),
                            contentDescription = "保存",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(3f).fillMaxHeight().padding(horizontal = 8.dp)) {
                        FileInfo(fileInfo)
                        PreviewList(
                            items = items,
                            onSelectionChanged = onSelectionChanged,
                            onEdit = { editingPosition = it },
                            onMove = onMoveItem,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .padding(vertical = 8.dp)
                    ) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.outlineVariant) {}
                    }
                    FormattingControls(
                        modifier = Modifier.weight(2f).fillMaxHeight(),
                        removeSpaces = removeSpaces,
                        onRemoveSpacesChanged = { removeSpaces = it },
                        innerPunctuation = innerPunctuation,
                        onInnerPunctuationChanged = { innerPunctuation = it },
                        startPunctuation = startPunctuation,
                        onStartPunctuationChanged = { startPunctuation = it },
                        endPunctuation = endPunctuation,
                        onEndPunctuationChanged = { endPunctuation = it },
                        replaceFrom = replaceFrom,
                        onReplaceFromChanged = { replaceFrom = it },
                        replaceTo = replaceTo,
                        onReplaceToChanged = { replaceTo = it },
                        replacementScope = replacementScope,
                        onReplacementScopeChanged = { replacementScope = it },
                        addEndPunctuationIndex = addEndPunctuationIndex,
                        onAddEndPunctuationChanged = { addEndPunctuationIndex = it },
                        isApplying = isApplying,
                        onApply = { onApply(options) }
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    FileInfo(fileInfo)
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("预览 (${items.size})") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("格式选项") }
                        )
                    }
                    if (selectedTab == 0) {
                        PreviewList(
                            items = items,
                            onSelectionChanged = onSelectionChanged,
                            onEdit = { editingPosition = it },
                            onMove = onMoveItem,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        FormattingControls(
                            modifier = Modifier.weight(1f),
                            removeSpaces = removeSpaces,
                            onRemoveSpacesChanged = { removeSpaces = it },
                            innerPunctuation = innerPunctuation,
                            onInnerPunctuationChanged = { innerPunctuation = it },
                            startPunctuation = startPunctuation,
                            onStartPunctuationChanged = { startPunctuation = it },
                            endPunctuation = endPunctuation,
                            onEndPunctuationChanged = { endPunctuation = it },
                            replaceFrom = replaceFrom,
                            onReplaceFromChanged = { replaceFrom = it },
                            replaceTo = replaceTo,
                            onReplaceToChanged = { replaceTo = it },
                            replacementScope = replacementScope,
                            onReplacementScopeChanged = { replacementScope = it },
                            addEndPunctuationIndex = addEndPunctuationIndex,
                            onAddEndPunctuationChanged = { addEndPunctuationIndex = it },
                            isApplying = isApplying,
                            onApply = {
                                selectedTab = 0
                                onApply(options)
                            }
                        )
                    }
                }
            }
        }
    }

    editingPosition?.let { position ->
        val row = items.getOrNull(position)
        if (row == null) {
            editingPosition = null
        } else {
            EditSubtitleDialog(
                position = position,
                text = row.text,
                onDismiss = { editingPosition = null },
                onConfirm = { text ->
                    onEditItem(position, text)
                    editingPosition = null
                }
            )
        }
    }

    if (selectingRange) {
        RangeSelectionDialog(
            itemCount = items.size,
            onDismiss = { selectingRange = false },
            onConfirm = { start, end ->
                onSelectRange(start - 1, end - 1)
                selectingRange = false
            }
        )
    }

    if (showSaveConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissSaveConfirmation,
            title = { Text("保存格式化结果") },
            text = { Text("将覆盖原文件 $fileName，确定继续？") },
            confirmButton = {
                TextButton(onClick = onConfirmSave) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = onDismissSaveConfirmation) { Text("取消") }
            }
        )
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissDiscardConfirmation,
            title = { Text("放弃更改？") },
            text = { Text("尚未保存的格式化结果将丢失。") },
            confirmButton = {
                TextButton(onClick = onConfirmDiscard) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscardConfirmation) { Text("取消") }
            }
        )
    }
}

@Composable
private fun FileInfo(fileInfo: String) {
    Text(
        text = fileInfo,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis
    )
}

@Composable
private fun PreviewList(
    items: List<SubtitleFormatEditorRow>,
    onSelectionChanged: (Int, Boolean) -> Unit,
    onEdit: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        itemsIndexed(items, key = { _, item -> item.entryPosition }) { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = item.selected,
                    onCheckedChange = { onSelectionChanged(index, it) }
                )
                Text(
                    text = "${index + 1}.",
                    modifier = Modifier.width(32.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.text,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onEdit(index) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = { onMove(index, index - 1) },
                    enabled = index > 0
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_right),
                        contentDescription = "上移第 ${index + 1} 条字幕",
                        modifier = Modifier.graphicsLayer { rotationZ = -90f },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onMove(index, index + 1) },
                    enabled = index < items.lastIndex
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_right),
                        contentDescription = "下移第 ${index + 1} 条字幕",
                        modifier = Modifier.graphicsLayer { rotationZ = 90f },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FormattingControls(
    modifier: Modifier = Modifier,
    removeSpaces: Boolean,
    onRemoveSpacesChanged: (Boolean) -> Unit,
    innerPunctuation: String,
    onInnerPunctuationChanged: (String) -> Unit,
    startPunctuation: String,
    onStartPunctuationChanged: (String) -> Unit,
    endPunctuation: String,
    onEndPunctuationChanged: (String) -> Unit,
    replaceFrom: String,
    onReplaceFromChanged: (String) -> Unit,
    replaceTo: String,
    onReplaceToChanged: (String) -> Unit,
    replacementScope: PunctuationReplacementScope,
    onReplacementScopeChanged: (PunctuationReplacementScope) -> Unit,
    addEndPunctuationIndex: Int,
    onAddEndPunctuationChanged: (Int) -> Unit,
    isApplying: Boolean,
    onApply: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("清除句内内容", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = removeSpaces, onCheckedChange = onRemoveSpacesChanged)
            Text("空格", modifier = Modifier.clickable { onRemoveSpacesChanged(!removeSpaces) })
        }
        PunctuationGrid(
            selected = innerPunctuation,
            onSelectionChanged = onInnerPunctuationChanged
        )

        Text("清理句首标点", style = MaterialTheme.typography.titleSmall)
        PunctuationGrid(
            selected = startPunctuation,
            onSelectionChanged = onStartPunctuationChanged
        )

        Text("清除句末标点", style = MaterialTheme.typography.titleSmall)
        PunctuationGrid(
            selected = endPunctuation,
            onSelectionChanged = onEndPunctuationChanged
        )

        Text("标点替换", style = MaterialTheme.typography.titleSmall)
        ReplacementScopeSelector(
            selected = replacementScope,
            onSelected = onReplacementScopeChanged
        )
        OutlinedTextField(
            value = replaceFrom,
            onValueChange = onReplaceFromChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("要替换的标点") },
            singleLine = true
        )
        OutlinedTextField(
            value = replaceTo,
            onValueChange = onReplaceToChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("替换为") },
            singleLine = true
        )

        Text("添加句末标点", style = MaterialTheme.typography.titleSmall)
        EndPunctuationSelector(
            selectedIndex = addEndPunctuationIndex,
            onSelected = onAddEndPunctuationChanged
        )

        Button(
            onClick = onApply,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            enabled = !isApplying
        ) {
            Text(if (isApplying) "正在应用…" else "应用")
        }
    }
}

@Composable
private fun PunctuationGrid(
    selected: String,
    onSelectionChanged: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        punctuationOptions.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { punctuation ->
                    val isSelected = punctuation in selected
                    Surface(
                        onClick = {
                            onSelectionChanged(
                                if (isSelected) selected.filterNot { it == punctuation }
                                else selected + punctuation
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(punctuation.toString(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ReplacementScopeSelector(
    selected: PunctuationReplacementScope,
    onSelected: (PunctuationReplacementScope) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (selected == PunctuationReplacementScope.END) "句末替换" else "句内替换")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("句内替换") },
                onClick = {
                    onSelected(PunctuationReplacementScope.INNER)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("句末替换") },
                onClick = {
                    onSelected(PunctuationReplacementScope.END)
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun EndPunctuationSelector(
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedText = addEndPunctuationOptions[selectedIndex]
        .ifEmpty { "不添加" }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selectedText)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            addEndPunctuationOptions.forEachIndexed { index, punctuation ->
                DropdownMenuItem(
                    text = { Text(punctuation.ifEmpty { "不添加" }) },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun EditSubtitleDialog(
    position: Int,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var editedText by rememberSaveable(position) { mutableStateOf(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑第 ${position + 1} 条字幕") },
        text = {
            OutlinedTextField(
                value = editedText,
                onValueChange = { editedText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(editedText) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RangeSelectionDialog(
    itemCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var validationAttempted by rememberSaveable { mutableStateOf(false) }
    val startValue = start.toIntOrNull()
    val endValue = end.toIntOrNull()
    val isValid = startValue != null && endValue != null &&
        startValue in 1..itemCount && endValue in 1..itemCount && startValue <= endValue
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("区间选择（1-$itemCount）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("开始行") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = validationAttempted && !isValid,
                    supportingText = if (validationAttempted && !isValid) {
                        { Text("请输入有效的起止行号") }
                    } else {
                        null
                    },
                    singleLine = true
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("结束行") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = validationAttempted && !isValid,
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isValid) onConfirm(startValue!!, endValue!!)
                    else validationAttempted = true
                },
                enabled = true
            ) { Text("选择") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

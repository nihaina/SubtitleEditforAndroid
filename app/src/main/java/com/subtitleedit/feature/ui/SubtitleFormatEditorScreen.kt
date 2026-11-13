package com.subtitleedit.feature.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.MaterialSettingsTileTheme
import com.subtitleedit.ui.settings.settingsChipColor
import com.subtitleedit.ui.settings.settingsOutlinedTextFieldColors
import com.subtitleedit.ui.settings.settingsPrimaryTextColor
import com.subtitleedit.ui.settings.settingsSecondaryTextColor
import com.subtitleedit.ui.settings.settingsTileColor
import com.subtitleedit.util.AsrPunctuationOptions
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

private val punctuationOptions = AsrPunctuationOptions.choices.map { it.single() }
private const val boundaryPunctuationDefaults = "。．.，,、？?！!：:；;…"
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
    onInvalidRange: () -> Unit,
    onApply: (SubtitleFormatEditorOptions) -> Unit
) {
    var removeSpaces by rememberSaveable { mutableStateOf(false) }
    var innerPunctuation by rememberSaveable { mutableStateOf("") }
    var startPunctuation by rememberSaveable { mutableStateOf(boundaryPunctuationDefaults) }
    var endPunctuation by rememberSaveable { mutableStateOf(boundaryPunctuationDefaults) }
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

    val pageBackground = MaterialTheme.colorScheme.background
    MaterialSettingsTileTheme {
        AppToolScaffold(
            title = stringResource(R.string.subtitle_format_editor_title),
            onBack = onBack,
            scrollable = false,
            contentBackground = pageBackground,
            topBarContainerColor = pageBackground,
            topBarScrolledContainerColor = pageBackground,
            titleContent = {
                Column {
                    Text(
                        text = stringResource(R.string.subtitle_format_editor_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                }
            },
            actions = {
                IconButton(onClick = onSelectAll, enabled = items.isNotEmpty() && !isLoading) {
                    Icon(
                        painter = painterResource(R.drawable.ic_select_all),
                        contentDescription = stringResource(R.string.menu_subtitle_format_editor_title_01),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = { selectingRange = true },
                    enabled = items.isNotEmpty() && !isLoading
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_select_range),
                        contentDescription = stringResource(R.string.menu_subtitle_format_editor_title_02),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AppTaskProgress(visible = true, progress = null)
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(3f).fillMaxHeight().padding(8.dp)) {
                            FileInfo(fileInfo)
                            SubtitleFormatPreviewList(
                                items = items,
                                onSelectionChanged = onSelectionChanged,
                                onEdit = { editingPosition = it },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Surface(
                            Modifier.fillMaxHeight().width(1.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        ) {}
                        FormattingControls(
                            modifier = Modifier.weight(2.5f).fillMaxHeight(),
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
                            onApply = { onApply(options) },
                            onSave = onSaveRequest
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
            onInvalid = {
                onInvalidRange()
                // The legacy AlertDialog dismissed itself after an invalid submit.
                selectingRange = false
            },
            onConfirm = { start, end ->
                onSelectRange(start - 1, end - 1)
                selectingRange = false
            }
        )
    }

    if (showSaveConfirmation) {
        AppAlertDialog(
            title = stringResource(R.string.subtitle_format_save_title),
            message = stringResource(R.string.subtitle_format_save_message, fileName),
            confirmText = stringResource(R.string.save),
            dismissText = stringResource(R.string.cancel),
            onConfirm = onConfirmSave,
            onDismiss = onDismissSaveConfirmation
        )
    }

    if (showDiscardConfirmation) {
        AppAlertDialog(
            title = stringResource(R.string.discard_changes_title),
            message = stringResource(R.string.discard_changes_message),
            confirmText = stringResource(R.string.discard),
            dismissText = stringResource(R.string.cancel),
            onConfirm = onConfirmDiscard,
            onDismiss = onDismissDiscardConfirmation
        )
    }
}

@Composable
private fun FileInfo(fileInfo: String) {
    Text(
        text = fileInfo,
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = settingsSecondaryTextColor(),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis
    )
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
    onApply: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(settingsTileColor())
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(R.string.activity_subtitle_format_editor_text_01),
            style = MaterialTheme.typography.titleMedium,
            color = settingsPrimaryTextColor()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = removeSpaces, onCheckedChange = onRemoveSpacesChanged)
            Text(
                stringResource(R.string.activity_subtitle_format_editor_text_02),
                modifier = Modifier.clickable { onRemoveSpacesChanged(!removeSpaces) },
                color = settingsPrimaryTextColor()
            )
        }
        PunctuationGrid(
            selected = innerPunctuation,
            onSelectionChanged = onInnerPunctuationChanged
        )

        Text(
            stringResource(R.string.subtitle_format_clear_start_punctuation),
            style = MaterialTheme.typography.titleMedium,
            color = settingsPrimaryTextColor()
        )
        PunctuationGrid(
            selected = startPunctuation,
            onSelectionChanged = onStartPunctuationChanged
        )

        Text(
            stringResource(R.string.activity_subtitle_format_editor_text_03),
            style = MaterialTheme.typography.titleMedium,
            color = settingsPrimaryTextColor()
        )
        PunctuationGrid(
            selected = endPunctuation,
            onSelectionChanged = onEndPunctuationChanged
        )

        Text(
            stringResource(R.string.activity_subtitle_format_editor_text_04),
            style = MaterialTheme.typography.titleMedium,
            color = settingsPrimaryTextColor()
        )
        ReplacementScopeSelector(
            selected = replacementScope,
            onSelected = onReplacementScopeChanged
        )
        OutlinedTextField(
            value = replaceFrom,
            onValueChange = onReplaceFromChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.activity_subtitle_format_editor_hint_01)) },
            colors = settingsOutlinedTextFieldColors(),
            singleLine = true
        )
        OutlinedTextField(
            value = replaceTo,
            onValueChange = onReplaceToChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.activity_editor_hint_01)) },
            colors = settingsOutlinedTextFieldColors(),
            singleLine = true
        )

        Text(
            stringResource(R.string.activity_subtitle_format_editor_text_05),
            style = MaterialTheme.typography.titleMedium,
            color = settingsPrimaryTextColor()
        )
        EndPunctuationSelector(
            selectedIndex = addEndPunctuationIndex,
            onSelected = onAddEndPunctuationChanged
        )

        Button(
            onClick = onApply,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = !isApplying,
            shape = CircleShape
        ) {
            Text(
                if (isApplying) stringResource(R.string.applying)
                else stringResource(R.string.activity_subtitle_format_editor_text_06),
                style = MaterialTheme.typography.labelLarge
            )
        }
        OutlinedButton(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = CircleShape
        ) {
            Text(stringResource(R.string.menu_save), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PunctuationGrid(
    selected: String,
    onSelectionChanged: (String) -> Unit
) {
    val chipColor = settingsChipColor()
    Column(
        modifier = Modifier.padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        punctuationOptions.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
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
                            .height(38.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            chipColor
                        },
                        contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            settingsPrimaryTextColor()
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
    onSelected: (PunctuationReplacementScope) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Text(
                if (selected == PunctuationReplacementScope.END) {
                    stringResource(R.string.punctuation_scope_end)
                } else {
                    stringResource(R.string.punctuation_scope_inner)
                }
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.punctuation_scope_inner)) },
                onClick = {
                    onSelected(PunctuationReplacementScope.INNER)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.punctuation_scope_end)) },
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
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedText = addEndPunctuationOptions[selectedIndex]
        .ifEmpty { stringResource(R.string.punctuation_none) }
    Box(modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Text(selectedText)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            addEndPunctuationOptions.forEachIndexed { index, punctuation ->
                DropdownMenuItem(
                    text = { Text(punctuation.ifEmpty { stringResource(R.string.punctuation_none) }) },
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
    // The legacy EditText placed the cursor at the end when the dialog opened.
    // Keep that initial editing position while retaining Compose state across
    // recompositions and configuration changes.
    val editedState = rememberSaveable(position, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_subtitle_number, position + 1)) },
        text = {
            OutlinedTextField(
                value = editedState.value,
                onValueChange = { editedState.value = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(editedState.value.text) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun RangeSelectionDialog(
    itemCount: Int,
    onDismiss: () -> Unit,
    onInvalid: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var validationAttempted by rememberSaveable { mutableStateOf(false) }
    val startValue = start.toIntOrNull()
    val endValue = end.toIntOrNull()
    val isValid = startValue != null && endValue != null &&
        startValue in 1..itemCount && endValue in 1..itemCount && startValue <= endValue
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.range_selection_title, itemCount)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.range_start_line)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = validationAttempted && !isValid,
                    supportingText = if (validationAttempted && !isValid) {
                        { Text(stringResource(R.string.range_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.range_end_line)) },
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
                    else {
                        validationAttempted = true
                        onInvalid()
                    }
                },
                enabled = true
            ) { Text(stringResource(R.string.select)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

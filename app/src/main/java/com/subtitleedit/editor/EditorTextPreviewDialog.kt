package com.subtitleedit.editor

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.ComposeDialogHost
import com.subtitleedit.R
import com.subtitleedit.adapter.TranslationPreviewItem
import com.subtitleedit.ui.DraggableScrollbar
import kotlinx.coroutines.launch

/** AI translation, quick transcription and subtitle merge share this result preview. */
internal class EditorTextPreviewDialog(private val activity: Activity) {
    /**
     * @param onApply Receives only the checked items.
     * @param onNeutral Receives every item; omitted when the neutral action is not provided.
     */
    fun show(
        title: String,
        editTitle: String,
        previewItems: List<TranslationPreviewItem>,
        onApply: (List<TranslationPreviewItem>) -> Unit,
        neutralButtonText: String? = null,
        onNeutral: ((List<TranslationPreviewItem>) -> Unit)? = null,
        suspectedProblem: ((TranslationPreviewItem) -> Boolean)? = null
    ) {
        val applyStates = previewItems.map { mutableStateOf(it.apply) }
        val translatedStates = previewItems.map { mutableStateOf(it.translatedText) }
        val problemRevision = mutableIntStateOf(0)
        val showProblems = mutableStateOf(false)
        val editingIndex = mutableIntStateOf(-1)
        val editingText = mutableStateOf(TextFieldValue())

        ComposeDialogHost.show(activity) { dialog ->
            val problemIndices = remember(problemRevision.value) {
                previewItems.indices.filter { index ->
                    suspectedProblem?.invoke(previewItems[index]) == true
                }
            }
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val maxListHeight = LocalConfiguration.current.screenHeightDp.dp * 0.55f

            AlertDialog(
                onDismissRequest = dialog::dismiss,
                // Match the legacy window sizing (96% width, 82% height).
                modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.82f),
                properties = DialogProperties(usePlatformDefaultWidth = false),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (problemIndices.isNotEmpty()) {
                            TextButton(
                                onClick = { showProblems.value = true },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = activity.getString(R.string.translation_preview_suspect_warning),
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                },
                text = {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            itemsIndexed(previewItems) { index, item ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .semantics { contentDescription = "应用此行翻译" },
                                            checked = applyStates[index].value,
                                            onCheckedChange = { checked ->
                                                item.apply = checked
                                                applyStates[index].value = checked
                                            }
                                        )
                                        Text(
                                            text = "${index + 1}. ${item.originalText}",
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Text(
                                        text = translatedStates[index].value,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 40.dp, top = 2.dp, end = 0.dp)
                                            .clickable {
                                                val text = translatedStates[index].value
                                                editingText.value = TextFieldValue(
                                                    text = text,
                                                    selection = TextRange(text.length)
                                                )
                                                editingIndex.value = index
                                            },
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                        DraggableScrollbar(
                            state = listState,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            fixedThumbSize = true
                        )
                    }
                },
                confirmButton = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (neutralButtonText != null && onNeutral != null) {
                            TextButton(
                                onClick = {
                                    dialog.dismiss()
                                    onNeutral(previewItems)
                                }
                            ) { Text(neutralButtonText) }
                        }
                        Button(
                            onClick = {
                                dialog.dismiss()
                                onApply(previewItems.filter { it.apply })
                            }
                        ) { Text("应用") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text("取消") }
                }
            )

            if (showProblems.value && problemIndices.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = { showProblems.value = false },
                    title = { Text(stringResource(R.string.translation_preview_suspect_dialog_title)) },
                    text = {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            itemsIndexed(problemIndices) { _, previewIndex ->
                                TextButton(
                                    onClick = {
                                        showProblems.value = false
                                        scope.launch { listState.animateScrollToItem(previewIndex) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = activity.getString(
                                            R.string.translation_preview_suspect_row,
                                            previewIndex + 1
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showProblems.value = false }) { Text("关闭") }
                    }
                )
            }

            val index = editingIndex.value
            if (index in previewItems.indices) {
                val focusRequester = remember(index) { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current
                LaunchedEffect(index) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
                AlertDialog(
                    onDismissRequest = { editingIndex.value = -1 },
                    title = { Text(editTitle) },
                    text = {
                        OutlinedTextField(
                            value = editingText.value,
                            onValueChange = { editingText.value = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            minLines = 3
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val item = previewItems[index]
                                item.translatedText = editingText.value.text
                                translatedStates[index].value = editingText.value.text
                                if (suspectedProblem != null) {
                                    item.suspectedProblem = item.translatedText.isBlank()
                                }
                                problemRevision.value++
                                editingIndex.value = -1
                            }
                        ) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = { editingIndex.value = -1 }) { Text("取消") }
                    }
                )
            }
        }
    }
}

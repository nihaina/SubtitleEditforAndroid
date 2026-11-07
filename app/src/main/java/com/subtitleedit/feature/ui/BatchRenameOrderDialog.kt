package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.subtitleedit.R
import com.subtitleedit.model.FileSortField
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.util.BatchRenameOrder
import java.io.File
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

@Composable
internal fun BatchRenameOrderDialog(
    sources: List<File>,
    onDismiss: () -> Unit,
    onConfirm: (List<File>) -> Unit
) {
    var savedPaths by rememberSaveable { mutableStateOf(sources.map { it.absolutePath }) }
    var reversed by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val ordered = BatchRenameOrder.restore(sources, savedPaths)
    val listState = rememberLazyListState()
    val dragState = remember(listState, sources) {
        RenameOrderDragState(listState, order = { BatchRenameOrder.restore(sources, savedPaths) }) { from, to ->
            savedPaths = BatchRenameOrder.move(BatchRenameOrder.restore(sources, savedPaths), from, to).map { it.absolutePath }
        }
    }
    val edgeSize = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(dragState.draggedPath) {
        if (dragState.draggedPath == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (coroutineContext.isActive && dragState.draggedPath != null) {
            val frame = withFrameNanos { it }
            val frameSeconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previousFrame = frame
            val scroll = dragState.edgeScroll(edgeSize) * edgeSize * 14f * frameSeconds
            if (scroll != 0f && listState.scrollBy(scroll) != 0f) dragState.reorder(scroll)
        }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.batch_rename_sort_title), modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.batch_rename_sort_options))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        listOf(
                            FileSortField.NAME to R.string.batch_rename_sort_name,
                            FileSortField.DATE to R.string.batch_rename_sort_date,
                            FileSortField.SIZE to R.string.batch_rename_sort_size,
                            FileSortField.TYPE to R.string.batch_rename_sort_type
                        ).forEach { (field, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    dragState.stop()
                                    savedPaths = BatchRenameOrder.sort(ordered, field, reversed).map { it.absolutePath }
                                    menuExpanded = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.batch_rename_reverse)) },
                            trailingIcon = { Checkbox(checked = reversed, onCheckedChange = null) },
                            onClick = {
                                dragState.stop()
                                reversed = !reversed
                                savedPaths = ordered.asReversed().map { it.absolutePath }
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                state = listState,
                userScrollEnabled = dragState.draggedPath == null
            ) {
                items(ordered, key = { it.absolutePath }) { file ->
                    val dragging = file.absolutePath == dragState.draggedPath
                    val upLabel = stringResource(R.string.batch_rename_move_up)
                    val downLabel = stringResource(R.string.batch_rename_move_down)
                    val handleLabel = stringResource(R.string.batch_rename_drag_handle, file.name)
                    val rowIndex = ordered.indexOf(file)
                    RenameFileRow(
                        file = file,
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = dragState.translation(file.absolutePath) }
                            .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .semantics {
                                    contentDescription = handleLabel
                                    customActions = buildList {
                                        if (rowIndex > 0) add(CustomAccessibilityAction(upLabel) {
                                            savedPaths = BatchRenameOrder.move(ordered, rowIndex, rowIndex - 1).map { it.absolutePath }
                                            true
                                        })
                                        if (rowIndex < ordered.lastIndex) add(CustomAccessibilityAction(downLabel) {
                                            savedPaths = BatchRenameOrder.move(ordered, rowIndex, rowIndex + 1).map { it.absolutePath }
                                            true
                                        })
                                    }
                                }
                                .pointerInput(dragState, file.absolutePath) {
                                    detectDragGestures(
                                        onDragStart = { dragState.start(file.absolutePath) },
                                        onDragEnd = dragState::stop,
                                        onDragCancel = dragState::stop,
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragState.drag(amount.y)
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(painterResource(R.drawable.ic_batch_rename_drag), contentDescription = null)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(ordered) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Keeps the dragged row under the finger while LazyColumn changes its layout and scroll position. */
private class RenameOrderDragState(
    private val listState: LazyListState,
    private val order: () -> List<File>,
    private val onMove: (Int, Int) -> Unit
) {
    var draggedPath by mutableStateOf<String?>(null)
        private set
    private var centerY by mutableFloatStateOf(0f)

    fun start(path: String) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == path } ?: return
        centerY = item.offset + item.size / 2f
        draggedPath = path
    }

    fun stop() { draggedPath = null }

    fun drag(delta: Float) {
        if (draggedPath == null) return
        centerY += delta
        reorder(delta)
    }

    fun translation(path: String): Float {
        if (draggedPath != path) return 0f
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == path } ?: return 0f
        return centerY - item.offset - item.size / 2f
    }

    fun reorder(direction: Float) {
        val path = draggedPath ?: return
        val files = order()
        val from = files.indexOfFirst { it.absolutePath == path }
        if (from < 0) return
        val candidates = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val index = files.indexOfFirst { it.absolutePath == item.key }
            val midpoint = item.offset + item.size / 2f
            if (index < 0 || item.key == path) null else Triple(index, midpoint, item)
        }
        val target = if (direction >= 0f) {
            candidates.filter { it.first > from && centerY >= it.second }.maxByOrNull { it.second }
        } else {
            candidates.filter { it.first < from && centerY <= it.second }.minByOrNull { it.second }
        }
        target?.let { onMove(from, it.first) }
    }

    fun edgeScroll(edgeSize: Float): Float {
        if (draggedPath == null) return 0f
        val layout = listState.layoutInfo
        return when {
            centerY < layout.viewportStartOffset + edgeSize ->
                ((centerY - layout.viewportStartOffset - edgeSize) / edgeSize).coerceAtLeast(-1f)
            centerY > layout.viewportEndOffset - edgeSize ->
                ((centerY - layout.viewportEndOffset + edgeSize) / edgeSize).coerceAtMost(1f)
            else -> 0f
        }
    }
}

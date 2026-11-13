package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.subtitleedit.MediaMuxColumn
import com.subtitleedit.MediaMuxRow
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.theme.AppSpacing
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

private data class MediaMuxOrderSlot(val key: String, val name: String?)

/** Keeps empty positions explicit so moving one column can change pairings without moving the other. */
@Composable
internal fun MediaMuxOrderDialog(
    rows: List<MediaMuxRow>,
    column: MediaMuxColumn,
    onDismiss: () -> Unit,
    onMove: (Int, Int) -> Unit
) {
    var slots by remember(column) {
        mutableStateOf(rows.map { row ->
            val file = if (column == MediaMuxColumn.VIDEO) row.video else row.audio
            MediaMuxOrderSlot(file?.id ?: "empty:${row.id}", file?.fileName)
        })
    }
    val moveCallback by rememberUpdatedState(onMove)
    val listState = rememberLazyListState()
    val latestSlots by rememberUpdatedState(slots)
    fun move(from: Int, to: Int) {
        if (from !in slots.indices || to !in slots.indices || from == to) return
        slots = slots.toMutableList().apply { add(to, removeAt(from)) }
        moveCallback(from, to)
    }
    val moveAction by rememberUpdatedState<(Int, Int) -> Unit>(::move)
    val dragState = remember(listState) {
        MediaMuxOrderDragState(listState, { latestSlots.map { it.key } }) { from, to -> moveAction(from, to) }
    }
    val edgeSize = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(dragState.draggedKey) {
        if (dragState.draggedKey == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (coroutineContext.isActive && dragState.draggedKey != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
            previousFrame = frame
            val distance = dragState.edgeScroll(edgeSize) * edgeSize * 14f * seconds
            if (distance != 0f && listState.scrollBy(distance) != 0f) dragState.reorder(distance)
        }
    }
    fun applyOrder(target: List<MediaMuxOrderSlot>) {
        dragState.stop()
        target.forEachIndexed { index, slot ->
            val from = slots.indexOfFirst { it.key == slot.key }
            if (from != index) move(from, index)
        }
    }
    val title = stringResource(if (column == MediaMuxColumn.VIDEO) R.string.media_mux_video else R.string.media_mux_audio)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_mux_order_title, title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                Text(stringResource(R.string.media_mux_order_hint), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { applyOrder(slots.sortedWith(compareBy<MediaMuxOrderSlot> { it.name == null }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })) },
                        modifier = Modifier.testTag("mux-order-sort-name")) { Text(stringResource(R.string.media_mux_sort_name)) }
                    TextButton(onClick = { applyOrder(slots.asReversed()) }, modifier = Modifier.testTag("mux-order-reverse")) {
                        Text(stringResource(R.string.media_mux_sort_reverse))
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("mux-order-list"),
                    state = listState, verticalArrangement = Arrangement.spacedBy(6.dp),
                    userScrollEnabled = dragState.draggedKey == null) {
                    itemsIndexed(slots, key = { _, slot -> slot.key }) { index, slot ->
                        val dragging = dragState.draggedKey == slot.key
                        val moveUp = stringResource(R.string.media_mux_move_up)
                        val moveDown = stringResource(R.string.media_mux_move_down)
                        val blank = slot.name == null
                        val name = slot.name ?: stringResource(R.string.media_mux_row_blank)
                        val handleLabel = stringResource(R.string.media_mux_drag, name)
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = dragState.translation(slot.key) }
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (dragging) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(1.dp, if (dragging) MaterialTheme.colorScheme.primary.copy(alpha = .55f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f),
                                MaterialTheme.shapes.medium)
                            .testTag("mux-order-slot-${slot.key}")
                            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                                Text(stringResource(R.string.media_mux_row, index + 1), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                    color = if (blank) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(onClick = { dragState.stop(); move(index, index - 1) }, enabled = index > 0,
                                modifier = Modifier.size(36.dp).testTag("mux-order-up-${slot.key}")) {
                                Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = moveUp,
                                    modifier = Modifier.size(20.dp).graphicsLayer(rotationZ = -90f))
                            }
                            IconButton(onClick = { dragState.stop(); move(index, index + 1) }, enabled = index < slots.lastIndex,
                                modifier = Modifier.size(36.dp).testTag("mux-order-down-${slot.key}")) {
                                Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = moveDown,
                                    modifier = Modifier.size(20.dp).graphicsLayer(rotationZ = 90f))
                            }
                            Box(Modifier.size(44.dp).testTag("mux-order-drag-${slot.key}")
                                .semantics {
                                    contentDescription = handleLabel
                                    customActions = buildList {
                                        if (index > 0) add(CustomAccessibilityAction(moveUp) { move(index, index - 1); true })
                                        if (index < slots.lastIndex) add(CustomAccessibilityAction(moveDown) { move(index, index + 1); true })
                                    }
                                }
                                .pointerInput(dragState, slot.key) {
                                    detectDragGestures(onDragStart = { dragState.start(slot.key) }, onDragEnd = dragState::stop,
                                        onDragCancel = dragState::stop, onDrag = { change, amount -> change.consume(); dragState.drag(amount.y) })
                                }, contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_batch_rename_drag), contentDescription = null)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("mux-order-done")) {
            Text(stringResource(R.string.media_mux_order_done))
        } }
    )
}

/** Same center-point/edge-scroll behavior as the batch rename order dialog. */
private class MediaMuxOrderDragState(private val listState: LazyListState, private val order: () -> List<String>,
    private val onMove: (Int, Int) -> Unit) {
    var draggedKey by mutableStateOf<String?>(null)
        private set
    private var centerY by mutableFloatStateOf(0f)
    fun start(key: String) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        centerY = item.offset + item.size / 2f
        draggedKey = key
    }
    fun stop() { draggedKey = null }
    fun drag(delta: Float) {
        if (draggedKey == null) return
        centerY += delta
        reorder(delta)
    }
    fun translation(key: String): Float {
        if (draggedKey != key) return 0f
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return centerY - item.offset - item.size / 2f
    }
    fun reorder(direction: Float) {
        val key = draggedKey ?: return
        val slots = order()
        val from = slots.indexOf(key)
        if (from < 0) return
        val candidates = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val index = slots.indexOfFirst { it == item.key }
            if (index < 0 || item.key == key) null else index to item.offset + item.size / 2f
        }
        val target = if (direction >= 0f) candidates.filter { it.first > from && centerY >= it.second }.maxByOrNull { it.second }
            else candidates.filter { it.first < from && centerY <= it.second }.minByOrNull { it.second }
        target?.let { onMove(from, it.first) }
    }
    fun edgeScroll(size: Float): Float {
        if (draggedKey == null) return 0f
        val layout = listState.layoutInfo
        return when {
            centerY < layout.viewportStartOffset + size -> ((centerY - layout.viewportStartOffset - size) / size).coerceAtLeast(-1f)
            centerY > layout.viewportEndOffset - size -> ((centerY - layout.viewportEndOffset + size) / size).coerceAtMost(1f)
            else -> 0f
        }
    }
}

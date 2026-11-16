package com.subtitleedit.player

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.sign

/** The stable queue key stays attached to the finger while rows are reordered. */
internal class PlaybackQueueDragState(
    private val list: LazyListState,
    private val onMove: (String, String) -> Unit
) {
    var path by mutableStateOf<String?>(null)
        private set
    private var pointerY by mutableFloatStateOf(0f)
    private var grabbedOffset = 0f
    private var draggedItemHeight = 0
    private var lastTarget: String? = null
    private var lastDirection = 0f
    private data class PendingMove(val key: String, val previousKeys: List<String>, val firstIndex: Int, val offset: Int)
    private var pendingMove: PendingMove? = null
    private var composedKeys = emptyList<String>()

    fun start(y: Float) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: return
        path = item.key as? String ?: return
        pointerY = y
        grabbedOffset = y - item.offset
        draggedItemHeight = item.size
        lastTarget = null
        lastDirection = 0f
        pendingMove = null
    }

    fun drag(deltaY: Float) {
        if (path == null) return
        pointerY += deltaY
        if (deltaY != 0f && deltaY.sign != lastDirection) lastTarget = null
        if (deltaY != 0f) lastDirection = deltaY.sign
        moveAcrossRows()
    }

    private fun moveAcrossRows() {
        val dragged = path ?: return
        if (pendingMove != null) return
        val info = list.layoutInfo
        // The held item may be clipped out of layout while the finger is beyond an edge.
        val center = pointerY - grabbedOffset + draggedItemHeight / 2f
        val target = info.visibleItemsInfo.firstOrNull { center >= it.offset && center < it.offset + it.size }
            ?: when {
                center < info.viewportStartOffset -> info.visibleItemsInfo.firstOrNull()
                center > info.viewportEndOffset -> info.visibleItemsInfo.lastOrNull()
                else -> null
            }
        val targetPath = target?.key as? String ?: return
        if (targetPath != dragged && targetPath != lastTarget) {
            lastTarget = targetPath
            // Reordering the first visible key must not make LazyColumn follow that key
            // to its new index and scroll against the finger. Only edge scrolling moves
            // the viewport during a drag.
            pendingMove = PendingMove(dragged, composedKeys, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            onMove(dragged, targetPath)
        }
    }

    fun onQueueComposed(keys: List<String>) {
        composedKeys = keys
        val pending = pendingMove ?: return
        val index = keys.indexOf(pending.key)
        if (index < 0) pendingMove = null
        else if (keys != pending.previousKeys) {
            list.requestScrollToItem(pending.firstIndex, pending.offset)
            pendingMove = null
        }
    }

    fun translationFor(itemPath: String): Float {
        if (path != itemPath) return 0f
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemPath } ?: return 0f
        return pointerY - grabbedOffset - item.offset
    }

    suspend fun scrollAtEdge(edgeSize: Float) {
        // scrollBy can force a synchronous remeasure. Wait until the new item order
        // is composed before applying its viewport request or scrolling again.
        if (path == null || pendingMove != null) return
        val info = list.layoutInfo
        val above = pointerY - info.viewportStartOffset - edgeSize
        val below = pointerY - info.viewportEndOffset + edgeSize
        val amount = when {
            above < 0f -> -minOf(abs(above) / edgeSize, 1.5f) * 18f
            below > 0f -> minOf(below / edgeSize, 1.5f) * 18f
            else -> 0f
        }
        if (amount != 0f) {
            list.scrollBy(amount)
            moveAcrossRows()
        }
    }

    fun stop() {
        path = null
        lastTarget = null
        lastDirection = 0f
    }
}

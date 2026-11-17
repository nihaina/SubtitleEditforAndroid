package com.subtitleedit.feature.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import com.subtitleedit.model.FileBrowserViewMode
import java.io.File
import kotlinx.coroutines.flow.first

/** Locating a file is distinct from restoring ordinary directory scroll positions. */
@Composable
internal fun rememberDirectoryRevealHighlight(
    state: DirectoryBrowserState,
    isActive: Boolean,
    scrollReady: Boolean,
    listState: LazyListState,
    gridState: LazyGridState,
    onFinished: (Long) -> Unit
): Pair<Float, Int> {
    val opacity = remember { Animatable(0f) }
    var pulse by remember { mutableIntStateOf(0) }
    val finished by rememberUpdatedState(onFinished)
    val files by rememberUpdatedState(state.files)
    val request = state.directoryRevealRequest
    val grid = state.viewMode == FileBrowserViewMode.GRID
    val targetPresent = request?.let { target -> state.files.any { it.absolutePath == target.path } } == true
    LaunchedEffect(request?.token, state.currentDirectory, targetPresent, state.isDirectoryLoading,
        state.isDirectoryNavigationPending, grid, isActive, scrollReady) {
        opacity.snapTo(0f)
        pulse = 0
        val target = request?.takeIf { isActive && scrollReady && !state.isDirectoryLoading &&
            !state.isDirectoryNavigationPending } ?: return@LaunchedEffect
        if (File(target.path).parentFile?.absolutePath != state.currentDirectory?.absolutePath) return@LaunchedEffect
        if (!targetPresent) {
            finished(target.token)
            return@LaunchedEffect
        }
        try {
            withFrameNanos { }
            val index = files.indexOfFirst { it.absolutePath == target.path }
            if (index < 0) return@LaunchedEffect
            if (grid) gridState.scrollToItem(index) else listState.scrollToItem(index)
            snapshotFlow {
                if (grid) {
                    val layout = gridState.layoutInfo
                    layout.visibleItemsInfo.any { it.key == target.path && it.offset.y < layout.viewportEndOffset &&
                        it.offset.y + it.size.height > layout.viewportStartOffset }
                } else {
                    val layout = listState.layoutInfo
                    layout.visibleItemsInfo.any { it.key == target.path && it.offset < layout.viewportEndOffset &&
                        it.offset + it.size > layout.viewportStartOffset }
                }
            }.first { it }
            repeat(3) { index ->
                pulse = index + 1
                opacity.animateTo(1f, tween(450))
                opacity.animateTo(0f, keyframes {
                    durationMillis = 600
                    1f at 150
                    0f at 600
                })
            }
            finished(target.token)
        } finally {
            pulse = 0
            opacity.snapTo(0f)
        }
    }
    return opacity.value to pulse
}

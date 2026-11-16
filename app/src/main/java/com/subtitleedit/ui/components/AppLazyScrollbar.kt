package com.subtitleedit.ui.components

import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Only the edge rail handles pointer input; normal list scrolling and row actions stay intact. */
@Composable
internal fun AppLazyListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val indicator = state.scrollIndicatorState ?: return
    AppLazyScrollbar(
        indicator = indicator,
        canScrollBackward = state.canScrollBackward,
        canScrollForward = state.canScrollForward,
        reverseLayout = state.layoutInfo.reverseLayout,
        modifier = modifier,
        scrollToFraction = { fraction ->
            val info = state.layoutInfo
            val count = info.totalItemsCount
            if (count > 0) {
                when {
                    fraction <= 0f -> state.requestScrollToItem(0)
                    // The last row itself may exceed the viewport; let the layout clamp an
                    // oversized positive offset to the actual end instead of its top edge.
                    fraction >= 1f -> state.requestScrollToItem(count - 1, indicator.contentSize.coerceAtLeast(0))
                    else -> {
                        val content = indicator.contentSize.toFloat()
                        val extent = ((content - info.beforeContentPadding - info.afterContentPadding +
                            info.mainAxisItemSpacing) / count).coerceAtLeast(1f)
                        val target = fraction * (content - indicator.viewportSize).coerceAtLeast(0f)
                        val index = floor(target / extent).toInt().coerceIn(0, count - 1)
                        state.requestScrollToItem(index, (target - index * extent).roundToInt().coerceAtLeast(0))
                    }
                }
            }
        }
    )
}

/** Grid progress is measured in rows, so changing the number of columns keeps the rail useful. */
@Composable
internal fun AppLazyGridScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    val indicator = state.scrollIndicatorState ?: return
    AppLazyScrollbar(
        indicator = indicator,
        canScrollBackward = state.canScrollBackward,
        canScrollForward = state.canScrollForward,
        reverseLayout = state.layoutInfo.reverseLayout,
        modifier = modifier,
        scrollToFraction = { fraction ->
            val info = state.layoutInfo
            val count = info.totalItemsCount
            if (count > 0) {
                when {
                    fraction <= 0f -> state.requestScrollToItem(0)
                    fraction >= 1f -> state.requestScrollToItem(count - 1, indicator.contentSize.coerceAtLeast(0))
                    else -> {
                        val columns = info.maxSpan.coerceAtLeast(1)
                        val rows = ceil(count.toDouble() / columns).toInt().coerceAtLeast(1)
                        val content = indicator.contentSize.toFloat()
                        val extent = ((content - info.beforeContentPadding - info.afterContentPadding +
                            info.mainAxisItemSpacing) / rows).coerceAtLeast(1f)
                        val target = fraction * (content - indicator.viewportSize).coerceAtLeast(0f)
                        val row = floor(target / extent).toInt().coerceIn(0, rows - 1)
                        state.requestScrollToItem((row * columns).coerceAtMost(count - 1),
                            (target - row * extent).roundToInt().coerceAtLeast(0))
                    }
                }
            }
        }
    )
}

@Composable
private fun AppLazyScrollbar(
    indicator: ScrollIndicatorState,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    reverseLayout: Boolean,
    modifier: Modifier,
    scrollToFraction: (Float) -> Unit,
) {
    if (!canScrollBackward && !canScrollForward) return
    val contentSize = indicator.contentSize
    val viewportSize = indicator.viewportSize
    if (contentSize <= 0 || viewportSize <= 0) return
    val progress = when {
        !canScrollBackward -> 0f
        !canScrollForward -> 1f
        else -> indicator.scrollOffset.toFloat() / (contentSize - viewportSize).coerceAtLeast(1)
    }.coerceIn(0f, 1f).let { if (reverseLayout) 1f - it else it }
    val visibleFraction = (viewportSize.toFloat() / contentSize).coerceIn(0f, 1f)
    val description = stringResource(R.string.app_scrollbar_description)
    val density = LocalDensity.current
    val currentScroll by rememberUpdatedState(scrollToFraction)
    val currentProgress by rememberUpdatedState(progress)
    val currentVisibleFraction by rememberUpdatedState(visibleFraction)
    var dragging by remember { mutableStateOf(false) }
    var draggedProgress by remember { mutableFloatStateOf(progress) }
    var draggedVisibleFraction by remember { mutableFloatStateOf(visibleFraction) }
    val thumbAlpha = remember { Animatable(AppScrollbarStyle.VisibleAlpha / 255f) }
    LaunchedEffect(indicator.scrollOffset, contentSize, viewportSize, dragging) {
        thumbAlpha.snapTo(AppScrollbarStyle.VisibleAlpha / 255f)
        if (!dragging) {
            delay(AppScrollbarStyle.FadeDelayMs)
            thumbAlpha.animateTo(0f, tween(AppScrollbarStyle.FadeDurationMs, easing = LinearEasing))
        }
    }
    val thumbColor = Color(AppScrollbarStyle.ThumbColorArgb).copy(alpha = 1f)

    BoxWithConstraints(
        modifier = modifier.width(AppScrollbarStyle.TouchWidth.dp).fillMaxHeight()
            .testTag("app-lazy-scrollbar")
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                setProgress { value ->
                    currentScroll(if (reverseLayout) 1f - value.coerceIn(0f, 1f) else value.coerceIn(0f, 1f))
                    true
                }
            }
    ) {
        val trackPx = with(density) { maxHeight.toPx() }
        val minThumbPx = with(density) { AppScrollbarStyle.MinThumbHeight.dp.toPx() }
        val displayedFraction = if (dragging) draggedVisibleFraction else visibleFraction
        val thumbPx = (trackPx * displayedFraction).coerceIn(minThumbPx.coerceAtMost(trackPx), trackPx)
        val travelPx = (trackPx - thumbPx).coerceAtLeast(0f)
        val thumbTop = travelPx * if (dragging) draggedProgress else progress

        Box(
            Modifier.width(AppScrollbarStyle.TouchWidth.dp).fillMaxHeight().pointerInput(trackPx, minThumbPx, reverseLayout) {
                if (trackPx <= 0f) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val size = (trackPx * currentVisibleFraction)
                        .coerceIn(minThumbPx.coerceAtMost(trackPx), trackPx)
                    val travel = (trackPx - size).coerceAtLeast(0f)
                    if (travel <= 0f) return@awaitEachGesture
                    val top = travel * currentProgress
                    val grabbedThumb = down.position.y in top..(top + size)
                    val grabOffset = if (grabbedThumb) down.position.y - top else size / 2f
                    draggedVisibleFraction = currentVisibleFraction
                    draggedProgress = currentProgress
                    dragging = true
                    fun seek(y: Float) {
                        draggedProgress = ((y - grabOffset) / travel).coerceIn(0f, 1f)
                        currentScroll(if (reverseLayout) 1f - draggedProgress else draggedProgress)
                    }
                    try {
                        down.consume()
                        if (!grabbedThumb) seek(down.position.y)
                        drag(down.id) { change ->
                            change.consume()
                            seek(change.position.y)
                        }
                    } finally {
                        dragging = false
                    }
                }
            }
        ) {
            Box(Modifier.align(Alignment.TopEnd).padding(end = AppScrollbarStyle.EdgeMargin.dp)
                .offset { IntOffset(0, thumbTop.roundToInt()) }
                .width(AppScrollbarStyle.ThumbWidth.dp).height(with(density) { thumbPx.toDp() })
                .graphicsLayer { alpha = thumbAlpha.value }
                .background(thumbColor, RoundedCornerShape((AppScrollbarStyle.ThumbWidth / 2).dp))
                .testTag("app-lazy-scrollbar-thumb"))
        }
    }
}

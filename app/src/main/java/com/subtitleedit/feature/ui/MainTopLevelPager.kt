package com.subtitleedit.feature.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs

@Composable
internal fun rememberMainTopLevelMotion(pageOrder: List<Int>, selectedItemId: Int): MainTopLevelMotion {
    val pager = rememberPagerState(
        initialPage = pageOrder.indexOf(selectedItemId).coerceAtLeast(0),
        pageCount = { pageOrder.size },
    )
    return remember(pager) { MainTopLevelMotion(pager) }
}

private val TabSlideSpec = tween<Float>(300, easing = CubicBezierEasing(.25f, .1f, .25f, 1f))

internal class MainTopLevelMotion(val pager: PagerState) {
    private class Jump(val from: Int, val to: Int) {
        val progress = Animatable(0f)
        val direction = if (to > from) 1 else -1
    }

    private var jump by mutableStateOf<Jump?>(null)
    private val pagePosition get() = pager.currentPage + pager.currentPageOffsetFraction

    // Both readers are used in drawing/layer blocks so progress does not recompose pages.
    val indicatorFrame: () -> MainTabIndicatorFrame = {
        val current = jump
        if (current == null) MainTabIndicatorFrame.adjacent(pagePosition)
        else MainTabIndicatorFrame(current.from, current.to, current.progress.value)
    }

    fun pageTranslation(page: Int): Float {
        val current = jump ?: return 0f
        // The pager stays in its canonical order. Only the old and requested pages
        // are repositioned into the one-page slide; nothing in between is shown.
        val offset = when (page) {
            current.from -> -current.direction * current.progress.value
            current.to -> current.direction * (1f - current.progress.value)
            else -> return 0f
        }
        return pagePosition - page + offset
    }

    suspend fun animateTo(page: Int) {
        if (abs(page - pager.currentPage) <= 1) {
            pager.animateScrollToPage(page, animationSpec = TabSlideSpec)
            return
        }
        pager.scroll {
            val transition = Jump(pager.currentPage, page)
            jump = transition
            try {
                pager.run { updateCurrentPage(page) }
                transition.progress.animateTo(1f, TabSlideSpec)
            } finally {
                jump = null
            }
        }
    }
}

/** Visited destinations keep their compositions; only the settled page runs lifecycle work. */
@Composable
internal fun MainTopLevelPager(
    state: MainActivityScreenState,
    pageOrder: List<Int>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true,
    motion: MainTopLevelMotion = rememberMainTopLevelMotion(pageOrder, state.selectedTopLevelItem),
    content: @Composable (MainActivityScreenState, Boolean) -> Unit,
) {
    val pager = motion.pager
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestState by rememberUpdatedState(state)
    val latestOnSelected by rememberUpdatedState(onSelected)
    val visited = remember { mutableStateListOf(state.selectedTopLevelItem) }
    val snapshots = remember { mutableMapOf<Int, MainActivityScreenState>() }
    snapshots[state.selectedTopLevelItem] = state
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(motion, state.selectedTopLevelItem, pageOrder) {
        val page = pageOrder.indexOf(state.selectedTopLevelItem)
        val atDestination = pager.settledPage == page && !pager.isScrollInProgress &&
            pager.currentPageOffsetFraction == 0f
        if (page < 0 || atDestination) return@LaunchedEffect
        requestedPage = page
        if (state.selectedTopLevelItem !in visited) visited.add(state.selectedTopLevelItem)
        focusManager.clearFocus()
        keyboard?.hide()
        try {
            motion.animateTo(page)
        } finally {
            if (requestedPage == page) requestedPage = null
        }
    }
    LaunchedEffect(pager, pageOrder) {
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collect { (page, moving) ->
            if (!moving && requestedPage == null) {
                val id = pageOrder[page]
                if (id != latestState.selectedTopLevelItem) latestOnSelected(id)
            }
            if (moving) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }
    }
    HorizontalPager(
        state = pager,
        modifier = modifier.fillMaxSize().clipToBounds().testTag("main-page-pager"),
        // Only five lightweight page hosts are created initially. Their real content is mounted
        // on first exposure and then retained, including Lazy states and loaded artwork.
        beyondViewportPageCount = (pageOrder.size - 1).coerceAtLeast(0),
        userScrollEnabled = userScrollEnabled,
        key = { pageOrder[it] },
    ) { page ->
        val id = pageOrder[page]
        val exposed by remember(page, pager) {
            derivedStateOf {
                pager.layoutInfo.let { layout ->
                    layout.visiblePagesInfo.any {
                        it.index == page && it.offset < layout.viewportEndOffset &&
                            it.offset + layout.pageSize > layout.viewportStartOffset
                    }
                }
            }
        }
        val selected = latestState.selectedTopLevelItem == id
        val mounted = selected || exposed || id in visited
        if (mounted && id !in visited) SideEffect { visited.add(id) }
        val snapshot = if (selected) latestState else snapshots.getOrPut(id) {
            latestState.copy(selectedTopLevelItem = id, toolbarTitle = "", toolbarHasBack = false,
                isSearchExpanded = false)
        }
        val active = selected && pager.settledPage == page && !pager.isScrollInProgress
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = motion.pageTranslation(page) * size.width * if (rtl) -1 else 1
        }.testTag("main-page:$id")) {
            if (mounted) RetainedPageLifecycle(active) { content(snapshot, active) }
        }
    }
}

/** A hidden retained page must not own Back handlers or lifecycle-aware subscriptions. */
@Composable
private fun RetainedPageLifecycle(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    } }
    DisposableEffect(parent, owner, active) {
        fun update() {
            val state = parent.lifecycle.currentState
            owner.registry.currentState = when {
                state == Lifecycle.State.DESTROYED -> state
                !active && state.isAtLeast(Lifecycle.State.CREATED) -> Lifecycle.State.CREATED
                else -> state
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        parent.lifecycle.addObserver(observer)
        update()
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner) { onDispose { owner.registry.currentState = Lifecycle.State.DESTROYED } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

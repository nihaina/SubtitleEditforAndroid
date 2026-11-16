package com.subtitleedit.feature.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.subtitleedit.ui.theme.AppMotion

/** Fixed-size pages need layer transforms, without AnimatedContent's size/lookahead measurement. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun MainTopLevelPageTransition(
    state: MainActivityScreenState,
    pageOrder: List<Int>,
    modifier: Modifier = Modifier,
    content: @Composable (MainActivityScreenState) -> Unit
) {
    val offsetPx = with(LocalDensity.current) { 28.dp.toPx() }
    val latestState by rememberUpdatedState(state)
    // Only page IDs enter the transition's maps. File lists stay in the page snapshots.
    val snapshots = remember { mutableMapOf<Int, MainActivityScreenState>() }
    snapshots[state.selectedTopLevelItem] = state
    val transition = updateTransition(state.selectedTopLevelItem, label = "main-top-level")
    LaunchedEffect(transition.currentState, transition.targetState) {
        if (transition.currentState == transition.targetState) {
            snapshots.keys.retainAll(setOf(transition.targetState))
        }
    }
    transition.Crossfade(
        modifier = modifier.fillMaxSize().clipToBounds(),
        animationSpec = AppMotion.state()
    ) { pageId ->
        val pageState = if (pageId == latestState.selectedTopLevelItem) latestState else snapshots[pageId]
        if (pageState != null) {
            val translation by transition.animateFloat(transitionSpec = { AppMotion.state() }, label = "page-offset:$pageId") { selected ->
                when {
                    selected == pageId -> 0f
                    pageOrder.indexOf(pageId) > pageOrder.indexOf(selected) -> offsetPx
                    else -> -offsetPx
                }
            }
            // The toolbar and body share one transform and one fixed viewport.
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = translation }
                .testTag("main-page:$pageId")) {
                content(pageState)
            }
        }
    }
}

package com.subtitleedit.feature.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.subtitleedit.ui.theme.AppMotion

/** Keeps each page's toolbar and body together, including their state while the page exits. */
@Composable
internal fun MainTopLevelPageTransition(
    state: MainActivityScreenState,
    pageOrder: List<Int>,
    modifier: Modifier = Modifier,
    content: @Composable (MainActivityScreenState) -> Unit
) {
    val offsetPx = with(LocalDensity.current) { 28.dp.roundToPx() }
    AnimatedContent(
        targetState = state,
        contentKey = { it.selectedTopLevelItem },
        modifier = modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val direction = when {
                targetState.selectedTopLevelItem == initialState.selectedTopLevelItem -> 0
                pageOrder.indexOf(targetState.selectedTopLevelItem) >
                    pageOrder.indexOf(initialState.selectedTopLevelItem) -> 1
                else -> -1
            }
            (
                slideInHorizontally(AppMotion.state()) { direction * offsetPx } +
                    fadeIn(AppMotion.state())
                ) togetherWith (
                slideOutHorizontally(AppMotion.state()) { -direction * offsetPx } +
                    fadeOut(AppMotion.state())
                ) using SizeTransform(clip = true)
        },
        label = "main-top-level"
    ) { pageState ->
        // Equal viewport sizes prevent a page without a toolbar from resizing the exiting page.
        Box(Modifier.fillMaxSize().testTag("main-page:${pageState.selectedTopLevelItem}")) {
            content(pageState)
        }
    }
}

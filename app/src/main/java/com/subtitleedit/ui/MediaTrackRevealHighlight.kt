package com.subtitleedit.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import kotlinx.coroutines.flow.first

internal val MediaTrackRevealPulse = SemanticsPropertyKey<Int>("MediaTrackRevealPulse")
internal var SemanticsPropertyReceiver.mediaTrackRevealPulse by MediaTrackRevealPulse
internal val MediaTrackRevealOpacity = SemanticsPropertyKey<Float>("MediaTrackRevealOpacity")
internal var SemanticsPropertyReceiver.mediaTrackRevealOpacity by MediaTrackRevealOpacity

/** Three finite, slow pulses begin only after the requested row is in the actual viewport. */
@Composable
internal fun rememberMediaTrackRevealHighlight(
    collectionKey: String,
    request: MediaCategoryRevealRequest?,
    visiblePaths: List<String>,
    isActive: Boolean,
    listState: LazyListState,
    onRevealFinished: (Long) -> Unit
): Pair<Float, Int> {
    val opacity = remember { Animatable(0f) }
    var pulse by remember { mutableIntStateOf(0) }
    val finished by rememberUpdatedState(onRevealFinished)
    LaunchedEffect(collectionKey, request?.token, visiblePaths, isActive) {
        opacity.snapTo(0f)
        pulse = 0
        val target = request?.takeIf { isActive } ?: return@LaunchedEffect
        val trackIndex = visiblePaths.indexOf(target.trackPath)
        if (trackIndex < 0) return@LaunchedEffect
        try {
            // Layout must reflect any group change before deriving the number of header items.
            withFrameNanos { }
            withFrameNanos { }
            val total = snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it > visiblePaths.size }
            val headerCount = total - visiblePaths.size
            listState.scrollToItem(headerCount + trackIndex)
            snapshotFlow {
                val layout = listState.layoutInfo
                layout.visibleItemsInfo.any { item ->
                    item.key == target.trackPath && item.offset < layout.viewportEndOffset &&
                        item.offset + item.size > layout.viewportStartOffset
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
            pulse = 0
            finished(target.token)
        } finally {
            pulse = 0
            opacity.snapTo(0f)
        }
    }
    return opacity.value to pulse
}

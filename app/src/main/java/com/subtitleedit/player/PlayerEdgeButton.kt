package com.subtitleedit.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Keeps the visual circle compact while reporting its full minimum touch target to gestures. */
@Composable
internal fun PlayerEdgeButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = PlayerColors.SurfaceHigh.copy(alpha = 0.94f),
    tint: Color = PlayerColors.OnDark,
    interactionSource: MutableInteractionSource? = null,
    onBoundsChanged: (Rect?) -> Unit = {}
) {
    val boundsChanged by rememberUpdatedState(onBoundsChanged)
    val density = LocalDensity.current
    val minimumTouchTarget = LocalViewConfiguration.current.minimumTouchTargetSize
    val minimumWidth = with(density) { minimumTouchTarget.width.toPx() }
    val minimumHeight = with(density) { minimumTouchTarget.height.toPx() }
    DisposableEffect(Unit) { onDispose { boundsChanged(null) } }
    Box(
        modifier = modifier.size(32.dp).clip(CircleShape).background(background)
            .onGloballyPositioned {
                val bounds = it.boundsInRoot()
                val horizontal = ((minimumWidth - bounds.width) / 2f).coerceAtLeast(0f)
                val vertical = ((minimumHeight - bounds.height) / 2f).coerceAtLeast(0f)
                boundsChanged(Rect(bounds.left - horizontal, bounds.top - vertical,
                    bounds.right + horizontal, bounds.bottom + vertical))
            }
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current,
                role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription, Modifier.size(16.dp), tint = tint)
    }
}

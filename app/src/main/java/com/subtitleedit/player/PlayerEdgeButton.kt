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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The circle, press feedback and clickable node share the same bounds. */
@Composable
internal fun PlayerEdgeButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = PlayerColors.SurfaceHigh.copy(alpha = 0.94f),
    tint: Color = PlayerColors.OnDark,
    interactionSource: MutableInteractionSource? = null
) {
    Box(
        modifier = modifier.size(32.dp).clip(CircleShape).background(background)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current,
                role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription, Modifier.size(16.dp), tint = tint)
    }
}

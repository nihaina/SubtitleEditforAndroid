package com.subtitleedit.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import com.subtitleedit.R
import kotlin.math.roundToInt

/** Uses the rendered artwork, including its layer transform, rather than a second layout estimate. */
@Composable
internal fun PlayerAudioArtworkHitTarget(
    boundsInRoot: Rect?,
    originInRoot: Offset,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bounds = boundsInRoot?.takeIf { it.width > 0f && it.height > 0f } ?: return
    val density = LocalDensity.current
    Box(modifier
        .offset { IntOffset((bounds.left - originInRoot.x).roundToInt(),
            (bounds.top - originInRoot.y).roundToInt()) }
        .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
        .testTag("player-audio-artwork-hit-target")
        .clickable(enabled = enabled, onClickLabel = stringResource(R.string.player_lyrics_show),
            role = Role.Button, onClick = onClick))
}

package com.subtitleedit.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Player surfaces share the app's current light/dark theme and accent colors. */
internal object PlayerColors {
    val Background: Color @Composable get() = MaterialTheme.colorScheme.background
    val Surface: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
    val SurfaceHigh: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
    val AccentSoft: Color @Composable get() = MaterialTheme.colorScheme.tertiary
    val OnDark: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val OnDarkVariant: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Danger: Color @Composable get() = MaterialTheme.colorScheme.error

    val accentBrush: Brush
        @Composable get() = Brush.linearGradient(listOf(Accent, AccentSoft))

    val pageGradient: Brush
        @Composable get() = Brush.verticalGradient(listOf(
            MaterialTheme.colorScheme.surfaceContainerLow,
            Background,
            MaterialTheme.colorScheme.surfaceContainerLowest
        ))
}

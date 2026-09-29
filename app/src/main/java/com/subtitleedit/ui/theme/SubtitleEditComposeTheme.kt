package com.subtitleedit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3F2FD),
    onPrimaryContainer = Color(0xFF1565C0),
    secondary = Color(0xFF26A69A),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFFE0F2F1),
    onSecondaryContainer = Color(0xFF004D40),
    tertiary = Color(0xFF00897B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB2DFDB),
    onTertiaryContainer = Color(0xFF004D40),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFEEEEF2),
    onSurfaceVariant = Color(0xFF6C6B6F),
    inverseSurface = Color(0xFF303033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Color(0xFFFFD54F),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF777982),
    outlineVariant = Color(0xFFD5D6DB),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF1976D2)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1565C0),
    onPrimaryContainer = Color(0xFFE3F2FD),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF004D40),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = Color(0xFF4DB6AC),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF00695C),
    onTertiaryContainer = Color(0xFFB2DFDB),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF25262B),
    onSurfaceVariant = Color(0xFFCAC4D0),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF303033),
    inversePrimary = Color(0xFF1976D2),
    error = Color(0xFFCF6679),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF9B9DA8),
    outlineVariant = Color(0xFF3B3D45),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF90CAF9)
)

@Composable
fun SubtitleEditComposeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}

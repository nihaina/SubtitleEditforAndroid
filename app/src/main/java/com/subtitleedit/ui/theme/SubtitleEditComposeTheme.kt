package com.subtitleedit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0E58C8), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF), onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF575E71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE2F9), onSecondaryContainer = Color(0xFF151B2C),
    tertiary = Color(0xFF0B6B59), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA1F2DC), onTertiaryContainer = Color(0xFF002019),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FD), onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFBF8FD), onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE1E2EC), onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780), outlineVariant = Color(0xFFC5C6D0),
    scrim = Color(0xFF000000), inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4), inversePrimary = Color(0xFFB0C6FF),
    surfaceDim = Color(0xFFDBD9DD), surfaceBright = Color(0xFFFBF8FD),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F3F7),
    surfaceContainer = Color(0xFFEFEDF1), surfaceContainerHigh = Color(0xFFE9E7EC),
    surfaceContainerHighest = Color(0xFFE3E2E6)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF), onPrimary = Color(0xFF002D6F),
    primaryContainer = Color(0xFF00419C), onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFC0C6DC), onSecondary = Color(0xFF293042),
    secondaryContainer = Color(0xFF404659), onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFF85D6C0), onTertiary = Color(0xFF00382D),
    tertiaryContainer = Color(0xFF005143), onTertiaryContainer = Color(0xFFA1F2DC),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121316), onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121316), onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44464F), onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099), outlineVariant = Color(0xFF44464F),
    scrim = Color(0xFF000000), inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF303034), inversePrimary = Color(0xFF0E58C8),
    surfaceDim = Color(0xFF121316), surfaceBright = Color(0xFF39393C),
    surfaceContainerLowest = Color(0xFF0D0E11), surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF1F1F23), surfaceContainerHigh = Color(0xFF292A2D),
    surfaceContainerHighest = Color(0xFF343438)
)

private val SubtitleEditTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
)

private val SubtitleEditShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

val ColorScheme.cardContainer: Color
    @Composable get() = if (background.luminance() < 0.5f) {
        surfaceContainerLow
    } else {
        surfaceContainerLowest
    }

@Composable
fun SubtitleEditComposeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = SubtitleEditTypography,
        shapes = SubtitleEditShapes,
        content = content
    )
}

package com.subtitleedit.ui.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Visual tokens of the settings page, shared with the model import / management pages so that all
 * of them look the same. In light mode the page uses a slightly deeper background than its tiles.
 */
@Composable
internal fun settingsLightPalette(): Boolean = MaterialTheme.colorScheme.surface.luminance() > 0.5f

@Composable
internal fun settingsTileColor(): Color =
    if (settingsLightPalette()) Color(0xFFFAF7FE) else MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
internal fun settingsPrimaryTextColor(): Color =
    if (settingsLightPalette()) Color(0xFF303139) else MaterialTheme.colorScheme.onSurface

@Composable
internal fun settingsSecondaryTextColor(): Color =
    if (settingsLightPalette()) Color(0xFF68758A) else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun settingsIconBoxColor(): Color =
    if (settingsLightPalette()) Color(0xFFE2EAFA) else MaterialTheme.colorScheme.primaryContainer

@Composable
internal fun settingsIconTint(): Color =
    if (settingsLightPalette()) Color(0xFF303139) else MaterialTheme.colorScheme.onPrimaryContainer

/** Background of value capsules and inset value fields placed on a tile. */
@Composable
internal fun settingsChipColor(): Color =
    if (settingsLightPalette()) Color(0xFFEDF0F7) else MaterialTheme.colorScheme.surfaceContainerHighest

/** Background of the selected segment in a small two-option switch (CPU / NPU, TDT / CTC). */
@Composable
internal fun settingsChoiceSelectedColor(): Color =
    if (settingsLightPalette()) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.primaryContainer

@Composable
internal fun settingsChoiceSelectedTextColor(): Color =
    if (settingsLightPalette()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer

private val SettingsTileRestingCorner = 4.dp
private val SettingsTilePressedCorner = 20.dp

/** Tile corners relax while pressed; the group clips the outer corners of the first and last tile. */
@Composable
internal fun settingsTileShape(source: MutableInteractionSource): RoundedCornerShape {
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) SettingsTilePressedCorner else SettingsTileRestingCorner,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "settingsTileCorner"
    )
    return RoundedCornerShape(corner.coerceAtLeast(0.dp))
}

/** A standalone tile with the same colour and corner language as the settings page groups. */
@Composable
fun SettingsTileCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(settingsTileColor())
            .padding(16.dp),
        content = content
    )
}

/** The rounded 44dp icon box used at the start of settings rows. */
@Composable
fun SettingsIconBox(iconRes: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(settingsIconBoxColor()),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = settingsIconTint()
        )
    }
}

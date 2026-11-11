package com.subtitleedit.ui.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.theme.cardContainer

private val LocalUseMaterialTileColors = staticCompositionLocalOf { false }

/** Use the app's Material colours while keeping settings tile layouts and interactions. */
@Composable
internal fun MaterialSettingsTileTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUseMaterialTileColors provides true, content = content)
}

/**
 * Visual tokens of the settings page, shared with the model import / management pages so that all
 * of them look the same. In light mode the page uses a slightly deeper background than its tiles.
 */
@Composable
internal fun settingsLightPalette(): Boolean = MaterialTheme.colorScheme.surface.luminance() > 0.5f

@Composable
internal fun settingsTileColor(): Color =
    when {
        LocalUseMaterialTileColors.current -> MaterialTheme.colorScheme.cardContainer
        settingsLightPalette() -> Color(0xFFFAF7FE)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

@Composable
internal fun settingsPrimaryTextColor(): Color =
    if (LocalUseMaterialTileColors.current || !settingsLightPalette()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color(0xFF303139)
    }

@Composable
internal fun settingsSecondaryTextColor(): Color =
    if (LocalUseMaterialTileColors.current || !settingsLightPalette()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color(0xFF68758A)
    }

@Composable
internal fun settingsIconBoxColor(): Color =
    if (LocalUseMaterialTileColors.current || !settingsLightPalette()) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color(0xFFE2EAFA)
    }

@Composable
internal fun settingsIconTint(): Color =
    if (LocalUseMaterialTileColors.current || !settingsLightPalette()) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        Color(0xFF303139)
    }

/** Background of value capsules and inset value fields placed on a tile. */
@Composable
internal fun settingsChipColor(): Color =
    if (LocalUseMaterialTileColors.current || !settingsLightPalette()) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color(0xFFEDF0F7)
    }

/** Background of the selected segment in a small two-option switch (CPU / NPU, TDT / CTC). */
@Composable
internal fun settingsChoiceSelectedColor(): Color =
    if (settingsLightPalette()) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.primaryContainer

@Composable
internal fun settingsChoiceSelectedTextColor(): Color =
    if (settingsLightPalette()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer

/**
 * Colours for outlined fields placed on a settings tile. The container stays transparent so the
 * floating label's cut-out in the border never shows a box; contrast comes from the outline.
 */
@Composable
internal fun settingsOutlinedTextFieldColors(): TextFieldColors {
    val text = settingsPrimaryTextColor()
    val secondary = settingsSecondaryTextColor()
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = text,
        unfocusedTextColor = text,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = secondary,
        unfocusedTrailingIconColor = secondary,
        focusedTrailingIconColor = secondary,
        unfocusedPlaceholderColor = secondary,
        focusedPlaceholderColor = secondary
    )
}

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

/** A settings-styled tile with an optional title and subtitle, used to group the controls of a tool page. */
@Composable
fun SettingsTileSection(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsTileCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null || subtitle != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    title?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = settingsPrimaryTextColor())
                    }
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = settingsSecondaryTextColor())
                    }
                }
            }
            content()
        }
    }
}

/** A group of settings tiles: 2dp gaps between tiles, outer corners clipped like the settings page. */
@Composable
fun SettingsTileGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

/** Clickable settings tile: optional icon box, title, optional value chip (or value below) and arrow. */
@Composable
fun SettingsTileRow(
    title: String,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    value: String? = null,
    valueBelow: Boolean = false,
    showArrow: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (valueBelow) 80.dp else 68.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(settingsTileShape(source))
            .background(settingsTileColor())
            .clickable(
                enabled = enabled,
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            SettingsIconBox(iconRes)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = settingsPrimaryTextColor())
            if (valueBelow && !value.isNullOrEmpty()) {
                Text(
                    value,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
            }
        }
        if (!valueBelow && !value.isNullOrEmpty()) {
            Text(
                value,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .widthIn(max = 160.dp)
                    .clip(CircleShape)
                    .background(settingsChipColor())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = settingsSecondaryTextColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showArrow) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(24.dp),
                tint = settingsSecondaryTextColor()
            )
        }
    }
}

/** Switch settings tile with the same look as the switch rows of the settings page. */
@Composable
fun SettingsTileSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "",
    enabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val light = settingsLightPalette()
    val tile = settingsTileColor()
    val container by animateColorAsState(
        targetValue = if (checked && !light) lerp(tile, colors.primaryContainer, 0.5f) else tile,
        label = "settingsTileSwitchContainer"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(settingsTileShape(source))
            .background(container)
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = {
                    haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onCheckedChange(it)
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = settingsPrimaryTextColor())
            if (description.isNotBlank()) {
                Text(
                    description,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryTextColor()
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = if (checked) {
                {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            } else null
        )
    }
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

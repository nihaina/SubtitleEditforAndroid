package com.subtitleedit.feature.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.subtitleedit.ui.theme.AppMotion

@Composable
internal fun MainBottomNavigation(selectedItemId: Int, playerModeEnabled: Boolean, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bottomNavigationItems(playerModeEnabled).forEach { (id, icon, title) ->
            MainBottomNavigationItem(id, icon, stringResource(title), selectedItemId == id, onSelected,
                Modifier.weight(1f))
        }
    }
}

/** Animation values are read by drawing/layer nodes, leaving layout and composition unchanged. */
@Composable
private fun MainBottomNavigationItem(
    id: Int,
    icon: Int,
    label: String,
    selected: Boolean,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val indicatorAlpha = animateFloatAsState(if (selected) 1f else 0f, AppMotion.enter(), label = "bottom-nav-indicator")
    val indicatorScale = animateFloatAsState(if (selected) 1f else 0.6f, AppMotion.enter(), label = "bottom-nav-indicator-scale")
    val tint = animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        AppMotion.fast(), label = "bottom-nav-tint",
    )
    val indicatorColor = MaterialTheme.colorScheme.secondaryContainer
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.fillMaxHeight().padding(vertical = 4.dp).selectable(
            selected = selected, enabled = !selected, role = Role.Tab,
            interactionSource = interactionSource, indication = null,
            onClick = { if (!selected) onSelected(id) },
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 28.dp).clip(MaterialTheme.shapes.extraLarge)
                .graphicsLayer { scaleX = indicatorScale.value; scaleY = indicatorScale.value }
                .drawBehind { drawRect(indicatorColor.copy(alpha = indicatorAlpha.value)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), tint = { tint.value }, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        BasicText(label, color = { tint.value },
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

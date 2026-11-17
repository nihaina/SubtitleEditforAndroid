package com.subtitleedit.feature.ui

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.subtitleedit.ui.theme.AppMotion
import kotlin.math.abs
import kotlin.math.floor

@Composable
internal fun MainBottomNavigation(
    selectedItemId: Int,
    playerModeEnabled: Boolean,
    pagePosition: (() -> Float)? = null,
    onSelected: (Int) -> Unit,
) {
    val items = bottomNavigationItems(playerModeEnabled)
    val selectedIndex = items.indexOfFirst { it.first == selectedItemId }.coerceAtLeast(0)
    // The home pager supplies its actual position during dragging, flinging and click navigation.
    // Standalone hosts use the same spatial motion without adding a second animation to the pager.
    val position = pagePosition ?: run {
        val animated = animateFloatAsState(selectedIndex.toFloat(), AppMotion.enter(), label = "bottom-nav-position")
        remember(animated) { { animated.value } }
    }
    val indicatorColor = MaterialTheme.colorScheme.secondaryContainer
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val labelHeight = remember { mutableIntStateOf(0) }
    val initialLabelHeight = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toPx() }
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag("main-bottom-navigation")
            .drawBehind {
                val page = position().coerceIn(0f, items.lastIndex.toFloat())
                val source = floor(page)
                val fraction = page - source
                val pitch = size.width / items.size
                val width = 56.dp.toPx()
                val height = 28.dp.toPx()
                // The leading edge moves first; the trailing edge catches up. Reversing a
                // gesture retraces the same shape without a timer or an extra spring lag.
                val left = (source + fraction * fraction + .5f) * pitch - width / 2f
                val right = (source + 1f - (1f - fraction) * (1f - fraction) + .5f) * pitch + width / 2f
                val textHeight = labelHeight.intValue.takeIf { it > 0 }?.toFloat() ?: initialLabelHeight
                val top = (size.height - height - textHeight) / 2f
                drawRoundRect(indicatorColor,
                    topLeft = Offset(if (rtl) size.width - right else left, top),
                    size = Size(right - left, height), cornerRadius = CornerRadius(height / 2f))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, (id, icon, title) ->
            MainBottomNavigationItem(id, icon, stringResource(title), selectedItemId == id,
                index, position, onSelected, Modifier.weight(1f),
                onLabelHeight = if (index == 0) ({ labelHeight.intValue = it }) else null)
        }
    }
}

/** Position and color are read by drawing nodes, leaving layout and composition unchanged. */
@Composable
private fun MainBottomNavigationItem(
    id: Int,
    icon: Int,
    label: String,
    selected: Boolean,
    index: Int,
    position: () -> Float,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
    onLabelHeight: ((Int) -> Unit)?,
) {
    val activeTint = MaterialTheme.colorScheme.onSecondaryContainer
    val inactiveTint = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = remember(position, index, activeTint, inactiveTint) {
        { lerp(inactiveTint, activeTint, (1f - abs(position() - index)).coerceIn(0f, 1f)) }
    }
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
        Box(Modifier.size(width = 56.dp, height = 28.dp).graphicsLayer {
            val scale = .6f + .4f * (1f - abs(position() - index)).coerceIn(0f, 1f)
            scaleX = scale
            scaleY = scale
        }, contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), tint = tint, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        BasicText(label, color = tint,
            modifier = if (onLabelHeight == null) Modifier else Modifier.onSizeChanged { onLabelHeight(it.height) },
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

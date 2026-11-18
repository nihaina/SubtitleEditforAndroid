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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal data class MainTabIndicatorFrame(val from: Int, val to: Int, val progress: Float) {
    fun emphasis(index: Int): Float = when (index) {
        from -> if (from == to) 1f else 1f - progress
        to -> progress
        else -> 0f
    }

    companion object {
        fun adjacent(position: Float): MainTabIndicatorFrame {
            val from = floor(position).toInt()
            return MainTabIndicatorFrame(from, from + 1, position - from)
        }
    }
}

@Composable
internal fun MainBottomNavigation(
    selectedItemId: Int,
    playerModeEnabled: Boolean,
    indicatorFrame: (() -> MainTabIndicatorFrame)? = null,
    onSelected: (Int) -> Unit,
) {
    val items = bottomNavigationItems(playerModeEnabled)
    val selectedIndex = items.indexOfFirst { it.first == selectedItemId }.coerceAtLeast(0)
    val frame = indicatorFrame ?: run {
        val animated = animateFloatAsState(selectedIndex.toFloat(), AppMotion.enter(), label = "bottom-nav-position")
        remember(animated) { { MainTabIndicatorFrame.adjacent(animated.value) } }
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
                val (from, to, progress) = frame()
                val start = minOf(from, to).coerceIn(items.indices)
                val span = maxOf(from, to).coerceIn(items.indices) - start
                val fraction = if (from <= to) progress else 1f - progress
                val pitch = size.width / items.size
                val width = 56.dp.toPx()
                val height = 28.dp.toPx()
                // A drag keeps its original response. A long jump stretches once across
                // the whole interval, like the elastic indicator in Kazumi's weekday bar.
                val angle = fraction * PI / 2
                val trailing = if (span > 1) 1f - cos(angle).toFloat() else fraction * fraction
                val leading = if (span > 1) sin(angle).toFloat() else 1f - (1f - fraction) * (1f - fraction)
                val left = (start + span * trailing + .5f) * pitch - width / 2f
                val right = (start + span * leading + .5f) * pitch + width / 2f
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
                index, frame, onSelected, Modifier.weight(1f),
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
    frame: () -> MainTabIndicatorFrame,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
    onLabelHeight: ((Int) -> Unit)?,
) {
    val activeTint = MaterialTheme.colorScheme.onSecondaryContainer
    val inactiveTint = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = remember(frame, index, activeTint, inactiveTint) {
        { lerp(inactiveTint, activeTint, frame().emphasis(index)) }
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
            val scale = .6f + .4f * frame().emphasis(index)
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

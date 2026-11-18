package com.subtitleedit.feature.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.subtitleedit.FileOperation
import com.subtitleedit.R
import com.subtitleedit.ui.theme.AppMotion

internal data class MainBottomBarState(
    val selectedTopLevelItem: Int,
    val playerModeEnabled: Boolean,
    val selectionCount: Int,
    val pendingOperation: FileOperation?,
    val canConvertSelected: Boolean,
    val canCompressSelected: Boolean,
    val canEditTagsSelected: Boolean,
    val canClearCacheSelected: Boolean,
    val canProcessMediaSelected: Boolean,
)

internal fun MainActivityScreenState.bottomBarState() = MainBottomBarState(
    selectedTopLevelItem, playerModeEnabled, selectedPaths.size, pendingOperation,
    canConvertSelected, canCompressSelected, canEditTagsSelected, canClearCacheSelected, canProcessMediaSelected,
)

@Composable
internal fun MainBottomBar(
    state: MainBottomBarState,
    onTopLevelPageSelected: (Int) -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onConvertSelection: () -> Unit,
    onCompressSelection: () -> Unit,
    onEditTagsSelection: () -> Unit,
    onClearCacheSelection: () -> Unit,
    onShowSelectionProperties: () -> Unit,
    onConfirmDestination: () -> Unit,
    onCancelDestination: () -> Unit,
    onProcessMediaSelection: () -> Unit,
    indicatorFrame: (() -> MainTabIndicatorFrame)? = null,
    previewActions: (@Composable () -> Unit)? = null,
    previewControlsVisible: Boolean = true,
) {
    val isSelectionActive = state.selectedTopLevelItem == R.id.nav_directory &&
        (state.selectionCount > 0 || state.pendingOperation != null)
    val containerColor = if (previewActions != null) directoryImageOverlayColor() else MaterialTheme.colorScheme.surfaceContainer
    val selectionActions: @Composable () -> Unit = {
        SelectionActions(
            operation = state.pendingOperation,
            onCopy = onCopy,
            onMove = onMove,
            onRename = onRename,
            onDelete = onDelete,
            canConvert = state.canConvertSelected,
            canCompress = state.canCompressSelected,
            canEditTags = state.canEditTagsSelected,
            canClearCache = state.canClearCacheSelected,
            onConvert = onConvertSelection,
            onCompress = onCompressSelection,
            onEditTags = onEditTagsSelection,
            onClearCache = onClearCacheSelection,
            onProperties = onShowSelectionProperties,
            onConfirmDestination = onConfirmDestination,
            onCancelDestination = onCancelDestination,
            canProcessMedia = state.canProcessMediaSelected,
            onProcessMedia = onProcessMediaSelection,
            containerColor = containerColor
        )
    }
    if (previewActions != null) {
        val selectionVisibility = remember { MutableTransitionState(isSelectionActive) }
        selectionVisibility.targetState = isSelectionActive
        Box(
            modifier = Modifier.fillMaxWidth().clipToBounds(),
            contentAlignment = Alignment.BottomCenter
        ) {
            if (previewControlsVisible && selectionVisibility.isIdle && !selectionVisibility.currentState) {
                Box(Modifier.fillMaxWidth().height(68.dp).background(containerColor), contentAlignment = Alignment.BottomCenter) {
                    previewActions()
                }
            }
            AnimatedVisibility(
                visibleState = selectionVisibility,
                enter = slideInVertically(AppMotion.enter()) { it } + fadeIn(AppMotion.enter()),
                exit = slideOutVertically(AppMotion.exit()) { it } + fadeOut(AppMotion.exit()),
                label = "preview-selection-actions"
            ) {
                Column { selectionActions() }
            }
        }
        return
    }
    val showBottomNavigation = state.selectionCount == 0 && state.pendingOperation == null
    androidx.compose.animation.AnimatedContent(
        targetState = showBottomNavigation,
        transitionSpec = {
            androidx.compose.animation.fadeIn(AppMotion.enter()) togetherWith
                androidx.compose.animation.fadeOut(AppMotion.exit())
        },
        label = "selection-actions"
    ) { showBottom ->
        if (showBottom) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MainBottomNavigation(
                selectedItemId = state.selectedTopLevelItem,
                playerModeEnabled = state.playerModeEnabled,
                indicatorFrame = indicatorFrame,
                onSelected = onTopLevelPageSelected
            )
        } else if (isSelectionActive) {
            selectionActions()
        }
    }
}

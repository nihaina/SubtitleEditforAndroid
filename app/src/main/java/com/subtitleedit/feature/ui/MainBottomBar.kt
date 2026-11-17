package com.subtitleedit.feature.ui

import androidx.compose.animation.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
) {
    val isSelectionActive = state.selectedTopLevelItem == R.id.nav_directory &&
        (state.selectionCount > 0 || state.pendingOperation != null)
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
                onSelected = onTopLevelPageSelected
            )
        } else if (isSelectionActive) {
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
                onProcessMedia = onProcessMediaSelection
            )
        }
    }
}

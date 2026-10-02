package com.subtitleedit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.editor.EditorSearchUiState
import com.subtitleedit.model.SubtitleEntry

/**
 * Compose shell for the editor. Activity code supplies document actions and the custom media
 * surfaces through slots; this keeps the screen independent from XML and View hierarchies.
 */
@Composable
internal fun EditorScreen(
    title: String,
    subtitle: String,
    isSelectionActive: Boolean,
    onNavigateUp: () -> Unit,
    prepareMenu: () -> List<EditorToolbarMenuGroup>,
    searchState: EditorSearchUiState,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSubmitSearch: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onToggleMatchCase: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onCloseSearch: () -> Unit,
    isSourceViewMode: Boolean,
    listLoading: Boolean,
    entries: List<SubtitleEntry>,
    contentRevision: Int,
    listState: LazyListState,
    adapter: SubtitleAdapter,
    hasPlayableMedia: Boolean,
    onLongClick: (SubtitleEntry, Int) -> Unit,
    onTimeClick: (SubtitleEntry, Int, Boolean) -> Unit,
    onTextClick: (SubtitleEntry, Int) -> Unit,
    onJumpToTime: (SubtitleEntry, Int) -> Unit,
    onSetTime: (SubtitleEntry, Int) -> Unit,
    mediaContent: @Composable () -> Unit = {},
    sourceContent: @Composable () -> Unit = {},
    isVideoFullscreen: Boolean = false,
    fullscreenContent: @Composable () -> Unit = {}
) {
    if (isVideoFullscreen) {
        Box(Modifier.fillMaxSize()) {
            fullscreenContent()
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                EditorToolbar(
                    title = title,
                    subtitle = subtitle,
                    isSelectionActive = isSelectionActive,
                    onNavigateUp = onNavigateUp,
                    prepareMenu = prepareMenu
                )
                EditorComposeContent(
                    searchContent = {
                        EditorSearchBar(
                            state = searchState,
                            onQueryChange = onQueryChange,
                            onReplacementChange = onReplacementChange,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onSubmitSearch = onSubmitSearch,
                            onReplace = onReplace,
                            onReplaceAll = onReplaceAll,
                            onToggleMatchCase = onToggleMatchCase,
                            onToggleWholeWord = onToggleWholeWord,
                            onClose = onCloseSearch
                        )
                    },
                    isSourceViewMode = isSourceViewMode,
                    mediaContent = {
                        if (hasPlayableMedia) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                shape = RoundedCornerShape(8.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    mediaContent()
                                }
                            }
                        }
                    },
                    sourceContent = sourceContent,
                    subtitleContent = {
                        EditorSubtitleList(
                            entries = entries,
                            contentRevision = contentRevision,
                            listState = listState,
                            adapter = adapter,
                            hasPlayableMedia = hasPlayableMedia,
                            onLongClick = onLongClick,
                            onTimeClick = onTimeClick,
                            onTextClick = onTextClick,
                            onJumpToTime = onJumpToTime,
                            onSetTime = onSetTime
                        )
                    }
                )
            }
            if (listLoading) EditorListLoadingOverlay()
        }
    }
}

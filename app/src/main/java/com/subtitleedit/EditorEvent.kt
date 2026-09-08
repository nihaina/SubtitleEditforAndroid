package com.subtitleedit

internal sealed interface EditorEvent {
    data class SetInitialized(val value: Boolean) : EditorEvent
    data class SetDocumentLoaded(val value: Boolean) : EditorEvent
    data class SetSourceViewMode(val value: Boolean) : EditorEvent
    data class SetSourceViewTransitioning(val value: Boolean) : EditorEvent
    data class SetSourceViewEntryCount(val value: Int) : EditorEvent
    data class SetSourceDocumentContent(val value: String) : EditorEvent
    data class SetSavedScrollPosition(val value: Int) : EditorEvent
    data class SetSavedFirstVisibleItemPosition(val value: Int) : EditorEvent
    data class SetVideoFullscreen(val value: Boolean) : EditorEvent
    data class SetPreviousRequestedOrientation(val value: Int) : EditorEvent
    data class SetVideoViewportInlineIndex(val value: Int) : EditorEvent
    data class SetSelectedIndices(val value: Set<Int>) : EditorEvent
    data class SetPlaybackPositionMs(val value: Long) : EditorEvent
    data class SetPlaybackSpeed(val value: Float) : EditorEvent
    data class SetSelectedAudioStreamIndex(val value: Int?) : EditorEvent
    data class SetAudioOnlyFromVideo(val value: Boolean) : EditorEvent
    data class ExecuteCommand(val command: EditorCommand) : EditorEvent
    data object RequestOptionsMenuRefresh : EditorEvent
}

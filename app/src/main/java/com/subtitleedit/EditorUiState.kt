package com.subtitleedit

/** Transient editor state that belongs to the UI/session rather than the document itself. */
internal data class EditorUiState(
    val initialized: Boolean = false,
    val documentLoaded: Boolean = false,
    val isSourceViewMode: Boolean = false,
    val isSourceViewTransitioning: Boolean = false,
    val sourceViewEntryCount: Int = 0,
    val savedScrollPosition: Int = 0,
    val savedFirstVisibleItemPosition: Int = 0,
    val isVideoFullscreen: Boolean = false,
    val previousRequestedOrientation: Int = -1,
    val videoViewportInlineIndex: Int = 0,
    val selectedIndices: Set<Int> = emptySet(),
    val playbackPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val selectedAudioStreamIndex: Int? = null,
    val isAudioOnlyFromVideo: Boolean = false
)

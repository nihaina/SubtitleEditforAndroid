package com.subtitleedit.editor

/** Pure state policy for editor back navigation. */
internal object EditorNavigationPolicy {
    enum class Decision {
        EXIT_FULLSCREEN,
        CANCEL_SELECTION,
        CONFIRM_UNSAVED,
        FINISH
    }

    fun decide(
        isVideoFullscreen: Boolean,
        selectedCount: Int,
        hasUnsavedChanges: Boolean
    ): Decision = when {
        isVideoFullscreen -> Decision.EXIT_FULLSCREEN
        selectedCount > 0 -> Decision.CANCEL_SELECTION
        hasUnsavedChanges -> Decision.CONFIRM_UNSAVED
        else -> Decision.FINISH
    }
}

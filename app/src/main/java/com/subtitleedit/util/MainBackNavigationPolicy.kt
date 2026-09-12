package com.subtitleedit.util

/** Pure priority rules for the file browser's back navigation. */
internal object MainBackNavigationPolicy {
    enum class Decision {
        DELEGATE_TO_TOP_LEVEL,
        NAVIGATE_DESTINATION,
        EXIT_SELECTION,
        GO_UP_LEVEL,
        FINISH
    }

    fun decide(
        isDirectorySelected: Boolean,
        hasPendingFileOperation: Boolean,
        hasSelection: Boolean,
        hasDirectoryHistory: Boolean
    ): Decision = when {
        !isDirectorySelected -> Decision.DELEGATE_TO_TOP_LEVEL
        hasPendingFileOperation -> Decision.NAVIGATE_DESTINATION
        hasSelection -> Decision.EXIT_SELECTION
        hasDirectoryHistory -> Decision.GO_UP_LEVEL
        else -> Decision.FINISH
    }
}

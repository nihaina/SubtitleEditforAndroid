package com.subtitleedit.editor

/** Mutable, lifecycle-safe source editor state that does not depend on an Activity view. */
internal class EditorSourceViewState {
    var editGeneration: Long = 0L
        
    var entriesGeneration: Long = -1L
        
    var pendingEdits: Boolean = false
    var entryCount: Int = 0

    fun beginTransition() {
        editGeneration++
        entriesGeneration = editGeneration
        pendingEdits = false
    }

    fun markTextEdited() {
        pendingEdits = true
        editGeneration++
    }

    fun markEntriesSynchronized() {
        entriesGeneration = editGeneration
        pendingEdits = false
    }

    fun hasSynchronizedEntries(): Boolean = entriesGeneration == editGeneration
}

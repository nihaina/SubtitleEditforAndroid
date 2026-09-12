package com.subtitleedit.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorNavigationPolicyTest {
    @Test
    fun fullscreenTakesPriorityOverSelectionAndUnsavedChanges() {
        assertEquals(
            EditorNavigationPolicy.Decision.EXIT_FULLSCREEN,
            EditorNavigationPolicy.decide(true, selectedCount = 2, hasUnsavedChanges = true)
        )
    }

    @Test
    fun selectionIsCancelledBeforeUnsavedConfirmation() {
        assertEquals(
            EditorNavigationPolicy.Decision.CANCEL_SELECTION,
            EditorNavigationPolicy.decide(false, selectedCount = 1, hasUnsavedChanges = true)
        )
    }

    @Test
    fun cleanDocumentFinishesDirectly() {
        assertEquals(
            EditorNavigationPolicy.Decision.FINISH,
            EditorNavigationPolicy.decide(false, selectedCount = 0, hasUnsavedChanges = false)
        )
    }

    @Test
    fun unsavedDocumentRequestsConfirmation() {
        assertEquals(
            EditorNavigationPolicy.Decision.CONFIRM_UNSAVED,
            EditorNavigationPolicy.decide(false, selectedCount = 0, hasUnsavedChanges = true)
        )
    }
}

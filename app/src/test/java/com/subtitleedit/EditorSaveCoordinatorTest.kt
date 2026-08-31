package com.subtitleedit

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorSaveCoordinatorTest {
    @Test
    fun successfulSaveReturnsPendingContinuationOnce() {
        val coordinator = EditorSaveCoordinator()
        coordinator.begin(SaveContinuation.FINISH)

        assertEquals(SaveContinuation.FINISH, coordinator.complete(success = true))
        assertEquals(SaveContinuation.NONE, coordinator.complete(success = true))
    }

    @Test
    fun failedSaveClearsContinuationWithoutExecutingIt() {
        val coordinator = EditorSaveCoordinator()
        coordinator.begin(SaveContinuation.FINISH)

        assertEquals(SaveContinuation.NONE, coordinator.complete(success = false))
        assertEquals(SaveContinuation.NONE, coordinator.pending)
    }

    @Test
    fun cancelledDocumentCreationClearsContinuation() {
        val coordinator = EditorSaveCoordinator()
        coordinator.begin(SaveContinuation.FINISH)

        coordinator.cancel()

        assertEquals(SaveContinuation.NONE, coordinator.pending)
    }
}

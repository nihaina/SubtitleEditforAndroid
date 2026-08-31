package com.subtitleedit

internal enum class SaveContinuation {
    NONE,
    FINISH
}

internal class EditorSaveCoordinator {
    var pending: SaveContinuation = SaveContinuation.NONE
        private set

    fun begin(continuation: SaveContinuation) {
        pending = continuation
    }

    fun cancel() {
        pending = SaveContinuation.NONE
    }

    fun complete(success: Boolean): SaveContinuation {
        val continuation = pending
        pending = SaveContinuation.NONE
        return if (success) continuation else SaveContinuation.NONE
    }
}

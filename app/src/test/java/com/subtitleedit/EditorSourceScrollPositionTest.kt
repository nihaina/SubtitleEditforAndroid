package com.subtitleedit

import com.subtitleedit.ui.EditorSourceEditorState
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorSourceScrollPositionTest {
    @Test
    fun measuredWrappedRowsContributeToSavedScrollOffset() {
        val state = EditorSourceEditorState("one\ntwo\nthree")
        val ids = state.lines.map { it.stableId }

        state.updateMeasuredRowHeight(ids[0], 28)
        state.updateMeasuredRowHeight(ids[1], 56)
        state.updateScrollPosition(firstVisibleItem = 2, firstVisibleOffset = 7, rowHeightPx = 28)

        assertEquals(91, state.getDocumentScrollOffset())
    }

    @Test
    fun unmeasuredRowsUseDensityAwareFallback() {
        val state = EditorSourceEditorState("one\ntwo\nthree")
        state.updateScrollPosition(firstVisibleItem = 2, firstVisibleOffset = 3, rowHeightPx = 32)

        assertEquals(67, state.getDocumentScrollOffset())
    }

    @Test
    fun pixelRestoreTargetsMeasuredRowAndKeepsRemainder() {
        val state = EditorSourceEditorState("one\ntwo\nthree")
        val ids = state.lines.map { it.stableId }
        state.updateMeasuredRowHeight(ids[0], 28)
        state.updateMeasuredRowHeight(ids[1], 56)

        state.scrollToDocumentY(91)

        val request = state.pendingScroll
        requireNotNull(request)
        assertEquals(2, request.line)
        assertEquals(-7, request.offset)
    }

    @Test
    fun documentOffsetAtLineTerminatorTargetsFollowingLine() {
        val state = EditorSourceEditorState("a\nb")

        state.scrollToDocumentOffset(2)

        assertEquals(1, state.pendingScroll?.line)
    }
}

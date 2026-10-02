package com.subtitleedit

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.subtitleedit.ui.EditorSourceEditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorSourceSelectionTest {
    @Test
    fun crossLineSelectionSurvivesCollapsedFocusCallback() {
        val state = EditorSourceEditorState("one\ntwo")
        state.selectAllDocument()

        state.updateLine(1, TextFieldValue("two", TextRange(3)))

        assertEquals("one\ntwo", state.selectedDocumentText())
    }

    @Test
    fun movingDocumentHandleKeepsTheOtherEndpoint() {
        val state = EditorSourceEditorState("one\ntwo")
        state.selectAllDocument()

        state.moveSelectionHandle(anchor = false, position = 1, offset = 1)

        assertEquals("one\nt", state.selectedDocumentText())
    }

    @Test
    fun crossedHandleStaysRecoverableUntilDragEnds() {
        val state = EditorSourceEditorState("one\ntwo")
        state.selectAllDocument()
        state.beginHandleDrag(
            sourcePosition = 1,
            anchor = false,
            contentLeftPx = 0f,
            textOriginPx = 0f,
            charWidthPx = 1f
        )

        state.moveSelectionHandle(anchor = false, position = 0, offset = 0)

        assertEquals(null, state.selectedDocumentText())
        requireNotNull(state.documentSelection)
        requireNotNull(state.activeHandleDrag)

        state.moveSelectionHandle(anchor = false, position = 1, offset = 1)
        assertEquals("one\nt", state.selectedDocumentText())

        state.endHandleDrag()
    }

    @Test
    fun mergingPreviousPreservesTheCurrentLineEndingInOffsets() {
        val state = EditorSourceEditorState("a\r\nb\r\n")
        var change: com.subtitleedit.ui.EditorSourceDocumentChange? = null
        state.addOnDocumentChangeListener { change = it }

        state.mergeWithPrevious(1)

        assertEquals("ab\r\n", state.getDocumentText())
        assertEquals(0, change!!.startOffset)
        assertEquals(4, change!!.oldEndOffset)
        assertEquals(4, change!!.newEndOffset)
    }

    @Test
    fun mergingNextPreservesTheNextLineEndingInOffsets() {
        val state = EditorSourceEditorState("a\r\nb\r\n")
        var change: com.subtitleedit.ui.EditorSourceDocumentChange? = null
        state.addOnDocumentChangeListener { change = it }

        state.mergeWithNext(0)

        assertEquals("ab\r\n", state.getDocumentText())
        assertEquals(0, change!!.startOffset)
        assertEquals(4, change!!.oldEndOffset)
        assertEquals(4, change!!.newEndOffset)
    }

    @Test
    fun replacingDocumentClearsFocusRequestsFromPreviousDocument() {
        val state = EditorSourceEditorState("one\ntwo")
        state.requestLineFocus(1, 2)

        state.setDocumentText("new")

        assertEquals(-1, state.focusedLine)
        assertEquals(null, state.pendingFocus)
    }

    @Test
    fun replacingDocumentClearsActiveSelectionGestures() {
        val state = EditorSourceEditorState("one\ntwo")
        state.selectAllDocument()
        state.beginHandleDrag(
            sourcePosition = 0,
            anchor = true,
            contentLeftPx = 0f,
            textOriginPx = 0f,
            charWidthPx = 1f
        )
        state.beginSelectionDrag(
            initialSelection = state.documentSelection,
            initialTouchLine = 0,
            initialTouchOffset = 1
        )

        state.setDocumentText("new")

        assertNull(state.activeHandleDrag)
        assertNull(state.activeSelectionDrag)
        assertNull(state.documentSelection)
    }
}

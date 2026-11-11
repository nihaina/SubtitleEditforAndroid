package com.subtitleedit.feature.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaMuxAudioAction
import com.subtitleedit.MediaMuxColumn
import com.subtitleedit.MediaMuxConflict
import com.subtitleedit.MediaMuxFile
import com.subtitleedit.MediaMuxMode
import com.subtitleedit.MediaMuxResult
import com.subtitleedit.MediaMuxRow
import com.subtitleedit.MediaMuxUiState
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaMuxScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun video(id: String) = MediaMuxFile(id, "file:///video/$id.mp4", "$id.mp4", MediaMuxColumn.VIDEO)
    private fun audio(id: String) = MediaMuxFile(id, "file:///audio/$id.mp3", "$id.mp3", MediaMuxColumn.AUDIO)

    @Test fun switchingModesKeepsTheQueueAndSkippedRowsRemainRemovable() {
        val state = mutableStateOf(MediaMuxUiState(rows = listOf(
            MediaMuxRow("pair", video("movie"), audio("song"), MediaMuxResult("file:///output/movie.mp4", "movie.mp4")),
            MediaMuxRow("video", video("another")))))
        val removals = mutableListOf<Pair<String, MediaMuxColumn>>()
        compose.setContent { SubtitleEditComposeTheme {
            MediaMuxScreen(state.value, {}, {}, { state.value = state.value.copy(mode = it) }, {},
                { id, column -> removals += id to column }, {}, { _, _, _ -> }, {}, {}, { _, _ -> })
        } }
        compose.onNodeWithTag("mux-skipped-pair").assertDoesNotExist()
        compose.onNodeWithTag("mux-skipped-video").assertExists()
        compose.onNodeWithTag("mux-mode-split").performClick()
        compose.onNodeWithTag("mux-skipped-pair").assertExists()
        compose.onNodeWithTag("mux-skipped-video").assertDoesNotExist()
        compose.onNodeWithTag("mux-remove-pair-audio").assertIsEnabled().performClick()
        compose.onNodeWithTag("mux-order-audio").assertIsEnabled()
        compose.onNodeWithTag("mux-start").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(listOf("pair" to MediaMuxColumn.AUDIO), removals)
            assertEquals(listOf("pair", "video"), state.value.rows.map { it.id })
            assertEquals("file:///output/movie.mp4", state.value.rows.first().output?.uri)
        }
    }

    @Test fun activeWorkLocksQueueEditingAndProvidesCancellation() {
        var cancelled = 0
        compose.setContent { SubtitleEditComposeTheme {
            MediaMuxScreen(MediaMuxUiState(rows = listOf(MediaMuxRow("pair", video("movie"), audio("song"),
                MediaMuxResult("file:///output/movie.mp4", "movie.mp4"))),
                isRunning = true, progress = 30), {}, {}, {}, {}, { _, _ -> }, {}, { _, _, _ -> }, {},
                { cancelled++ }, { _, _ -> })
        } }
        compose.onNodeWithTag("mux-import").assertIsNotEnabled()
        compose.onNodeWithTag("mux-add-row").assertIsNotEnabled()
        compose.onNodeWithTag("mux-mode-split").assertIsNotEnabled()
        compose.onNodeWithTag("mux-remove-pair-audio").assertIsNotEnabled()
        compose.onNodeWithTag("mux-remove-pair-output").assertIsNotEnabled()
        compose.onNodeWithTag("mux-start").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun outputRemovalClearsOnlyTheResultAndRemainsAvailableForSkippedRows() {
        val sourceVideo = video("movie")
        val sourceAudio = audio("song")
        val state = mutableStateOf(MediaMuxUiState(rows = listOf(MediaMuxRow("pair", sourceVideo, sourceAudio,
            MediaMuxResult("file:///output/movie.mp4", "movie.mp4"))), mode = MediaMuxMode.SPLIT, isImporting = true))
        val removed = mutableListOf<String>()
        compose.setContent { SubtitleEditComposeTheme {
            MediaMuxScreen(state.value, {}, {}, {}, {}, { _, _ -> }, {}, { _, _, _ -> }, {}, {}, { _, _ -> },
                onRemoveOutput = { rowId ->
                    removed += rowId
                    state.value = state.value.copy(rows = state.value.rows.map {
                        if (it.id == rowId) it.copy(output = null) else it
                    })
                })
        } }
        compose.onNodeWithTag("mux-remove-pair-output").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(isImporting = false) }
        compose.onNodeWithTag("mux-skipped-pair").assertExists()
        compose.onNodeWithTag("mux-remove-pair-output").assertIsEnabled().performClick()
        compose.onNodeWithTag("mux-remove-pair-output").assertDoesNotExist()
        compose.onNodeWithTag("mux-cell-pair-video").assertExists()
        compose.onNodeWithTag("mux-cell-pair-audio").assertExists()
        compose.onNodeWithText("待输出").assertExists()
        compose.runOnIdle {
            assertEquals(listOf("pair"), removed)
            val row = state.value.rows.single()
            assertEquals(sourceVideo, row.video)
            assertEquals(sourceAudio, row.audio)
            assertNull(row.output)
        }
    }

    @Test fun existingAudioConflictCarriesTheChosenActionAndApplyToAll() {
        var choice: Pair<MediaMuxAudioAction?, Boolean>? = null
        compose.setContent { SubtitleEditComposeTheme {
            MediaMuxScreen(MediaMuxUiState(isRunning = true, conflict = MediaMuxConflict("movie.mp4")),
                {}, {}, {}, {}, { _, _ -> }, {}, { _, _, _ -> }, {}, {}, { action, all -> choice = action to all })
        } }
        compose.onNodeWithText("视频已有音轨").assertExists()
        compose.onNodeWithTag("mux-conflict-all").performClick()
        compose.onNodeWithTag("mux-conflict-add").performClick()
        compose.runOnIdle { assertEquals(MediaMuxAudioAction.ADD to true, choice) }
    }

    @Test fun orderChangesOneColumnAndCanMoveFilesAcrossAnExplicitEmptyPosition() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val duplicateName1 = audio("first").copy(fileName = "same.mp3")
        val duplicateName2 = audio("second").copy(fileName = "same.mp3")
        compose.setContent { SubtitleEditComposeTheme {
            MediaMuxOrderDialog(listOf(MediaMuxRow("one", audio = duplicateName1), MediaMuxRow("hole"),
                MediaMuxRow("three", audio = duplicateName2)), MediaMuxColumn.AUDIO, {}, { from, to -> moves += from to to })
        } }
        compose.onNodeWithTag("mux-order-up-second").performClick()
        val secondTop = compose.onNodeWithTag("mux-order-slot-second").fetchSemanticsNode().boundsInRoot.top
        val emptyTop = compose.onNodeWithTag("mux-order-slot-empty:hole").fetchSemanticsNode().boundsInRoot.top
        assertTrue(secondTop < emptyTop)
        compose.runOnIdle { assertEquals(listOf(2 to 1), moves) }
        compose.onNodeWithTag("mux-order-reverse").performClick()
        compose.runOnIdle {
            assertEquals(listOf(2 to 1, 2 to 0, 2 to 1), moves)
        }
        compose.onNodeWithText("空行").assertExists()
    }

    @Test fun draggingAnAudioHandleAcrossABlankMovesThatColumnAndUpdatesItsDisplayedOrder() {
        val moves = mutableListOf<Pair<Int, Int>>()
        compose.setContent { SubtitleEditComposeTheme {
            MediaMuxOrderDialog(listOf(
                MediaMuxRow("one", video("left-one"), audio("first")),
                MediaMuxRow("hole", video("left-two")),
                MediaMuxRow("three", video("left-three"), audio("last"))
            ), MediaMuxColumn.AUDIO, {}, { from, to -> moves += from to to })
        } }
        val first = compose.onNodeWithTag("mux-order-drag-first")
        val last = compose.onNodeWithTag("mux-order-drag-last")
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val lastBounds = last.fetchSemanticsNode().boundsInRoot
        val distance = lastBounds.center.y - firstBounds.center.y + lastBounds.height / 2f
        first.performTouchInput {
            swipe(start = center, end = center + Offset(0f, distance), durationMillis = 600)
        }
        val emptyTop = compose.onNodeWithTag("mux-order-slot-empty:hole").fetchSemanticsNode().boundsInRoot.top
        val lastTop = compose.onNodeWithTag("mux-order-slot-last").fetchSemanticsNode().boundsInRoot.top
        val firstTop = compose.onNodeWithTag("mux-order-slot-first").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The blank slot must move before the dragged file", emptyTop < firstTop)
        assertTrue("The final audio entry must move before the dragged file", lastTop < firstTop)
        assertTrue(emptyTop < lastTop)
        compose.runOnIdle {
            assertTrue("A real drag must dispatch the column move callback", moves.isNotEmpty())
            val order = mutableListOf("first", "empty", "last")
            moves.forEach { (from, to) -> order.add(to, order.removeAt(from)) }
            assertEquals(listOf("empty", "last", "first"), order)
        }
    }
}

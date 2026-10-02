package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSourceWaveformSyncControllerTest {
    @Test
    fun successiveVttDragMovesAlwaysPatchFromTheDragStartDocument() = runBlocking {
        var source = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n字幕\n"
        val original = SubtitleEntry(1, 0L, 1_000L, "字幕", stableId = 41L)
        var latestEntries = listOf(original)
        val firstApplied = CompletableDeferred<Unit>()
        val finalApplied = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        val controller = EditorSourceWaveformSyncController(
            scope = scope,
            isSourceViewMode = { true },
            hasPendingSourceEdits = { false },
            isPreviewActive = { false },
            editGeneration = { 1L },
            currentFormat = { SubtitleParser.SubtitleFormat.VTT },
            sourceContent = { source },
            snapshotSourceContent = { source },
            currentEntries = { latestEntries },
            cancelPreview = {},
            schedulePreview = {},
            recordHistory = { _, _, _ -> },
            onSourceUpdated = { updated ->
                source = updated
                if (!firstApplied.isCompleted) firstApplied.complete(Unit)
                else if (!finalApplied.isCompleted) finalApplied.complete(Unit)
            }
        )

        val firstMove = original.copy(startTime = 100L, endTime = 1_100L)
        controller.schedule(listOf(firstMove), dragKey = 7L, shouldRecord = false, initialEntries = null)
        firstApplied.await()

        // The production callback updates the live model before the next MOVE arrives.
        latestEntries = listOf(firstMove)
        val finalMove = original.copy(startTime = 200L, endTime = 1_200L)
        controller.schedule(listOf(finalMove), dragKey = 7L, shouldRecord = true, initialEntries = null)
        finalApplied.await()
        controller.cancel()

        assertTrue(source.contains("00:00:00.200 --> 00:00:01.200"))
        assertTrue(!source.contains("00:00:00.300 --> 00:00:01.300"))
    }
}

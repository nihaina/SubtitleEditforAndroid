package com.subtitleedit

import com.subtitleedit.editor.EditorSourceTextEdit
import com.subtitleedit.editor.EditorSourceWaveformSyncController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EditorSourceWaveformSyncControllerTest {
    @Test fun rapidFinalsOnDifferentRowsRetainBothChangesAndSeparateUndoSteps() = runBlocking {
        val fixture = Fixture(this, debounce = 60_000L)
        try {
            fixture.gesture(1L, 0, EditorTimingRange(1_100L, 2_100L, false))
            fixture.gesture(2L, 1, EditorTimingRange(3_200L, 4_200L, false))
            assertEquals(SRT, fixture.model.sourceViewContent)
            fixture.controller.flushNow()

            val first = SRT.replace("01,000 --> 00:00:02,000", "01,100 --> 00:00:02,100")
            val second = first.replace("03,000 --> 00:00:04,000", "03,200 --> 00:00:04,200")
            assertEquals(listOf(1L, 2L), fixture.finals.map { it.first })
            assertEquals(listOf(SRT, first), fixture.finals.map { it.second })
            assertEquals(second, fixture.visible)
            assertFalse(fixture.model.waveformSourceEditsPending)
            assertEquals(first, fixture.undoSource())
            assertEquals(SRT, fixture.undoSource())
            assertNull(fixture.model.peekUndo())
        } finally { fixture.controller.cancel() }
    }

    @Test fun rapidFinalsOnSameRowUseThePreviousFinalAsTheirSourceBaseline() = runBlocking {
        val fixture = Fixture(this, debounce = 60_000L)
        try {
            fixture.gesture(3L, 0, EditorTimingRange(1_100L, 2_100L, false))
            fixture.gesture(4L, 0, EditorTimingRange(1_200L, 2_200L, false))
            fixture.controller.flushNow()
            val first = SRT.replace("01,000 --> 00:00:02,000", "01,100 --> 00:00:02,100")
            val second = SRT.replace("01,000 --> 00:00:02,000", "01,200 --> 00:00:02,200")
            assertEquals(second, fixture.visible)
            assertEquals(first, fixture.undoSource())
            assertEquals(SRT, fixture.undoSource())
            assertNull(fixture.model.peekUndo())
        } finally { fixture.controller.cancel() }
    }

    @Test fun previewsCoalesceWhileFinalStillRecordsTheEntireGesture() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.begin(5L)
            repeat(20) { index -> fixture.update(5L, EditorTimingRange(1_010L + index * 10, 2_010L + index * 10, false)) }
            fixture.awaitIdle()
            assertEquals(1, fixture.updates.size)
            assertNull(fixture.model.peekUndo())
            fixture.finish(5L)
            fixture.awaitIdle()
            assertEquals(listOf(5L), fixture.finals.map { it.first })
            assertEquals(SRT, fixture.undoSource())
            assertNull(fixture.model.peekUndo())
        } finally { fixture.controller.cancel() }
    }

    @Test fun aSecondGestureCannotSupersedeAnInFlightFirstFinal() = runBlocking {
        val gate = CalculationGate()
        val fixture = Fixture(this, build = gate::build)
        try {
            fixture.gesture(15L, 0, EditorTimingRange(1_100L, 2_100L, false))
            withTimeout(5_000L) { gate.started.await() }
            fixture.gesture(16L, 1, EditorTimingRange(3_200L, 4_200L, false))
            gate.release.countDown()
            fixture.awaitIdle()
            val first = SRT.replace("01,000 --> 00:00:02,000", "01,100 --> 00:00:02,100")
            assertEquals(listOf(15L, 16L), fixture.finals.map { it.first })
            assertEquals(first.replace("03,000 --> 00:00:04,000", "03,200 --> 00:00:04,200"), fixture.visible)
            assertEquals(first, fixture.undoSource())
            assertEquals(SRT, fixture.undoSource())
            assertNull(fixture.model.peekUndo())
        } finally {
            gate.release.countDown()
            fixture.controller.cancel()
        }
    }

    @Test fun returningToOriginalAfterDisplayedPreviewRestoresSourceWithoutHistoryOrDirtyState() = runBlocking {
        val fixture = Fixture(this)
        try {
            val original = fixture.model.subtitleEntries.first().copy()
            fixture.begin(6L)
            fixture.update(6L, EditorTimingRange(1_000L, 2_400L, true))
            fixture.awaitIdle()
            assertNotEquals(SRT, fixture.visible)
            fixture.update(6L, EditorTimingRange(original.startTime, original.endTime, true))
            fixture.finish(6L)
            fixture.awaitIdle()
            assertEquals(SRT, fixture.visible)
            assertEquals(original, fixture.model.subtitleEntries.first())
            assertFalse(fixture.model.hasUnsavedChanges)
            assertNull(fixture.model.peekUndo())
        } finally { fixture.controller.cancel() }
    }

    @Test fun flushBarrierCommitsLatestRangeAndCancelledCalculationCannotApplyLater() = runBlocking {
        val gate = CalculationGate()
        val fixture = Fixture(this, build = gate::build)
        try {
            fixture.begin(7L)
            fixture.update(7L, EditorTimingRange(1_100L, 2_100L, false))
            withTimeout(5_000L) { gate.started.await() }
            fixture.update(7L, EditorTimingRange(1_200L, 2_200L, false))
            fixture.finish(7L)
            fixture.controller.flushNow()
            val committed = fixture.visible
            assertTrue(committed.contains("00:00:01,200 --> 00:00:02,200"))
            assertEquals(1, fixture.finals.size)
            gate.release.countDown()
            fixture.controller.flushAndJoin()
            delay(20L)
            assertEquals(committed, fixture.visible)
            assertEquals(1, fixture.updates.size)
            assertEquals(SRT, fixture.undoSource())
        } finally {
            gate.release.countDown()
            fixture.controller.cancel()
        }
    }

    @Test fun sourceGenerationChangeRejectsAnAlreadyRunningCalculation() = runBlocking {
        val gate = CalculationGate()
        val fixture = Fixture(this, build = gate::build)
        try {
            fixture.begin(8L)
            fixture.update(8L, EditorTimingRange(1_100L, 2_100L, false))
            fixture.finish(8L)
            withTimeout(5_000L) { gate.started.await() }
            val typed = SRT.replace("first", "typed")
            fixture.model.setSourceDocumentContent(typed)
            fixture.model.documentState.sourceViewEditGeneration++
            gate.release.countDown()
            fixture.awaitIdle()
            assertEquals(typed, fixture.model.sourceViewContent)
            assertTrue(fixture.updates.isEmpty())
            assertNull(fixture.model.peekUndo())
        } finally {
            gate.release.countDown()
            fixture.model.discardWaveformSourceEdits()
            fixture.controller.cancel()
        }
    }

    @Test fun replacedDocumentRejectsOldCalculationAndHistory() = runBlocking {
        val gate = CalculationGate()
        val fixture = Fixture(this, build = gate::build)
        try {
            fixture.gesture(9L, 0, EditorTimingRange(1_100L, 2_100L, false))
            withTimeout(5_000L) { gate.started.await() }
            val replacement = SRT.replace("first", "new document")
            fixture.model.loadSubtitleContent(replacement, "replacement.srt")
            assertFalse(fixture.model.waveformSourceEditsPending)
            gate.release.countDown()
            fixture.awaitIdle()
            assertEquals(replacement, fixture.model.sourceViewContent)
            assertTrue(fixture.updates.isEmpty())
            assertNull(fixture.model.peekUndo())
        } finally {
            gate.release.countDown()
            fixture.model.discardWaveformSourceEdits()
            fixture.controller.cancel()
        }
    }

    @Test fun replacingDocumentWhileGestureIsActiveAllowsANewGestureImmediately() = runBlocking {
        val fixture = Fixture(this, debounce = 60_000L)
        try {
            fixture.begin(13L)
            fixture.update(13L, EditorTimingRange(1_100L, 2_100L, false))
            fixture.model.loadSubtitleContent(SRT.replace("first", "new document"), "replacement.srt")
            fixture.visible = fixture.model.sourceViewContent
            assertFalse(fixture.model.waveformSourceEditsPending)
            fixture.begin(14L)
            assertNull(fixture.model.finishWaveformDrag(13L, emptySet()))
            fixture.finish(14L)
            fixture.controller.flushNow()
            assertEquals(SRT.replace("first", "new document"), fixture.visible)
            assertNull(fixture.model.peekUndo())
        } finally { fixture.controller.cancel() }
    }

    @Test fun sourceTypingCommitsOnlyDisplayedPreviewAndCancelsUnshownTimingResults() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.begin(10L)
            fixture.update(10L, EditorTimingRange(1_100L, 2_100L, false))
            fixture.awaitIdle()
            val displayed = fixture.visible
            fixture.update(10L, EditorTimingRange(1_200L, 2_200L, false))
            fixture.controller.finishVisibleAndCancel()
            fixture.model.discardWaveformSourceEdits()
            assertNull(fixture.model.finishWaveformDrag(10L, emptySet()))
            val typed = displayed.replace("first", "typed")
            fixture.model.recordSourceHistory(EditorSourceTextEdit.between(displayed, typed), "typing")
            fixture.model.setSourceDocumentContent(typed)
            fixture.model.documentState.sourceViewEditGeneration++
            fixture.controller.flushAndJoin()
            assertEquals(typed, fixture.model.sourceViewContent)
            assertEquals(displayed, fixture.undoSource())
            assertEquals(SRT, fixture.undoSource())
            assertNull(fixture.model.peekUndo())
        } finally { fixture.controller.cancel() }
    }

    @Test fun timingCommitPreservesRawVttSectionsAndCrlf() = runBlocking {
        val source = "\uFEFFWEBVTT\r\n\r\nSTYLE\r\n::cue { color: lime; }\r\n\r\n" +
            "cue-id\r\n00:01.000 --> 00:02.000 line:70% region:r1\r\nbody\r\n\r\nNOTE footer\r\nretained\r\n"
        val fixture = Fixture(this, source, "test.vtt", debounce = 60_000L)
        try {
            fixture.gesture(11L, 0, EditorTimingRange(1_200L, 2_200L, false))
            fixture.controller.flushNow()
            assertEquals(source.replace("00:01.000 --> 00:02.000", "00:00:01.200 --> 00:00:02.200"), fixture.visible)
            assertEquals(source, fixture.undoSource())
        } finally { fixture.controller.cancel() }
    }

    @Test fun resizingLrcEndRetainsMetadataAndCreatesAReversibleSourcePatch() = runBlocking {
        val source = "[ar:artist]\r\n[offset:15]\r\n\r\n[00:01.00]first\r\n[00:02.00]\r\n"
        val fixture = Fixture(this, source, "test.lrc", debounce = 60_000L)
        try {
            val row = fixture.model.subtitleEntries.single()
            fixture.gesture(12L, 0, EditorTimingRange(row.startTime, row.endTime + 500L, true))
            fixture.controller.flushNow()
            assertTrue(row.endTimeModified)
            assertEquals(source.replace("[00:02.00]", "[00:02.50]"), fixture.visible)
            assertEquals(source, fixture.undoSource())
        } finally { fixture.controller.cancel() }
    }

    private class CalculationGate {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        private val first = AtomicBoolean(true)
        fun build(drag: EditorTimingDrag, range: EditorTimingRange, source: String): String {
            if (first.compareAndSet(true, false)) {
                started.complete(Unit)
                check(release.await(5L, TimeUnit.SECONDS)) { "Timed out waiting for test calculation" }
            }
            return EditorTimingDragSession.sourceFor(drag, range, source)
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        source: String = SRT,
        name: String = "test.srt",
        debounce: Long = 0L,
        build: (EditorTimingDrag, EditorTimingRange, String) -> String = EditorTimingDragSession::sourceFor
    ) {
        val model = EditorViewModel().apply {
            loadSubtitleContent(source, name)
            setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
            isSourceViewMode = true
        }
        var visible = source
        val updates = mutableListOf<String>()
        val finals = mutableListOf<Pair<Long, String>>()
        val controller = EditorSourceWaveformSyncController(
            scope, { model.sourceViewContent }, { model.documentState.sourceViewEditGeneration },
            model::isCurrentWaveformDrag,
            { drag, base, updated, final ->
                model.applyWaveformSource(drag, base, updated, final).also { accepted ->
                    if (accepted && final) finals += drag.key to base
                }
            },
            { updated, edit ->
                assertEquals(updated, edit.applyTo(visible, false))
                visible = updated
                updates += updated
            }, debounce, build
        )
        fun begin(key: Long, position: Int = 0) {
            assertTrue(model.beginWaveformDrag(key, model.subtitleEntries[position].stableId, emptySet()))
        }
        fun update(key: Long, range: EditorTimingRange) {
            assertNotNull(model.updateWaveformDrag(key, range))
            val drag = model.waveformDrag(key)!!
            controller.preview(drag, drag.range)
        }
        fun finish(key: Long) { controller.commit(model.finishWaveformDrag(key, emptySet())!!.drag) }
        fun gesture(key: Long, position: Int, range: EditorTimingRange) {
            begin(key, position)
            update(key, range)
            finish(key)
        }
        suspend fun awaitIdle() = withTimeout(5_000L) { while (controller.hasPending) delay(1L) }
        fun undoSource(): String {
            assertTrue(model.undo(true) { operation, undo ->
                val edit = (operation as EditorEditHistory.Operation.SourceChange).edit
                val source = edit.applyTo(model.sourceViewContent, undo)
                model.originalFileContent = source
                model.sourceViewContent = source
            })
            return model.sourceViewContent
        }
    }

    private companion object {
        const val SRT = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nfirst\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nsecond\r\n"
    }
}

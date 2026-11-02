package com.subtitleedit

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.view.WaveformTimelineView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real touch dispatch coverage; no media playback or external files are needed. */
@RunWith(AndroidJUnit4::class)
class WaveformTimingDragTest {
    @Test fun beginAndUpdateCallbacksRunBeforeTheSharedModelIsMutated() = onMain {
        val fixture = Fixture()
        try {
            val original = fixture.model.subtitleEntries.single().copy()
            fixture.beforeEvent = { event ->
                if (event is EditorWaveformDragEvent.Begin || event is EditorWaveformDragEvent.Update) {
                    assertEquals(original, fixture.model.subtitleEntries.single())
                }
            }
            fixture.touch(MotionEvent.ACTION_DOWN, 200f)
            fixture.touch(MotionEvent.ACTION_MOVE, 220f)
            assertEquals(1_200L, fixture.model.subtitleEntries.single().startTime)
            fixture.beforeEvent = {}
            fixture.touch(MotionEvent.ACTION_MOVE, 240f)
            fixture.touch(MotionEvent.ACTION_UP, 240f)
            assertEquals(1, fixture.events.filterIsInstance<EditorWaveformDragEvent.Begin>().size)
            assertEquals(1, fixture.events.filterIsInstance<EditorWaveformDragEvent.Finish>().size)
            assertEquals(1_400L, fixture.model.subtitleEntries.single().startTime)
            assertEquals(1_000L, original.startTime)
        } finally { fixture.view.release() }
    }

    @Test fun cancelCommitsTheCurrentRangeAndFurtherFinishesAreIdempotent() = onMain {
        val fixture = Fixture()
        try {
            fixture.touch(MotionEvent.ACTION_DOWN, 200f)
            fixture.touch(MotionEvent.ACTION_MOVE, 220f)
            fixture.touch(MotionEvent.ACTION_CANCEL, 220f)
            fixture.view.finishSubtitleDrag()
            fixture.view.release()
            assertEquals(1, fixture.events.filterIsInstance<EditorWaveformDragEvent.Finish>().size)
            assertEquals(1_200L, fixture.model.subtitleEntries.single().startTime)
            assertTrue(fixture.model.undo(false) { operation, undo -> fixture.model.executeHistoryCommand(operation, undo) })
            assertEquals(1_000L, fixture.model.subtitleEntries.single().startTime)
            assertNull(fixture.model.peekUndo())
        } finally { fixture.view.release() }
    }

    @Test fun resizingBackToOriginalThenMovingUsesTheNormalizedEndMarkerFlag() = onMain {
        val fixture = Fixture()
        try {
            fixture.touch(MotionEvent.ACTION_DOWN, 299f)
            fixture.touch(MotionEvent.ACTION_MOVE, 339f)
            assertTrue(fixture.model.subtitleEntries.single().endTimeModified)
            fixture.touch(MotionEvent.ACTION_MOVE, 299f)
            fixture.touch(MotionEvent.ACTION_UP, 299f)
            assertFalse(fixture.model.subtitleEntries.single().endTimeModified)
            assertNull(fixture.model.peekUndo())

            fixture.touch(MotionEvent.ACTION_DOWN, 200f)
            fixture.touch(MotionEvent.ACTION_MOVE, 220f)
            fixture.touch(MotionEvent.ACTION_UP, 220f)
            val row = fixture.model.subtitleEntries.single()
            assertEquals(1_200L, row.startTime)
            assertEquals(3_200L, row.endTime)
            assertFalse(row.endTimeModified)
            assertFalse((fixture.model.peekUndo() as EditorEditHistory.Operation.ListChange).updated.single().after.endTimeModified)
        } finally { fixture.view.release() }
    }

    @Test fun rejectedBeginLeavesTheRowAndNextGestureUntouched() = onMain {
        val fixture = Fixture()
        try {
            fixture.view.onSubtitleDragListener = { event -> fixture.events += event; false }
            fixture.touch(MotionEvent.ACTION_DOWN, 200f)
            fixture.touch(MotionEvent.ACTION_MOVE, 220f)
            fixture.touch(MotionEvent.ACTION_UP, 220f)
            assertEquals(1, fixture.events.size)
            assertEquals(1_000L, fixture.model.subtitleEntries.single().startTime)
            assertNull(fixture.model.peekUndo())
            fixture.bind()
            fixture.touch(MotionEvent.ACTION_DOWN, 200f)
            fixture.touch(MotionEvent.ACTION_MOVE, 220f)
            fixture.touch(MotionEvent.ACTION_UP, 220f)
            assertEquals(1_200L, fixture.model.subtitleEntries.single().startTime)
        } finally { fixture.view.release() }
    }

    @Test fun rejectedUpdateRestoresTheAuthoritativeRowForTheNextGesture() = onMain {
        val fixture = Fixture()
        try {
            fixture.view.onSubtitleDragListener = { event ->
                fixture.events += event
                event !is EditorWaveformDragEvent.Update
            }
            fixture.touch(MotionEvent.ACTION_DOWN, 200f)
            fixture.touch(MotionEvent.ACTION_MOVE, 240f)
            fixture.touch(MotionEvent.ACTION_UP, 240f)
            assertEquals(1, fixture.events.filterIsInstance<EditorWaveformDragEvent.Finish>().size)
            fixture.bind()
            fixture.touch(MotionEvent.ACTION_DOWN, 200f)
            fixture.touch(MotionEvent.ACTION_MOVE, 220f)
            fixture.touch(MotionEvent.ACTION_UP, 220f)
            assertEquals(1_200L, fixture.model.subtitleEntries.single().startTime)
        } finally { fixture.view.release() }
    }

    @Test fun finishCallbackCanRebindTheDocumentWithoutBeingOverwritten() = onMain {
        val fixture = Fixture()
        try {
            var replace = true
            fixture.afterFinish = {
                if (replace) {
                    replace = false
                    fixture.model.loadSubtitleContent("1\n00:00:04,000 --> 00:00:06,000\nreplacement\n", "new.srt")
                    fixture.view.setSubtitles(fixture.model.subtitleEntries)
                    fixture.view.setSelectedIndices(setOf(0))
                }
            }
            fixture.touch(MotionEvent.ACTION_DOWN, 200f)
            fixture.touch(MotionEvent.ACTION_MOVE, 220f)
            fixture.touch(MotionEvent.ACTION_UP, 220f)
            assertEquals(1, fixture.events.filterIsInstance<EditorWaveformDragEvent.Finish>().size)
            fixture.touch(MotionEvent.ACTION_DOWN, 500f)
            fixture.touch(MotionEvent.ACTION_MOVE, 520f)
            fixture.touch(MotionEvent.ACTION_UP, 520f)
            assertEquals("replacement", fixture.model.subtitleEntries.single().text)
            assertEquals(4_200L, fixture.model.subtitleEntries.single().startTime)
        } finally { fixture.view.release() }
    }

    @Test fun enteringTimestampingFinishesTheDragOnceBeforeRelease() = onMain {
        val fixture = Fixture()
        try {
            fixture.touch(MotionEvent.ACTION_DOWN, 200f)
            fixture.touch(MotionEvent.ACTION_MOVE, 220f)
            fixture.view.startTimestamping(5_000L)
            fixture.view.release()
            assertEquals(1, fixture.events.filterIsInstance<EditorWaveformDragEvent.Finish>().size)
            assertTrue(fixture.model.hasUnsavedChanges)
            assertEquals(1_200L, fixture.model.subtitleEntries.single().startTime)
        } finally { fixture.view.release() }
    }

    private class Fixture {
        val model = EditorViewModel().apply {
            loadSubtitleContent(SRT, "test.srt")
            setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), SRT, true)
        }
        val view = WaveformTimelineView(ApplicationProvider.getApplicationContext<Context>()).apply {
            layout(0, 0, 1_000, 400)
            initialize(10_000L, model.subtitleEntries)
            setSelectedIndices(setOf(0))
        }
        val events = mutableListOf<EditorWaveformDragEvent>()
        var beforeEvent: (EditorWaveformDragEvent) -> Unit = {}
        var afterFinish: () -> Unit = {}
        private var downTime = SystemClock.uptimeMillis()
        private var eventTime = downTime

        init { bind() }

        fun bind() {
            view.onSubtitleDragListener = { event ->
                events += event
                beforeEvent(event)
                when (event) {
                    is EditorWaveformDragEvent.Begin -> model.beginWaveformDrag(event.key, event.stableId, emptySet())
                    is EditorWaveformDragEvent.Update -> model.updateWaveformDrag(event.key, event.range) != null
                    is EditorWaveformDragEvent.Finish -> {
                        val accepted = model.finishWaveformDrag(event.key, emptySet()) != null
                        afterFinish()
                        accepted
                    }
                }
            }
        }

        fun touch(action: Int, x: Float) {
            eventTime += 16L
            if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
            val event = MotionEvent.obtain(downTime, eventTime, action, x, 399f, 0)
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private companion object {
        const val SRT = "1\n00:00:01,000 --> 00:00:03,000\nbody\n"
    }
}

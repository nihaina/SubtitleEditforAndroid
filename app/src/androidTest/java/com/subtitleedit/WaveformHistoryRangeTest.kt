package com.subtitleedit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.view.WaveformTimelineView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** History splices keep the waveform and the playback range owner in sync. */
@RunWith(AndroidJUnit4::class)
class WaveformHistoryRangeTest {
    @Test fun undoingTheLimitedAppendClearsPlaybackAfterInstallingTheFinalRows() = onMain {
        val model = loaded()
        model.commitListEdit(EditorCommand.Insert(model.subtitleEntries.size,
            listOf(SubtitleEntry(startTime = 7_000, endTime = 8_000, text = "appended"))), emptySet())!!
        val view = view(model.subtitleEntries)
        try {
            setLimitedIndex(view, 2)
            var notifications = 0
            view.onLimitedPlaybackRangeChange = { index ->
                notifications++
                assertNull(index)
                assertNull(limitedIndex(view))
                assertEquals(model.subtitleEntries, rows(view))
                assertEquals(2, rows(view).size)
            }
            var result: EditorHistoryCommandResult? = null

            assertTrue(model.undo(false) { operation, undo ->
                result = model.executeHistoryCommand(operation, undo)
            })
            val ranges = checkNotNull(result).ranges
            assertEquals(listOf(EditorListRangeChange(2, 1, emptyList())), ranges)
            ranges.forEach { range ->
                assertTrue(view.replaceSubtitleRange(range.start, range.removedCount, range.inserted))
            }

            assertEquals(1, notifications)
            assertNull(limitedIndex(view))
            assertEquals(SRT, model.originalFileContent)
            assertTrue(view.replaceSubtitleRange(0, 0, emptyList()))
            assertEquals(1, notifications)
        } finally { view.release() }
    }

    @Test fun splicesBeforeTheLimitedRowKeepTheSameTargetWithoutClearingPlayback() = onMain {
        val originals = listOf(
            SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "first"),
            SubtitleEntry(startTime = 3_000, endTime = 4_000, text = "second"),
            SubtitleEntry(startTime = 5_000, endTime = 6_000, text = "limited")
        )
        val view = view(originals)
        try {
            setLimitedIndex(view, 2)
            val notifications = mutableListOf<Int?>()
            view.onLimitedPlaybackRangeChange = { notifications += it }

            assertTrue(view.replaceSubtitleRange(0, 1, emptyList()))
            assertEquals(1, limitedIndex(view))
            assertSame(originals[2], rows(view)[1])
            assertTrue(notifications.isEmpty())

            val inserted = SubtitleEntry(startTime = 0, endTime = 500, text = "inserted")
            assertTrue(view.replaceSubtitleRange(0, 0, listOf(inserted)))
            assertEquals(2, limitedIndex(view))
            assertSame(originals[2], rows(view)[2])
            assertTrue(notifications.isEmpty())
        } finally { view.release() }
    }

    @Test fun invalidSplicesLeaveTheRangeAndRowsUntouchedWithoutNotifyingPlayback() = onMain {
        val model = loaded()
        val view = view(model.subtitleEntries)
        try {
            setLimitedIndex(view, 1)
            val originalRows = rows(view)
            var notifications = 0
            view.onLimitedPlaybackRangeChange = { notifications++ }

            assertFalse(view.replaceSubtitleRange(-1, 0, emptyList()))
            assertFalse(view.replaceSubtitleRange(0, -1, emptyList()))
            assertFalse(view.replaceSubtitleRange(1, 2, emptyList()))
            assertFalse(view.replaceSubtitleRange(3, 0, emptyList()))

            assertEquals(0, notifications)
            assertEquals(1, limitedIndex(view))
            assertSame(originalRows, rows(view))
            assertEquals(model.subtitleEntries, originalRows)
        } finally { view.release() }
    }

    private fun loaded() = EditorViewModel().apply {
        loadSubtitleContent(SRT, "test.srt")
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), SRT, true)
    }

    private fun view(entries: List<SubtitleEntry>) =
        WaveformTimelineView(ApplicationProvider.getApplicationContext<Context>()).apply {
            layout(0, 0, 1_000, 400)
            initialize(10_000, entries)
        }

    private fun setLimitedIndex(view: WaveformTimelineView, index: Int?) {
        view.javaClass.getDeclaredField("limitedPlaybackIndex").apply { isAccessible = true }.set(view, index)
    }

    private fun limitedIndex(view: WaveformTimelineView): Int? =
        view.javaClass.getDeclaredField("limitedPlaybackIndex").apply { isAccessible = true }.get(view) as Int?

    @Suppress("UNCHECKED_CAST")
    private fun rows(view: WaveformTimelineView): List<SubtitleEntry> =
        view.javaClass.getDeclaredField("subtitles").apply { isAccessible = true }.get(view) as List<SubtitleEntry>

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    private companion object {
        const val SRT = "1\n00:00:01,000 --> 00:00:02,000\nfirst\n\n" +
            "2\n00:00:03,000 --> 00:00:04,000\nsecond\n"
    }
}

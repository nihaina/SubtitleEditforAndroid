package com.subtitleedit

import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.editor.EditorSearchController
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SearchReplaceEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorSearchIncrementalTest {
    @Test fun touchTimingDragUnderSearchStaysLocalAndKeepsUndoRedo() {
        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            val size = 30 // Keep history synchronous; large-document costs are measured separately.
            val source = buildString {
                repeat(size) { index ->
                    val start = index * 10_000L + 1_000L
                    append("${index + 1}\n${com.subtitleedit.util.TimeUtils.formatSRT(start)} --> ")
                    append("${com.subtitleedit.util.TimeUtils.formatSRT(start + 2_000L)}\nneedle row $index\n\n")
                }
            }
            scenario.onActivity { activity ->
                val parse = activity.javaClass.declaredMethods.single { it.name == "parseContent" }
                parse.isAccessible = true
                parse.invoke(activity, source, "touch-drag.srt", null, null)
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            var installed = false
            while (!installed && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { installed = it.findViewById<RecyclerView>(R.id.rvSubtitles).adapter!!.itemCount == size }
                if (!installed) android.os.SystemClock.sleep(20L)
            }
            assertTrue(installed)
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[EditorViewModel::class.java]
                val waveform = activity.findViewById<com.subtitleedit.view.WaveformTimelineView>(R.id.waveformTimelineView)
                val adapter = activity.findViewById<RecyclerView>(R.id.rvSubtitles).adapter!!
                waveform.layout(0, 0, 1_000, 400)
                waveform.initialize(10_000L, model.subtitleEntries)
                waveform.setSelectedIndices(setOf(0))
                activity.findViewById<View>(R.id.searchBar).visibility = View.VISIBLE
                activity.findViewById<android.widget.EditText>(R.id.etSearch).setText("needle")
                var fullRanges = 0
                val observer = object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) { if (count > 3) fullRanges++ }
                }
                adapter.registerAdapterDataObserver(observer)
                try {
                    val downTime = android.os.SystemClock.uptimeMillis()
                    fun touch(action: Int, x: Float, elapsed: Long) {
                        val event = MotionEvent.obtain(downTime, downTime + elapsed, action, x, 399f, 0)
                        try { assertTrue(waveform.onTouchEvent(event)) } finally { event.recycle() }
                    }
                    touch(MotionEvent.ACTION_DOWN, 200f, 0)
                    touch(MotionEvent.ACTION_MOVE, 220f, 16)
                    touch(MotionEvent.ACTION_MOVE, 240f, 32)
                    touch(MotionEvent.ACTION_UP, 240f, 48)
                    assertEquals(1_400L, model.subtitleEntries[0].startTime)
                    assertEquals(0, fullRanges)
                    val method = activity.javaClass.getDeclaredMethod("undoEdit").apply { isAccessible = true }
                    method.invoke(activity)
                    assertEquals(1_000L, model.subtitleEntries[0].startTime)
                    assertNull("One gesture must make exactly one history operation", model.peekUndo())
                    activity.javaClass.getDeclaredMethod("redoEdit").apply { isAccessible = true }.invoke(activity)
                    assertEquals(1_400L, model.subtitleEntries[0].startTime)
                    assertEquals(0, fullRanges)
                } finally {
                    adapter.unregisterAdapterDataObserver(observer)
                }
            }
        }
    }

    @Test fun removingTheCurrentRowHighlightsItsReplacementEvenWhenThePositionIsUnchanged() {
        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            lateinit var h: Harness
            val rows = (0..2).map { SubtitleEntry(text = "needle $it") }
            scenario.onActivity { activity ->
                h = Harness(activity, rows)
                h.binding.rvSubtitles.adapter = h.adapter
                (h.binding.rvSubtitles.parent as android.view.ViewGroup).removeView(h.binding.rvSubtitles)
                activity.setContentView(h.binding.rvSubtitles)
                h.search("needle")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                h.input = CountingList(rows.drop(1))
                h.adapter.submitList(rows.drop(1))
                h.controller.onDocumentChanged()
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            var installed = false
            while (!installed && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { installed = h.adapter.itemCount == 2 }
                if (!installed) android.os.SystemClock.sleep(20L)
            }
            assertTrue(installed)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val row = h.binding.rvSubtitles.findViewHolderForAdapterPosition(0)!!.itemView
                assertEquals(ContextCompat.getColor(activity, R.color.primary_container),
                    (row.background as android.graphics.drawable.ColorDrawable).color)
            }
        }
    }

    @Test fun unchangedMatchesReadOnlyTheEditedRowAndDoNotRefreshSearchHighlights() = withActivity { activity ->
        val rows = MutableList(20_000) { SubtitleEntry(index = it + 1, startTime = it * 3_000L, text = "needle $it") }
        val h = Harness(activity, rows)
        h.search("needle")
        val results = h.engine.results
        h.input.reads = 0
        h.notifications.clear()
        repeat(50) {
            rows[10_000].startTime++
            h.controller.onDocumentChanged(listOf(10_000))
        }
        assertEquals(50, h.input.reads)
        assertSame(results, h.engine.results)
        assertTrue(h.notifications.isEmpty())
        assertEquals(20_000, h.engine.results.size)
    }

    @Test fun timestampMembershipChangesRetainTheCurrentOrPreviousSurvivingResult() = withActivity { activity ->
        val rows = MutableList(4) { SubtitleEntry(index = it + 1, startTime = it * 1_000L, text = "row $it") }
        rows[1].startTime++
        val h = Harness(activity, rows)
        h.search(".000")
        assertEquals(listOf(0, 2, 3), h.engine.results)
        h.binding.btnSearchNext.performClick()
        assertEquals(2, h.engine.currentResultPositionOrNull())
        rows[1].startTime = 1_000L
        h.controller.onDocumentChanged(listOf(1))
        assertEquals(listOf(0, 1, 2, 3), h.engine.results)
        assertEquals(2, h.engine.currentResultPositionOrNull())
        rows[2].startTime++
        rows[1].startTime++
        h.controller.onDocumentChanged(listOf(2, 1, 2))
        assertEquals(listOf(0, 3), h.engine.results)
        assertEquals(0, h.engine.currentResultPositionOrNull())
        rows[0].startTime++
        h.controller.onDocumentChanged(listOf(0))
        assertEquals(3, h.engine.currentResultPositionOrNull())
        rows[3].startTime++
        h.controller.onDocumentChanged(listOf(3))
        assertTrue(h.engine.results.isEmpty())
        assertNull(h.engine.currentResultPositionOrNull())
        assertEquals(activity.getString(R.string.search_result_position, 0, 0), h.binding.tvSearchResultCount.text.toString())
    }

    @Test fun queryOptionsAndDocumentReplacementsInvalidateTheLocalSearchCache() = withActivity { activity ->
        val rows = mutableListOf(SubtitleEntry(text = "Cat"), SubtitleEntry(text = "scatter"), SubtitleEntry(text = "cat"))
        val h = Harness(activity, rows)
        h.search("cat")
        assertEquals(listOf(0, 1, 2), h.engine.results)
        h.binding.btnSearchMatchCase.performClick()
        h.binding.btnSearchWholeWord.performClick()
        assertEquals(listOf(2), h.engine.results)
        rows[0].text = "cat"
        h.controller.onDocumentChanged(listOf(0))
        assertEquals(listOf(0, 2), h.engine.results)
        rows.add(SubtitleEntry(text = "cat"))
        h.controller.onDocumentChanged(listOf(3))
        assertEquals(listOf(0, 2, 3), h.engine.results)
        h.input = CountingList(listOf(SubtitleEntry(text = "none"), SubtitleEntry(text = "cat")))
        h.controller.onDocumentChanged(listOf(0))
        assertEquals(listOf(1), h.engine.results)
        h.binding.etSearch.setText("")
        h.input.reads = 0
        h.controller.onDocumentChanged(listOf(0))
        assertEquals(0, h.input.reads)
        assertTrue(h.engine.results.isEmpty())
    }

    @Test fun highlightChangesTouchOnlyOldAndNewRowsAndKeepPlaybackAndTextSpansCorrect() = withActivity { activity ->
        val h = Harness(activity, listOf(SubtitleEntry(text = "needle zero"), SubtitleEntry(text = "needle one")))
        val adapter = h.adapter
        val holders = (0..1).map { adapter.onCreateViewHolder(h.binding.rvSubtitles, 0) }
        h.notifications.clear()
        adapter.highlightSearchResult(0, "needle")
        assertEquals(listOf(Triple(0, 1, SubtitleAdapter.PAYLOAD_SEARCH_HIGHLIGHT)), h.notifications)
        adapter.onBindViewHolder(holders[0], 0)
        val firstText = holders[0].itemView.findViewById<TextView>(R.id.tvSubtitleText)
        assertEquals(1, (firstText.text as Spanned).getSpans(0, firstText.length(), BackgroundColorSpan::class.java).size)
        h.notifications.clear()
        adapter.highlightSearchResult(0, "needle")
        assertTrue(h.notifications.isEmpty())
        adapter.highlightCurrentPlaying(0)
        adapter.onBindViewHolder(holders[0], 0, mutableListOf(SubtitleAdapter.PAYLOAD_PLAYING))
        assertEquals(ContextCompat.getColor(activity, R.color.primary_container),
            (holders[0].itemView.background as android.graphics.drawable.ColorDrawable).color)
        h.notifications.clear()
        adapter.highlightSearchResult(1, "needle")
        assertEquals(setOf(0, 1), h.notifications.map { it.first }.toSet())
        adapter.onBindViewHolder(holders[0], 0, mutableListOf(SubtitleAdapter.PAYLOAD_SEARCH_HIGHLIGHT))
        assertEquals(ContextCompat.getColor(activity, R.color.playing_highlight),
            (holders[0].itemView.background as android.graphics.drawable.ColorDrawable).color)
        assertFalse(firstText.text is Spanned && (firstText.text as Spanned)
            .getSpans(0, firstText.length(), BackgroundColorSpan::class.java).isNotEmpty())
        h.notifications.clear()
        adapter.clearSearchHighlight()
        assertEquals(listOf(Triple(1, 1, SubtitleAdapter.PAYLOAD_SEARCH_HIGHLIGHT)), h.notifications)
        h.notifications.clear()
        adapter.clearSearchHighlight()
        assertTrue(h.notifications.isEmpty())
    }

    private class CountingList(private val rows: List<SubtitleEntry>) : AbstractList<SubtitleEntry>() {
        var reads = 0
        override val size get() = rows.size
        override fun get(index: Int): SubtitleEntry { reads++; return rows[index] }
    }

    private class Harness(activity: EditorActivity, rows: List<SubtitleEntry>) {
        val binding = ActivityEditorBinding.inflate(activity.layoutInflater)
        var input = CountingList(rows)
        val adapter = SubtitleAdapter({ _, _ -> }, { _, _ -> }, { _, _, _ -> },
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        val notifications = mutableListOf<Triple<Int, Int, Any?>>()
        val controller = EditorSearchController(activity, binding, adapter, { false }, { false }, { input },
            {}, { 0 }, { _, _ -> }, {})
        val engine get() = controller.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(controller) as SearchReplaceEngine
        init {
            binding.rvSubtitles.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
            adapter.submitList(rows.toList())
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) { notifications += Triple(start, count, payload) }
            })
        }
        fun search(query: String) { binding.searchBar.visibility = View.VISIBLE; binding.etSearch.setText(query) }
    }

    private fun withActivity(block: (EditorActivity) -> Unit) {
        ActivityScenario.launch(EditorActivity::class.java).use { scenario -> scenario.onActivity(block) }
    }
}

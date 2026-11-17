package com.subtitleedit

import android.os.Debug
import android.view.View
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.util.TimeUtils
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the Activity's production MOVE callback, independently of gesture-start/history costs. */
@RunWith(AndroidJUnit4::class)
class EditorSearchDragPerformanceTest {
    @Test fun measureTimingUpdatesWithAndWithoutAnActiveSearch() {
        val reports = mutableListOf<String>()
        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            for (size in listOf(1_000, 5_000, 20_000)) {
                val source = buildString {
                    repeat(size) { index ->
                        append("${index + 1}\n${TimeUtils.formatSRT(index * 3_000L)} --> ${TimeUtils.formatSRT(index * 3_000L + 2_000L)}\n")
                        append("Subtitle row $index with needle text\n\n")
                    }
                }
                scenario.onActivity { activity ->
                    val parse = activity.javaClass.declaredMethods.single { it.name == "parseContent" }
                    parse.isAccessible = true
                    parse.invoke(activity, *arrayOf(source, "drag.srt", null, null))
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000L
                var installed = false
                while (!installed && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { installed = it.findViewById<RecyclerView>(R.id.rvSubtitles).adapter!!.itemCount == size }
                    if (!installed) android.os.SystemClock.sleep(20L)
                }
                assertTrue("List diff must be installed before measuring", installed)
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[EditorViewModel::class.java]
                    val adapter = activity.findViewById<RecyclerView>(R.id.rvSubtitles).adapter!!
                    val search = activity.findViewById<EditText>(R.id.etSearch)
                    val target = model.subtitleEntries[size / 2]
                    val fullRanges = mutableListOf<Int>()
                    val observer = object : RecyclerView.AdapterDataObserver() {
                        override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) {
                            if (count == size) fullRanges += count
                        }
                    }
                    adapter.registerAdapterDataObserver(observer)
                    try {
                        for ((mode, query) in listOf("closed" to "", "text" to "needle", "time" to ".000")) {
                            activity.findViewById<View>(R.id.searchBar).visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                            search.setText(query)
                            fullRanges.clear()
                            val key = size.toLong() + mode.hashCode()
                            assertEquals(true, event(activity, EditorWaveformDragEvent.Begin(key, target.stableId)))
                            val times = mutableListOf<Long>()
                            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                            repeat(12) { step ->
                                val before = System.nanoTime()
                                assertEquals(true, event(activity, EditorWaveformDragEvent.Update(key,
                                    EditorTimingRange(target.startTime + 1L, target.endTime + 1L, false))))
                                times += System.nanoTime() - before
                            }
                            val bytes = allocated?.let { (Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - it) / times.size }
                            times.sort()
                            reports += String.format(Locale.US,
                                "rows=%d mode=%s median_ms=%.3f p90_ms=%.3f allocated_bytes_per_move=%s full_range_notifications=%d",
                                size, mode, times[times.size / 2] / 1e6, times[10] / 1e6, bytes, fullRanges.size)
                            assertEquals(true, event(activity, EditorWaveformDragEvent.Finish(key)))
                        }
                    } finally {
                        adapter.unregisterAdapterDataObserver(observer)
                        search.setText("")
                    }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val label = InstrumentationRegistry.getArguments().getString("measurementLabel") ?: "current"
        File(app.getExternalFilesDir(null), "search-drag-$label.txt").writeText(reports.joinToString("\n") + "\n")
        reports.forEach { android.util.Log.i("SearchDragPerformance", it) }
    }

    private fun event(activity: EditorActivity, event: EditorWaveformDragEvent): Any? =
        invoke(activity, "handleWaveformDrag", arrayOf(EditorWaveformDragEvent::class.java), event)

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, vararg arguments: Any?): Any? =
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(target, *arguments)
}

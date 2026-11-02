package com.subtitleedit

import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.view.SourceEditorView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Activity's real source change listener and incremental list renderer. */
@RunWith(AndroidJUnit4::class)
class EditorIncrementalEditingTest {
    @Test fun rangeReplacementReportsOnlyThePhysicalLinesTouchedByTheEdit() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = SourceEditorView(InstrumentationRegistry.getInstrumentation().targetContext)
            val source = (0 until 1_000).joinToString("\r\n") { "line $it" }
            editor.setDocumentText(source)
            var change: SourceEditorView.DocumentChange? = null
            editor.addOnDocumentChangeListener { change = it }
            val start = source.indexOf("line 500") + "line 500".length
            replaceRange(editor, start, start, "\r\ncontinued")
            assertEquals(1, change!!.oldLineCount)
            assertEquals(2, change!!.newLineCount)
            assertEquals(1_001, editor.getDocumentLineCount())
            assertEquals(source.replace("line 500\r\n", "line 500\r\ncontinued\r\n"), editor.getDocumentText())
            replaceRange(editor, start, start + "\r\ncontinued".length, "")
            assertEquals(2, change!!.oldLineCount)
            assertEquals(1, change!!.newLineCount)
            assertEquals(source, editor.getDocumentText())
        }
    }

    @Test fun rangeHintsPreserveTerminalRowsAndCarriageReturnBoundaries() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = SourceEditorView(InstrumentationRegistry.getInstrumentation().targetContext)
            val samples = listOf("", "a", "a\n", "a\r\nb\r\n", "\r\nx", "a\u2028b")
            samples.forEach { original ->
                val ranges = listOf(0 to 0, original.length to original.length, 0 to original.length) +
                    original.indices.map { it to it + 1 }
                ranges.forEach { (start, end) ->
                    listOf("", "X", "\n", "\r\n", "X\nY").forEach { replacement ->
                        editor.setDocumentText(original)
                        replaceRange(editor, start, end, replacement)
                        val expected = original.replaceRange(start, end, replacement)
                        assertEquals(expected, editor.getDocumentText())
                        val lines = expected.replace("\r\n", "\n").replace('\r', '\n')
                            .replace('\u2028', '\n').replace('\u2029', '\n').replace('\u0085', '\n').split('\n')
                        assertEquals("$original [$start,$end] $replacement", lines.size, editor.getDocumentLineCount())
                        lines.indices.forEach { assertEquals(lines[it], editor.getDocumentLineText(it)) }
                    }
                }
            }
        }
    }

    @Test fun sourceNewlineAndCueInsertionStayLocalAndKeepInputEnabled() {
        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            for (size in listOf(30, 5_000)) {
                val source = source(size)
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    invoke(activity, "parseContent", arrayOf(String::class.java, String::class.java), source, "test.srt")
                    invoke(activity, "enterSourceViewMode", arrayOf(kotlin.jvm.functions.Function0::class.java), null)
                }
                idle()
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[EditorViewModel::class.java]
                    val editor = activity.findViewById<SourceEditorView>(R.id.etSourceView)
                    val list = model.subtitleEntries
                    val rows = list.toList()
                    val snapshot = model.document.value
                    val position = size / 2
                    val offset = source.indexOf("row $position\n") + "row $position".length
                    val started = System.nanoTime()
                    replaceRange(editor, offset, offset, "\ncontinued")
                    report("source-newline rows=$size elapsedNs=${System.nanoTime() - started}")
                    assertEquals(size, model.subtitleEntries.size)
                    assertEquals("row $position\ncontinued", list[position].text)
                    assertSame(list, model.subtitleEntries)
                    assertSame(snapshot, model.document.value)
                    rows.indices.forEach { assertSame(rows[it], list[it]) }
                    assertTrue(editor.isEnabled)
                    assertTrue(model.sourceViewContent.contains("row $position\ncontinued"))

                    val inserted = "1\n00:00:00,000 --> 00:00:00,500\ninserted\n\n"
                    val cueStarted = System.nanoTime()
                    replaceRange(editor, 0, 0, inserted)
                    report("source-insert rows=$size elapsedNs=${System.nanoTime() - cueStarted}")
                    assertEquals(size + 1, model.subtitleEntries.size)
                    assertEquals("inserted", list.first().text)
                    assertSame(list, model.subtitleEntries)
                    // The existing one-cue-to-two-cue rule assigns new IDs to
                    // the replaced first block. The untouched suffix is reused.
                    (1 until rows.size).forEach { assertSame(rows[it], list[it + 1]) }
                    assertSame(snapshot, model.document.value)
                    assertTrue(editor.isEnabled)
                }
                idle()
            }
        }
    }

    @Test fun listInsertionDoesNotFullyRebindExistingRowsOrResetTheWaveform() {
        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                invoke(activity, "parseContent", arrayOf(String::class.java, String::class.java), source(30), "test.srt")
            }
            idle()
            var fullRefreshes = 0
            var fullSuffixBinds = 0
            lateinit var originalRows: List<com.subtitleedit.model.SubtitleEntry>
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[EditorViewModel::class.java]
                originalRows = model.subtitleEntries.toList()
                val adapter = activity.findViewById<RecyclerView>(R.id.rvSubtitles).adapter!!
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { fullRefreshes++ }
                    override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) {
                        if (payload == null && count > 3) fullSuffixBinds++
                    }
                })
                val started = System.nanoTime()
                invoke(activity, "insertSubtitle", arrayOf(Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!), true, 15, false)
                report("list-insert rows=30 elapsedNs=${System.nanoTime() - started}")
                assertEquals(31, model.subtitleEntries.size)
            }
            idle()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[EditorViewModel::class.java]
                assertEquals(0, fullRefreshes)
                assertEquals(0, fullSuffixBinds)
                originalRows.indices.forEach { index ->
                    assertSame(originalRows[index], model.subtitleEntries[if (index > 15) index + 1 else index])
                }
            }
        }
    }

    private fun replaceRange(editor: SourceEditorView, start: Int, end: Int, replacement: String) {
        invoke(editor, "replaceDocumentRange", arrayOf(Pair::class.java, String::class.java), start to end, replacement)
    }
    private fun report(metric: String) {
        Log.i("EditorIncrementalTest", metric)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("stream", "\nEDIT_COST $metric\n")
        })
    }
    private fun invoke(target: Any, name: String, types: Array<Class<*>>, vararg arguments: Any?) =
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(target, *arguments)
    private fun idle() {
        // Cursor/IME animations may keep the global queue non-idle indefinitely.
        // Let the submitted list diff finish, then cross one main-thread barrier.
        Thread.sleep(150L)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {}
    }
    private fun source(size: Int) = buildString {
        repeat(size) { index -> append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n") }
    }
}

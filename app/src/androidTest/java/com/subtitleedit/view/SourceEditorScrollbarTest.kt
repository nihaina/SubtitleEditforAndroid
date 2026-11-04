package com.subtitleedit.view

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.EditorActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToLong

/** Measures real source rows; no Activity, IME, or user document is required. */
@RunWith(AndroidJUnit4::class)
class SourceEditorScrollbarTest {
    @Test fun indexedHeightsMatchRealRowsForShortWrappedAndUnicodeText() {
        val lines = listOf("", "ordinary ASCII text", "00:00:01,000 --> 00:00:03,000",
            " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~",
            "a", "word ".repeat(120), "自动换行的长段落。".repeat(80), "emoji 😀 text 👨‍👩‍👧".repeat(30),
            "text\twith\ttabs", "a\u0301\u0308 combining marks")
        val editor = onMain {
            editor(lines.joinToString("\n")).also { layout(it, heightDp = 20_000) }
        }
        awaitIndexReady(editor)
        onMain {
            val manager = editor.layoutManager as LinearLayoutManager
            val actualHeight = lines.indices.sumOf { position ->
                manager.getDecoratedMeasuredHeight(manager.findViewByPosition(position)!!).toLong()
            }
            assertEquals(actualHeight, metrics(editor).range)
            assertEquals(lines.joinToString("\n"), editor.getDocumentText())
        }
    }

    @Test fun draggingThumbCanReachInsideOneWrappedPhysicalLineAndReverse() = onMain {
        val source = "wrapped source text ".repeat(2_000)
        val editor = editor(source)
        val manager = editor.layoutManager as LinearLayoutManager
        val row = manager.findViewByPosition(0)!!
        val input = row.findViewById<EditText>(R.id.etSourceLine)
        input.setSelection(17)
        val initialMetrics = metrics(editor)
        assertTrue(initialMetrics.range > initialMetrics.extent * 5L)
        assertEquals(manager.getDecoratedMeasuredHeight(row).toLong(), initialMetrics.range)
        assertEquals(1, editor.getDocumentLineCount())
        assertEquals(1, editor.computeVerticalScrollRange())

        // Each gesture enters the same production DOWN/MOVE/UP path as the right-edge handle.
        for (ratio in listOf(0.5f, 0.9f, 0.25f, 1f, 0f)) {
            dragThumb(editor, ratio)
            assertEquals(0, manager.findFirstVisibleItemPosition())
            val currentRow = manager.findViewByPosition(0)!!
            val actualOffset = (editor.paddingTop - manager.getDecoratedTop(currentRow)).toLong()
            val expectedOffset = ((initialMetrics.range - initialMetrics.extent) * ratio).roundToLong()
            assertNear(expectedOffset, actualOffset)
            assertNear(actualOffset, metrics(editor).offset)
            assertEquals(source, editor.getDocumentText())
            assertEquals(17, currentRow.findViewById<EditText>(R.id.etSourceLine).selectionStart)
            // Existing document-position APIs continue to expose their original line metrics.
            assertEquals(0, editor.getDocumentScrollOffset())
        }
    }

    @Test fun heightMappingWeightsWrappedRowsAndReachesTheLastRow() {
        val source = listOf("long wrapped row ".repeat(3_000))
            .plus((1..80).map { "short $it" }).joinToString("\n")
        val editor = onMain { editor(source) }
        awaitIndexReady(editor)
        onMain {
            val manager = editor.layoutManager as LinearLayoutManager
            val initialMetrics = metrics(editor)
            val firstHeight = manager.getDecoratedMeasuredHeight(manager.findViewByPosition(0)!!)
            assertTrue(firstHeight > (initialMetrics.range - initialMetrics.extent) / 2)

            dragThumb(editor, 0.5f)
            assertEquals("Half the rendered height is still inside the first physical row", 0,
                manager.findFirstVisibleItemPosition())
            assertNear((initialMetrics.range - initialMetrics.extent) / 2,
                (editor.paddingTop - manager.getDecoratedTop(manager.findViewByPosition(0)!!)).toLong())

            dragThumb(editor, 1f)
            val lastRow = manager.findViewByPosition(80)
            assertNotNull(lastRow)
            assertEquals(editor.height - editor.paddingBottom, manager.getDecoratedBottom(lastRow!!))
            assertEquals(source, editor.getDocumentText())
            assertEquals(81, editor.getDocumentLineCount())
        }
    }

    @Test fun directTextEditsAndWidthChangesInvalidateRenderedHeights() = onMain {
        val editor = editor("short")
        val shortRange = metrics(editor).range
        val input = editor.findViewById<EditText>(R.id.etSourceLine)
        val edited = "new long source text ".repeat(1_000)
        // Ordinary TextWatcher edits mutate the model without an adapter change notification.
        input.setText(edited)
        layout(editor)
        val wideRange = metrics(editor).range
        assertTrue(wideRange > shortRange * 5)
        assertEquals(edited, editor.getDocumentText())

        layout(editor, widthDp = 220)
        val narrowRange = metrics(editor).range
        assertTrue("Narrower text must wrap to a greater total height", narrowRange > wideRange)
        dragThumb(editor, 0.5f)
        val manager = editor.layoutManager as LinearLayoutManager
        val row = manager.findViewByPosition(0)!!
        val narrowed = metrics(editor)
        assertNear(((narrowed.range - narrowed.extent) * 0.5).roundToLong(),
            (editor.paddingTop - manager.getDecoratedTop(row)).toLong())

        editor.setDocumentText("short again")
        layout(editor, widthDp = 220)
        assertEquals(shortRange, metrics(editor).range)
        assertEquals("short again", editor.getDocumentText())
    }

    @Test fun viewportPaddingChangesUpdateScrollExtentWithoutChangingTextHeight() = onMain {
        val source = "source with wraps ".repeat(1_000)
        val editor = editor(source)
        val original = metrics(editor)
        editor.setPadding(editor.paddingLeft, editor.paddingTop, editor.paddingRight,
            editor.paddingBottom + dp(editor, 120))
        layout(editor)
        val padded = metrics(editor)
        assertEquals(original.range, padded.range)
        assertEquals(original.extent - dp(editor, 120), padded.extent)
        dragThumb(editor, 1f)
        val manager = editor.layoutManager as LinearLayoutManager
        assertEquals(editor.height - editor.paddingBottom,
            manager.getDecoratedBottom(manager.findViewByPosition(0)!!))
        assertEquals(source, editor.getDocumentText())
    }

    @Test fun firstMetricsForLargeChineseDocumentReturnBeforeBackgroundIndexCompletes() {
        // Vendor background freezers can suspend even an instrumented process with no window.
        // Keep the isolated test app foreground while its background index is being exercised.
        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            var foregroundContext: Context? = null
            scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                foregroundContext = it
            }
            checkLargeChineseDocument(20_000, foregroundContext!!)
            checkLargeChineseDocument(100_000, foregroundContext!!)
        }
    }

    private fun checkLargeChineseDocument(lineCount: Int, context: Context) {
        val source = (0 until lineCount).joinToString("\n") { index ->
            "$index " + "这是用于验证大文件滑动条不会在触摸线程测量全文的中文字幕。".repeat(4)
        }
        val editor = onMain {
            editor(source, context).also {
                val started = SystemClock.uptimeMillis()
                val initial = metrics(it)
                val elapsed = SystemClock.uptimeMillis() - started
                Log.i("SourceScrollbarTest", "rows=$lineCount firstMetricsMs=$elapsed")
                assertTrue("The first thumb query took ${elapsed}ms", elapsed < 1_000L)
                assertTrue(initial.range > initial.extent)
                assertTrue("Exact indexing must run after returning to the UI loop", !indexReady(it))
            }
        }
        // A second main-thread task can run while the expensive document index is in flight.
        onMain {
            val beforeDrag = SystemClock.uptimeMillis()
            dragThumb(editor, 0.5f)
            Log.i("SourceScrollbarTest", "rows=$lineCount firstDragMs=${SystemClock.uptimeMillis() - beforeDrag}")
            assertTrue("Dragging must not wait for the full height index",
                SystemClock.uptimeMillis() - beforeDrag < 1_000L)
            assertEquals(source, editor.getDocumentText())
        }
        val buildStarted = SystemClock.uptimeMillis()
        awaitIndexReady(editor, timeoutMs = 30_000L)
        Log.i("SourceScrollbarTest", "rows=$lineCount backgroundWaitMs=${SystemClock.uptimeMillis() - buildStarted}")
        onMain {
            assertEquals(lineCount, editor.getDocumentLineCount())
            assertEquals(source, editor.getDocumentText())
            dragThumb(editor, 1f)
            val manager = editor.layoutManager as LinearLayoutManager
            val last = manager.findViewByPosition(lineCount - 1)
            assertNotNull(last)
            assertEquals(editor.height - editor.paddingBottom, manager.getDecoratedBottom(last!!))
            heightIndex(editor).detach()
        }
    }

    @Test fun changingDocumentDuringAnActiveDragDiscardsTheFrozenOldHeights() = onMain {
        val editor = editor("very long physical source line ".repeat(2_000) + "\nlast")
        val originalRange = metrics(editor).range
        val downTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
            editor.width - 1f, editor.paddingTop + dp(editor, 24).toFloat(), 0)
        try {
            assertTrue(editor.onTouchEvent(event))
            editor.setDocumentText("new\nlast")
            layout(editor, widthDp = 220)
            assertTrue(metrics(editor).range < originalRange / 2)
            assertEquals("new\nlast", editor.getDocumentText())
            assertEquals(2, editor.getDocumentLineCount())
        } finally {
            event.action = MotionEvent.ACTION_UP
            editor.onTouchEvent(event)
            event.recycle()
            heightIndex(editor).detach()
        }
    }

    @Test fun replacingDocumentAndWidthDiscardsPendingIndexFromOldDocument() {
        val replacement = "新的短文本\n最后一行"
        val oldSource = (0 until 10_000).joinToString("\n") {
            "旧文档 $it " + "这一段需要自动换行。".repeat(15)
        }
        val editor = onMain {
            editor(oldSource).also {
                metrics(it)
                assertTrue(!indexReady(it))
                it.setDocumentText(replacement)
                layout(it, widthDp = 220)
                metrics(it)
            }
        }
        awaitIndexReady(editor)
        onMain {
            val manager = editor.layoutManager as LinearLayoutManager
            val actualHeight = (0..1).sumOf { position ->
                manager.getDecoratedMeasuredHeight(manager.findViewByPosition(position)!!).toLong()
            }
            assertEquals(actualHeight, metrics(editor).range)
            assertEquals(2, editor.getDocumentLineCount())
            assertEquals(replacement, editor.getDocumentText())
        }
    }

    private data class Metrics(val range: Long, val extent: Long, val offset: Long)

    private fun editor(source: String,
        context: Context = InstrumentationRegistry.getInstrumentation().targetContext): SourceEditorView {
        return SourceEditorView(context).also {
            it.setDocumentText(source)
            layout(it)
        }
    }

    private fun layout(editor: SourceEditorView, widthDp: Int = 360, heightDp: Int = 480) {
        layoutPixels(editor, dp(editor, widthDp), dp(editor, heightDp))
    }

    private fun layoutPixels(editor: SourceEditorView, width: Int, height: Int) {
        editor.forceLayout()
        editor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        editor.layout(0, 0, width, height)
    }

    private fun metrics(editor: SourceEditorView): Metrics {
        val method = SourceEditorView::class.java.getDeclaredMethod("computeScrollThumbMetrics")
            .apply { isAccessible = true }
        val result = method.invoke(editor)!!
        return Metrics(number(result, "getRange").toLong(), number(result, "getExtent").toLong(),
            number(result, "getOffset").toLong())
    }

    private fun indexReady(editor: SourceEditorView): Boolean {
        val index = heightIndex(editor)
        return index.javaClass.getDeclaredField("ready").apply { isAccessible = true }
            .getBoolean(index)
    }

    private fun heightIndex(editor: SourceEditorView): SourceScrollHeightIndex =
        SourceEditorView::class.java.getDeclaredField("scrollHeights")
            .apply { isAccessible = true }.get(editor) as SourceScrollHeightIndex

    /** Wait on the instrumentation thread, allowing the worker's main-thread publication to run. */
    private fun awaitIndexReady(editor: SourceEditorView, timeoutMs: Long = 10_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain { metrics(editor); indexReady(editor) }) return
            Thread.sleep(10L)
        }
        throw AssertionError("The source height index did not finish within ${timeoutMs}ms")
    }

    private fun dragThumb(editor: SourceEditorView, ratio: Float) {
        val geometry = DraggableRecyclerView::class.java.getDeclaredMethod("calculateThumbGeometry")
            .apply { isAccessible = true }.invoke(editor)!!
        val trackTop = number(geometry, "getTrackTop").toFloat()
        val thumbHeight = number(geometry, "getThumbHeight").toFloat()
        val startY = trackTop + number(geometry, "getThumbTop").toFloat() + thumbHeight / 2
        val endY = trackTop + number(geometry, "getMaxThumbTop").toFloat() * ratio + thumbHeight / 2
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to startY, MotionEvent.ACTION_MOVE to endY,
            MotionEvent.ACTION_UP to endY).forEachIndexed { index, (action, y) ->
            val event = MotionEvent.obtain(downTime, downTime + index * 16L, action,
                editor.width - 1f, y, 0)
            try {
                assertTrue("Scrollbar must consume the touch", editor.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        // ACTION_UP flushes the pending frame target. A layout applies its row/pixel anchor.
        layoutPixels(editor, editor.width, editor.height)
    }

    private fun number(target: Any, getter: String): Number = target.javaClass
        .getDeclaredMethod(getter).apply { isAccessible = true }.invoke(target) as Number

    private fun dp(editor: SourceEditorView, value: Int): Int =
        (value * editor.resources.displayMetrics.density).toInt()

    private fun assertNear(expected: Long, actual: Long) {
        assertTrue("Expected $expected px, got $actual px", abs(expected - actual) <= 2L)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
        return result.get()
    }
}

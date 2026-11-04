package com.subtitleedit.view

import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Compares worker-only text layout with the real editable row, independently of height caches. */
@RunWith(AndroidJUnit4::class)
class SourceScrollTextMeasurerTest {
    @Test fun emptyAndShortRowsMatchTheirRenderedMinimumHeight() {
        listOf("", "ordinary ASCII text", "00:00:01,000 --> 00:00:03,000", "短文本").forEach {
            assertRenderedHeight(it)
        }
    }

    @Test fun wrappedScriptsEmojiTabsAndCombiningMarksMatchRealRows() {
        listOf(
            "word ".repeat(120),
            "这是用于检查真实换行高度的中文段落。".repeat(100),
            "emoji 😀 text 👨‍👩‍👧 ".repeat(100),
            "text\twith\ttabs ".repeat(120),
            "a\u0301\u0308 e\u0301 combining marks ".repeat(100),
            "نص عربي لاختبار ارتفاع الأسطر الملتفة ".repeat(100)
        ).forEach { assertRenderedHeight(it) }
    }

    @Test fun longRowsAcrossSeveralBoundedLayoutPiecesMatchRealRows() {
        listOf(
            "wrapped source text ".repeat(4_000),
            "字幕超长段落中混合 English 和数字 0123456789。".repeat(2_000),
            "نص عربي طويل مع الأرقام 12345 والرموز 😀 ".repeat(2_000),
            // Place a surrogate pair precisely across the first chunk's nominal boundary.
            "a".repeat(16_383) + "😀" + " more words 👨‍👩‍👧 ".repeat(2_000)
        ).forEach {
            assertTrue(it.length > 32_768)
            assertRenderedHeight(it)
        }
    }

    @Test fun aLongRowChecksCancellationBetweenLayoutPieces() {
        val text = "long wrapped source text ".repeat(4_000)
        val snapshot = snapshot(text)
        var checks = 0
        try {
            snapshot.measurer.measure(text) {
                checks++
                if (checks == 3) throw CancellationException("cancel next layout piece")
            }
            fail("A cancelled row measurement must not finish")
        } catch (cancelled: CancellationException) {
            assertEquals("cancel next layout piece", cancelled.message)
            assertEquals(3, checks)
        }
    }

    private data class Snapshot(val measurer: SourceScrollTextMeasurer, val renderedHeight: Int)

    private fun assertRenderedHeight(text: String) {
        val snapshot = snapshot(text)
        assertFalse("Text layout must run outside the UI thread",
            Looper.myLooper() == Looper.getMainLooper())
        val measured = snapshot.measurer.measure(text) {}
        assertEquals("Rendered height for ${text.length} chars: ${text.take(40)}",
            snapshot.renderedHeight, measured)
    }

    private fun snapshot(text: String): Snapshot = onMain {
        val editor = SourceEditorView(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            editor.setDocumentText(text)
            val density = editor.resources.displayMetrics.density
            val width = (360 * density).toInt()
            val height = (480 * density).toInt()
            editor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            editor.layout(0, 0, width, height)
            val manager = editor.layoutManager as LinearLayoutManager
            val row = requireNotNull(manager.findViewByPosition(0))
            val input = row.findViewById<SourceLineEditText>(R.id.etSourceLine)
            Snapshot(SourceScrollTextMeasurer(input, row.minimumHeight),
                manager.getDecoratedMeasuredHeight(row))
        } finally {
            // These standalone views never attach to a window. Explicitly cancel their pending
            // background index so it cannot borrow this row's height and mask a measurer defect.
            val index = SourceEditorView::class.java.getDeclaredField("scrollHeights")
                .apply { isAccessible = true }.get(editor) as SourceScrollHeightIndex
            index.detach()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
        return result.get()
    }
}

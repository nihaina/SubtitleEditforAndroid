package com.subtitleedit.view

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.view.Menu
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import java.util.concurrent.atomic.AtomicReference
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcePreparedDocumentTest {
    @Test fun workerPreparedDocumentPreservesEndingsAndInstallsItsOffsetIndex() {
        val text = "first\r\nsecond\rthird\nfourth\u2028fifth\u2029sixth\u0085"
        val prepared = SourceEditorView.prepareDocumentText(text)
        onMain {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.Theme_SubtitleEditforAndroid
            )
            val editor = SourceEditorView(context)
            editor.setPreparedDocumentText(prepared)
            assertEquals(text, editor.getDocumentText())
            assertEquals(7, editor.getDocumentLineCount())
            assertEquals("", editor.getDocumentLineText(6))

            val changes = mutableListOf<SourceEditorView.DocumentChange>()
            editor.addOnDocumentChangeListener { changes += it }
            editor.replaceDocumentText("replacement")
            assertEquals(text.length, changes.single().oldEndOffset)
            assertEquals(7, changes.single().oldLineCount)
            assertEquals("replacement", editor.getDocumentText())
        }
    }

    @Test fun largePreparedDocumentKeepsEveryPhysicalRowAndIsSingleUse() {
        val count = 50_000
        val text = buildString { repeat(count) { append("subtitle ").append(it).append('\n') } }
        val prepared = SourceEditorView.prepareDocumentText(text)
        onMain {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.Theme_SubtitleEditforAndroid
            )
            val editor = SourceEditorView(context)
            editor.setPreparedDocumentText(prepared)
            assertEquals(count + 1, editor.getDocumentLineCount())
            assertEquals("subtitle 49999", editor.getDocumentLineText(count - 1))
            assertEquals(text, editor.getDocumentText())
            try {
                editor.setPreparedDocumentText(prepared)
                fail("Prepared source documents must transfer ownership only once")
            } catch (_: IllegalStateException) {
                assertEquals(text, editor.getDocumentText())
            }
        }
    }

    @Test fun exportedPartsKeepTheCapturedTextAfterLaterEdits() {
        val parts = onMain {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.Theme_SubtitleEditforAndroid
            )
            SourceEditorView(context).run {
                setPreparedDocumentText(SourceEditorView.prepareDocumentText("first\r\nsecond\u2028"))
                measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                layout(0, 0, 800, 600)
                findViewById<EditText>(R.id.etSourceLine).setText("edited")
                snapshotDocumentParts().also { replaceDocumentText("later") }
            }
        }
        val writer = StringWriter()
        parts.forEach(writer::write)
        assertEquals("edited\r\nsecond\u2028", writer.toString())
    }

    @Test fun readOnlyPreviewMenuKeepsCopyAndHidesMutatingActions() = onMain {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_SubtitleEditforAndroid
        )
        val editor = SourceEditorView(context)
        val menu = PopupMenu(context, editor).menu
        val fillMenu = SourceEditorView::class.java.getDeclaredMethod("addSelectionMenu", Menu::class.java)
            .apply { isAccessible = true }
        editor.setDocumentEnabled(false)
        fillMenu.invoke(editor, menu)
        assertEquals(listOf(context.getString(R.string.source_selection_select_all),
            context.getString(R.string.source_selection_copy)),
            (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }.map { it.title.toString() })
        editor.setDocumentEnabled(true)
        fillMenu.invoke(editor, menu)
        assertEquals(4, (0 until menu.size()).count { menu.getItem(it).isVisible })
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
        return result.get()
    }
}

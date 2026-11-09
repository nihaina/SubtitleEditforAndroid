package com.subtitleedit

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.subtitleedit.view.SourceEditorView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadOnlySubtitlePreviewTest {
    @Test fun lyricsUseTheSourceEditorWithoutEditingOrToolbarActions() = withPreview { context, file, text ->
        val intent = DraftPreviewActivity.createReadOnlyIntent(context, file, "中文歌词.lrc").apply {
            // The read-only entry point cannot acquire the draft editor's apply capability.
            putExtra(DraftsActivity.EXTRA_FROM_EDITOR, true)
        }
        ActivityScenario.launch<DraftPreviewActivity>(intent).use { scenario ->
            awaitLoaded(scenario, text)
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                val source = descendants(root).filterIsInstance<SourceEditorView>().single()
                val toolbar = descendants(root).filterIsInstance<MaterialToolbar>().single()
                assertFalse(source.isEnabled)
                assertEquals("中文歌词.lrc", toolbar.title.toString())
                assertEquals(0, toolbar.menu.size())
                assertEquals(text, source.getDocumentText())
                descendants(source).filterIsInstance<EditText>().forEach { assertFalse(it.isEnabled) }
            }
            assertEquals(text, file.readText())
        }
        assertFalse("Closing the preview must remove its temporary source file", file.exists())
    }

    @Test fun recreationKeepsThePreviewUntilItIsFinallyClosed() = withPreview { context, file, text ->
        ActivityScenario.launch<DraftPreviewActivity>(
            DraftPreviewActivity.createReadOnlyIntent(context, file, "Lyrics")
        ).use { scenario ->
            awaitLoaded(scenario, text)
            scenario.recreate()
            assertTrue("Recreation must not consume the preview file", file.isFile)
            awaitLoaded(scenario, text)
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                assertFalse(descendants(root).filterIsInstance<SourceEditorView>().single().isEnabled)
                assertEquals(0, descendants(root).filterIsInstance<MaterialToolbar>().single().menu.size())
            }
        }
        assertFalse(file.exists())
    }

    @Test fun theReadOnlyEntryPointRejectsFilesOutsideItsPrivatePreviewDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outside = File.createTempFile("unrelated_", ".lrc", context.cacheDir).apply { writeText("Unrelated") }
        try {
            try {
                DraftPreviewActivity.createReadOnlyIntent(context, outside, "Invalid")
                fail("Only private subtitle preview files may be consumed")
            } catch (_: IllegalArgumentException) { }
            assertEquals("Unrelated", outside.readText())
        } finally { outside.delete() }
    }

    private fun awaitLoaded(scenario: ActivityScenario<DraftPreviewActivity>, text: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var loaded = false
            scenario.onActivity { activity ->
                val source = descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<SourceEditorView>().single()
                loaded = source.visibility == View.VISIBLE && source.getDocumentText() == text
            }
            if (loaded) return
            SystemClock.sleep(20)
        }
        throw AssertionError("Subtitle source preview did not finish loading")
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun withPreview(block: (Context, File, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "subtitle_source_previews").apply { mkdirs() }
        val text = "[ti:中文歌词]\r\n[00:01.125]海风 🌊\r\n[00:02.500]Second line\n"
        val file = File.createTempFile("lyrics_", ".lrc", directory).apply { writeText(text) }
        try { block(context, file, text) } finally { file.delete() }
    }
}

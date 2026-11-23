package com.subtitleedit

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.translation.FoxletPageTranslation
import com.subtitleedit.translation.PageTranslationLanguage.Companion.CHINESE
import com.subtitleedit.translation.PageTranslationLanguage.Companion.ENGLISH
import com.subtitleedit.translation.PageTranslationOptions
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import com.subtitleedit.view.SourceEditorView
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorTextTranslationPreviewTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun translationActionPrecedesTheFileMenuForPlainTextOnly() {
        withDocument(".txt", "This is a plain text file.\n") { scenario ->
            assertToolbarActionPrecedesFileMenu(scenario)
        }
        withDocument(".md", "# An English heading\n\nA short paragraph.\n") { scenario ->
            assertToolbarActionPrecedesFileMenu(scenario)
        }
        withDocument(".srt", "1\n00:00:00,000 --> 00:00:01,000\nThis is a subtitle.\n") { scenario ->
            assertActionHidden(scenario, SubtitleFormat.SRT)
        }
        withDocument(".ass", "[Script Info]\nScriptType: v4.00+\n[Events]\n") { scenario ->
            assertActionHidden(scenario, SubtitleFormat.ASS)
        }
    }

    @Test fun previewUsesUnsavedChangesAndLeavesTheSourceIntact() {
        assumeTrue("Foxlet must be available", FoxletPageTranslation.isSupported)
        val options = PageTranslationOptions(ENGLISH, CHINESE)
        val savedText = "The train crosses the bridge every day.\n"
        val editedText = "It has been raining since yesterday afternoon.\n"
        val translatedText = runBlocking {
            FoxletPageTranslation.openPreview(context, options, Locale.ENGLISH).use { session ->
                if (session.missingModels(session.plan.requiredModels).isNotEmpty()) null
                else session.translate(editedText, ENGLISH).trim() + "\n"
            }
        }
        assumeTrue("Install the English to Chinese Foxlet language pack to verify the native preview", translatedText != null)
        val settings = SettingsManager.getInstance(context)
        val oldOptions = settings.getPageTranslationOptions()
        try {
            settings.setPageTranslationOptions(options)
            withDocument(".txt", savedText) { scenario ->
                await(scenario) { toolbarAction(it)?.isShown == true }
                scenario.onActivity { activity ->
                    activity.findViewById<SourceEditorView>(R.id.etSourceView).replaceDocumentText(editedText)
                    assertTrue(ViewModelProvider(activity)[EditorViewModel::class.java].hasUnsavedChanges)
                    assertTrue(requireNotNull(toolbarAction(activity)).performClick())
                }
                await(scenario) { it.findViewById<SourceEditorView>(R.id.editor_text_translation_content)?.isShown == true }
                scenario.onActivity { activity ->
                    val preview = activity.findViewById<SourceEditorView>(R.id.editor_text_translation_content)
                    val source = activity.findViewById<SourceEditorView>(R.id.etSourceView)
                    assertFalse(preview.isEnabled)
                    assertEquals(translatedText, preview.getDocumentText())
                    assertNotEquals(savedText, preview.getDocumentText())
                    assertEquals(editedText, source.getDocumentText())
                    assertEquals(View.INVISIBLE, source.visibility)
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertNull(activity.findViewById<SourceEditorView>(R.id.editor_text_translation_content))
                    assertEquals(View.VISIBLE, source.visibility)
                    assertEquals(editedText, source.getDocumentText())
                    assertTrue(ViewModelProvider(activity)[EditorViewModel::class.java].hasUnsavedChanges)
                    assertEquals(savedText, File(requireNotNull(activity.intent.getStringExtra(EditorActivity.EXTRA_FILE_PATH))).readText())
                }
            }
        } finally {
            settings.setPageTranslationOptions(oldOptions)
        }
    }

    private fun assertToolbarActionPrecedesFileMenu(scenario: ActivityScenario<EditorActivity>) {
        await(scenario) { toolbarAction(it)?.isShown == true && it.findViewById<View>(R.id.menu_file)?.isShown == true }
        scenario.onActivity { activity ->
            val action = requireNotNull(toolbarAction(activity))
            val fileMenu = activity.findViewById<View>(R.id.menu_file)
            val actionLocation = IntArray(2).also(action::getLocationOnScreen)
            val fileLocation = IntArray(2).also(fileMenu::getLocationOnScreen)
            assertTrue(actionLocation[0] + action.width <= fileLocation[0])
            assertEquals(activity.getString(R.string.editor_text_translation_preview), action.contentDescription.toString())
        }
    }

    private fun assertActionHidden(scenario: ActivityScenario<EditorActivity>, format: SubtitleFormat) {
        await(scenario) {
            val model = ViewModelProvider(it)[EditorViewModel::class.java]
            model.currentFormat == format && model.isSourceViewMode == format.isSourceOnly
        }
        scenario.onActivity { activity ->
            assertFalse(activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.menu_text_translation)?.isVisible == true)
        }
    }

    private fun toolbarAction(activity: EditorActivity): View? = activity.findViewById(R.id.menu_text_translation)

    private fun withDocument(suffix: String, text: String, test: (ActivityScenario<EditorActivity>) -> Unit) {
        val file = File.createTempFile("translation-preview-", suffix, context.cacheDir).apply { writeText(text) }
        try {
            val intent = Intent(context, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_FILE_PATH, file.absolutePath)
                .putExtra(EditorActivity.EXTRA_MEDIA_TYPE, EditorMediaType.SUBTITLE_ONLY.name)
            ActivityScenario.launch<EditorActivity>(intent).use(test)
        } finally {
            file.delete()
        }
    }

    private fun await(scenario: ActivityScenario<EditorActivity>, predicate: (EditorActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Translation preview did not reach the expected state")
    }
}

package com.subtitleedit.editor

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.subtitleedit.PageTranslationSettingsActivity
import com.subtitleedit.R
import com.subtitleedit.translation.FoxletPageTranslation
import com.subtitleedit.translation.TextDocumentPreviewer
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleTextSplitOps
import com.subtitleedit.view.SourceEditorView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Displays the translation in its own view so saving and editing always target the source. */
internal class EditorTextTranslationPreview(
    private val activity: AppCompatActivity,
    private val container: FrameLayout,
    private val source: SourceEditorView,
    private val onStateChanged: () -> Unit,
    private val showMessage: (String) -> Unit
) {
    private var job: Job? = null
    private var preview: SourceEditorView? = null
    private var modelDialog: AlertDialog? = null
    val running: Boolean get() = job != null
    val active: Boolean get() = running || preview != null

    fun start() {
        if (active) return
        if (!FoxletPageTranslation.isSupported) {
            showMessage(activity.getString(R.string.page_translation_unsupported))
            return
        }
        val original = source.getDocumentText()
        if (original.isBlank()) {
            showMessage(activity.getString(R.string.editor_text_translation_no_changes))
            return
        }
        source.clearFocus()
        WindowInsetsControllerCompat(activity.window, source).hide(WindowInsetsCompat.Type.ime())
        val translation = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val request = coroutineContext[Job]
            try {
                val (result, translated) = withContext(Dispatchers.Default) {
                    val context = activity.applicationContext
                    val options = SettingsManager.getInstance(context).getPageTranslationOptions()
                    val result = FoxletPageTranslation.openPreview(context, options, FoxletPageTranslation.systemLocale()).use { session ->
                        TextDocumentPreviewer().preview(original, session.plan, session) { text, offset ->
                            SubtitleTextSplitOps.wordStartAt(context, text, offset)
                        }
                    }
                    ensureActive()
                    result to result.text.takeIf { it != original }?.let(SourceEditorView::prepareDocumentText)
                }
                if (translated != null) {
                    preview = SourceEditorView(activity).apply {
                        id = R.id.editor_text_translation_content
                        setPreparedDocumentText(translated)
                        setDocumentEnabled(false)
                        container.addView(this, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                        ))
                    }
                    source.visibility = View.INVISIBLE
                }
                when {
                    result.missingModels.isNotEmpty() -> {
                        val locale = activity.resources.configuration.locales[0]
                        val names = result.missingModels.joinToString("、") { model ->
                            activity.getString(R.string.page_translation_model_direction,
                                model.source.toLocale().getDisplayName(locale), model.target.toLocale().getDisplayName(locale))
                        }
                        modelDialog = MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.page_translation_missing_models)
                            .setMessage(activity.getString(R.string.editor_text_translation_missing_models, names))
                            .setPositiveButton(R.string.page_translation_open_settings) { _, _ ->
                                close()
                                activity.startActivity(Intent(activity, PageTranslationSettingsActivity::class.java))
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    result.hasFailures -> showMessage(activity.getString(R.string.editor_text_translation_partial_failure))
                    translated == null -> showMessage(activity.getString(R.string.editor_text_translation_no_changes))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage(activity.getString(R.string.editor_text_translation_partial_failure))
            } finally {
                if (job === request) {
                    job = null
                    onStateChanged()
                }
            }
        }
        job = translation
        onStateChanged()
        translation.start()
    }

    fun close(): Boolean {
        val wasActive = active
        val previousJob = job
        job = null
        previousJob?.cancel()
        modelDialog?.dismiss()
        modelDialog = null
        preview?.let {
            container.removeView(it)
            source.visibility = View.VISIBLE
        }
        preview = null
        if (wasActive) onStateChanged()
        return wasActive
    }
}

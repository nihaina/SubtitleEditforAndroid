package com.subtitleedit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.util.DraftManager
import com.subtitleedit.view.SourceEditorView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Source-line preview for a draft. Keeping each physical line in SourceEditorView avoids the
 * large TextView snapshot that previously made opening a large draft block the UI thread.
 */
class DraftPreviewActivity : AppCompatActivity() {
    private lateinit var sourceView: SourceEditorView
    private var draftFile: File? = null
    private var fromEditor = false
    private var loaded = false
    private var applying = false
    private var applyAction: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fromEditor = intent.getBooleanExtra(DraftsActivity.EXTRA_FROM_EDITOR, false)
        val folderName = intent.getStringExtra(DraftsActivity.EXTRA_DRAFT_FOLDER_NAME).orEmpty()
        val fileName = intent.getStringExtra(DraftsActivity.EXTRA_DRAFT_FILE_NAME).orEmpty()
        draftFile = DraftManager.getDraftFile(this, folderName, fileName)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!applying) finish()
            }
        })

        val toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { if (!applying) finish() }
            title = fileName
            setTitleTextAppearance(this@DraftPreviewActivity,
                com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            if (fromEditor) {
                inflateMenu(R.menu.menu_draft_preview)
                applyAction = menu.findItem(R.id.menu_apply_draft_preview).apply { isEnabled = false }
                setOnMenuItemClickListener { item ->
                    if (item.itemId == R.id.menu_apply_draft_preview) {
                        applyDraft()
                        true
                    } else false
                }
            }
        }

        sourceView = SourceEditorView(this).apply {
            setDocumentEnabled(false)
            visibility = View.INVISIBLE
        }
        val loading = ProgressBar(this)
        val content = FrameLayout(this).apply {
            addView(sourceView, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(-1, (48 * resources.displayMetrics.density).toInt())
        )
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val file = requireNotNull(draftFile).takeIf(File::isFile)
                        ?: error(getString(R.string.read_failed))
                    SourceEditorView.prepareDocumentText(file.readText())
                }
                sourceView.setPreparedDocumentText(prepared)
                sourceView.setDocumentEnabled(fromEditor)
                sourceView.visibility = View.VISIBLE
                loading.visibility = View.GONE
                loaded = true
                applyAction?.isEnabled = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(this@DraftPreviewActivity,
                    getString(R.string.draft_preview_load_failed, error.message.orEmpty()),
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun applyDraft() {
        if (!fromEditor || !loaded || applying) return
        val target = draftFile ?: return
        applying = true
        applyAction?.isEnabled = false
        sourceView.setDocumentEnabled(false)
        lifecycleScope.launch {
            val parts = sourceView.snapshotDocumentParts()
            val pendingFile = AtomicReference<File?>()
            var returned = false
            try {
                val previewFile = withContext(Dispatchers.IO) {
                    val directory = File(cacheDir, "draft_previews").apply { mkdirs() }
                    File.createTempFile("draft_", ".${target.extension.ifBlank { "txt" }}", directory)
                        .apply {
                            pendingFile.set(this)
                            try {
                                bufferedWriter(Charsets.UTF_8).use { writer ->
                                    parts.forEach(writer::write)
                                }
                            } catch (error: Throwable) {
                                delete()
                                throw error
                            }
                        }
                }
                setResult(
                    RESULT_OK,
                    Intent().apply {
                        putExtra(EXTRA_PREVIEW_FILE_PATH, previewFile.absolutePath)
                        putExtra(DraftsActivity.EXTRA_DRAFT_FOLDER_NAME, target.parentFile?.name.orEmpty())
                        putExtra(DraftsActivity.EXTRA_DRAFT_FILE_NAME, target.name)
                    }
                )
                returned = true
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                applying = false
                applyAction?.isEnabled = true
                sourceView.setDocumentEnabled(true)
                Toast.makeText(this@DraftPreviewActivity,
                    getString(R.string.draft_preview_apply_failed, error.message.orEmpty()),
                    Toast.LENGTH_LONG).show()
            } finally {
                if (!returned) withContext(NonCancellable + Dispatchers.IO) {
                    pendingFile.get()?.delete()
                }
            }
        }
    }

    companion object {
        const val EXTRA_PREVIEW_FILE_PATH = "extra_preview_file_path"

        fun createIntent(
            context: Context,
            folderName: String,
            fileName: String,
            fromEditor: Boolean = false
        ): Intent = Intent(context, DraftPreviewActivity::class.java).apply {
            putExtra(DraftsActivity.EXTRA_DRAFT_FOLDER_NAME, folderName)
            putExtra(DraftsActivity.EXTRA_DRAFT_FILE_NAME, fileName)
            putExtra(DraftsActivity.EXTRA_FROM_EDITOR, fromEditor)
        }
    }
}

package com.subtitleedit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.feature.ui.ArchivePreviewScreen
import com.subtitleedit.model.ArchivePreviewBrowser
import com.subtitleedit.model.ArchivePreviewItem
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.ArchivePreviewCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ArchivePreviewActivity : AppComposeActivity() {
    private var archiveName by mutableStateOf("")
    private var currentDirectory by mutableStateOf("")
    private var items by mutableStateOf(emptyList<ArchivePreviewItem>())
    private var entryCount by mutableStateOf<Int?>(null)
    private var isLoading by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)
    private var browser: ArchivePreviewBrowser? = null
    private lateinit var previewFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        archiveName = intent.getStringExtra(EXTRA_ARCHIVE_NAME).orEmpty()
        previewFile = File(intent.getStringExtra(EXTRA_PREVIEW_PATH).orEmpty())
        currentDirectory = savedInstanceState?.getString(STATE_DIRECTORY).orEmpty()

        setContent {
            SubtitleEditComposeTheme {
                ArchivePreviewScreen(
                    archiveName = archiveName,
                    currentDirectory = currentDirectory,
                    entryCount = entryCount,
                    items = items,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenDirectory = ::showDirectory
                )
            }
        }

        loadPreview()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentDirectory.isNotEmpty()) {
                    showDirectory(ArchivePreviewBrowser.parentOf(currentDirectory))
                } else {
                    finish()
                }
            }
        })
    }

    private fun loadPreview() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ArchivePreviewCache.read(previewFile) }
            }
            isLoading = false
            result.onSuccess { entries ->
                browser = ArchivePreviewBrowser(entries)
                entryCount = entries.size
                errorMessage = null
                showDirectory(currentDirectory)
            }.onFailure { error ->
                items = emptyList()
                errorMessage = "无法读取预览：${error.message ?: "未知错误"}"
            }
        }
    }

    private fun showDirectory(directory: String) {
        val loadedBrowser = browser ?: return
        currentDirectory = directory.trim('/')
        items = loadedBrowser.itemsAt(currentDirectory)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DIRECTORY, currentDirectory)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) previewFile.delete()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ARCHIVE_NAME = "extra_archive_name"
        private const val EXTRA_PREVIEW_PATH = "extra_preview_path"
        private const val STATE_DIRECTORY = "state_directory"

        fun createIntent(context: Context, archiveName: String, previewFile: File): Intent =
            Intent(context, ArchivePreviewActivity::class.java).apply {
                putExtra(EXTRA_ARCHIVE_NAME, archiveName)
                putExtra(EXTRA_PREVIEW_PATH, previewFile.absolutePath)
            }
    }
}

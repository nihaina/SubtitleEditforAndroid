package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.feature.ui.SubtitleFormatSelectScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.OverwritingToast

class SubtitleFormatSelectActivity : AppCompatActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var selectedName by mutableStateOf("")

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = queryDisplayName(uri) ?: "未知文件"
        if (name.substringAfterLast('.', "").lowercase() !in supportedExtensions) {
            OverwritingToast.makeText(this, "请选择 SRT、LRC、TXT 或 VTT 字幕文件", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        selectedUri = uri
        selectedName = name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedUri = savedInstanceState?.getString(STATE_SELECTED_URI)?.let(Uri::parse)
        selectedName = savedInstanceState?.getString(STATE_SELECTED_NAME).orEmpty()

        setContent {
            SubtitleEditComposeTheme {
                SubtitleFormatSelectScreen(
                    selectedFileName = selectedName.takeIf { selectedUri != null },
                    onSelectFile = { filePicker.launch(arrayOf("text/*", "application/*")) },
                    onConfirm = ::openEditor,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_URI, selectedUri?.toString())
        outState.putString(STATE_SELECTED_NAME, selectedName)
        super.onSaveInstanceState(outState)
    }

    private fun openEditor() {
        val uri = selectedUri ?: return
        startActivity(Intent(this, SubtitleFormatEditorActivity::class.java).apply {
            putExtra(SubtitleFormatEditorActivity.EXTRA_URI, uri.toString())
            putExtra(SubtitleFormatEditorActivity.EXTRA_FILE_NAME, selectedName)
        })
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    companion object {
        private const val STATE_SELECTED_URI = "selected_uri"
        private const val STATE_SELECTED_NAME = "selected_name"
        private val supportedExtensions = setOf("srt", "lrc", "txt", "vtt")
    }
}

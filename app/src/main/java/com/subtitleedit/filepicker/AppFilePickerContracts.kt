package com.subtitleedit.filepicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import com.subtitleedit.util.DirectoryDisplayPath
import java.io.File

/** Internal selection results carry paths, avoiding file-URI grants or system document pickers. */
internal object AppFilePickerContracts {
    private const val RESULT_PATHS = "com.subtitleedit.filepicker.paths"

    fun result(files: List<File>): Intent = Intent().putStringArrayListExtra(
        RESULT_PATHS, ArrayList(files.map { it.absolutePath })
    )

    private fun selectedUris(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        intent.getStringArrayListExtra(RESULT_PATHS)?.let { paths ->
            return paths.distinct().filter { File(it).isAbsolute }.map { Uri.fromFile(File(it)) }
        }
        // Accept the same in-app result format from hosts restoring an earlier picker instance.
        val clip = intent.clipData
        val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            else listOfNotNull(intent.data)
        return uris.distinct().filter { it.scheme == "file" }
    }

    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            FilePickerRequest(FilePickerMode.OPEN_FILE, input.toList()).toIntent(context)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            selectedUris(resultCode, intent).firstOrNull()
    }

    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            FilePickerRequest(FilePickerMode.OPEN_FILES, input.toList()).toIntent(context)

        override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
            selectedUris(resultCode, intent)
    }

    class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?): Intent {
            val initial = input?.let {
                runCatching { DirectoryDisplayPath.fromUri(context, it) }.getOrNull()
                    ?.takeIf { path -> File(path).isAbsolute }
            }
            return FilePickerRequest(FilePickerMode.DIRECTORY, initialPath = initial).toIntent(context)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            selectedUris(resultCode, intent).firstOrNull()
    }

    class CreateDocument(private val mimeType: String) : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent =
            FilePickerRequest(FilePickerMode.SAVE_FILE, listOf(mimeType), suggestedName = input)
                .toIntent(context)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            selectedUris(resultCode, intent).firstOrNull()
    }
}

internal enum class FilePickerMode { OPEN_FILE, OPEN_FILES, DIRECTORY, SAVE_FILE }

internal data class FilePickerRequest(
    val mode: FilePickerMode,
    val mimeTypes: List<String> = emptyList(),
    val initialPath: String? = null,
    val suggestedName: String? = null
) {
    fun toIntent(context: Context): Intent = Intent(context, FilePickerActivity::class.java)
        .putExtra(MODE, mode.name)
        .putStringArrayListExtra(MIME_TYPES, ArrayList(mimeTypes))
        .putExtra(INITIAL_PATH, initialPath)
        .putExtra(SUGGESTED_NAME, suggestedName)

    companion object {
        private const val MODE = "picker_mode"
        private const val MIME_TYPES = "picker_mime_types"
        private const val INITIAL_PATH = "picker_initial_path"
        private const val SUGGESTED_NAME = "picker_suggested_name"

        fun fromIntent(intent: Intent): FilePickerRequest = FilePickerRequest(
            mode = runCatching { FilePickerMode.valueOf(intent.getStringExtra(MODE).orEmpty()) }
                .getOrDefault(FilePickerMode.OPEN_FILE),
            mimeTypes = intent.getStringArrayListExtra(MIME_TYPES).orEmpty(),
            initialPath = intent.getStringExtra(INITIAL_PATH),
            suggestedName = intent.getStringExtra(SUGGESTED_NAME)
        )
    }
}

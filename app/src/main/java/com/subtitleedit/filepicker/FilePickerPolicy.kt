package com.subtitleedit.filepicker

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import java.io.File
import java.util.Locale

internal object FilePickerPolicy {
    const val PREFERENCES_NAME = "internal_file_picker"
    const val LAST_DIRECTORY = "last_directory"

    fun defaultRoot(): File = Environment.getExternalStorageDirectory()

    fun initialDirectory(context: Context, initialPath: String?): File {
        val remembered = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(LAST_DIRECTORY, null)
        return sequenceOf(initialPath, remembered, defaultRoot().path,
            SettingsManager.getInstance(context).getSoftwareDirectory().path)
            .filterNotNull()
            .map(::File)
            .map { if (it.isFile) it.parentFile ?: it else it }
            .firstOrNull { it.isAbsolute && it.isDirectory && it.canRead() }
            ?: defaultRoot()
    }

    fun validateFileName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\u0000' }

    fun matchesMimeType(file: File, mimeTypes: List<String>): Boolean {
        if (file.isDirectory) return true
        if (mimeTypes.isEmpty() || "*/*" in mimeTypes) return true
        val extension = file.extension.lowercase(Locale.ROOT)
        // Subtitle pickers use a broad text filter. SRT is commonly registered as
        // application/x-subrip, but remains selectable alongside LRC/TXT/VTT here.
        if (extension in FileUtils.SUBTITLE_EXTENSIONS && matchesMime("text/plain", mimeTypes)) return true
        if (extension in FileUtils.AUDIO_EXTENSIONS && matchesMime("audio/x-$extension", mimeTypes)) return true
        if (extension in FileTypePolicy.videoExtensions && matchesMime("video/x-$extension", mimeTypes)) return true
        val actual = extensionMimeTypes[extension]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                in FileUtils.AUDIO_EXTENSIONS -> "audio/x-$extension"
                in FileTypePolicy.videoExtensions -> "video/x-$extension"
                else -> null
            }
            ?: "application/octet-stream"
        return matchesMime(actual, mimeTypes)
    }

    internal fun matchesMime(actual: String, requested: List<String>): Boolean = requested.any { filter ->
        val normalized = filter.substringBefore(';').trim().lowercase(Locale.ROOT)
        normalized == "*/*" || normalized == actual.lowercase(Locale.ROOT) ||
            (normalized.endsWith("/*") && actual.startsWith(normalized.substringBefore('/') + "/", true))
    }

    private val extensionMimeTypes = mapOf(
        "srt" to "application/x-subrip", "lrc" to "text/plain", "vtt" to "text/vtt",
        "ass" to "text/x-ssa", "ssa" to "text/x-ssa", "sub" to "text/plain",
        "txt" to "text/plain", "json" to "application/json", "jsonl" to "application/json",
        "onnx" to "application/octet-stream", "data" to "application/octet-stream",
        "bin" to "application/octet-stream", "gguf" to "application/octet-stream",
        "model" to "application/octet-stream", "mnn" to "application/octet-stream",
        "ttf" to "font/ttf", "otf" to "font/otf", "ttc" to "font/collection"
    )
}

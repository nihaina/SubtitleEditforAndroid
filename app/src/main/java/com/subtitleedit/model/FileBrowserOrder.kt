package com.subtitleedit.model

import java.io.File
import java.util.Locale

enum class FileSortField { NAME, TYPE, SIZE, DATE }
enum class FileSortDirection { ASCENDING, DESCENDING }

object FileBrowserOrder {
    /** Sorts preloaded attributes without any filesystem access. */
    internal fun sortMetadata(
        files: List<BrowserFileMetadata>,
        field: FileSortField,
        direction: FileSortDirection
    ): List<BrowserFileMetadata> = files.sortedWith { left, right ->
        if (left.isDirectory != right.isDirectory) {
            if (left.isDirectory) -1 else 1
        } else {
            val primary = when (field) {
                FileSortField.NAME -> left.file.name.compareTo(right.file.name, ignoreCase = true)
                FileSortField.TYPE -> metadataExtension(left).compareTo(metadataExtension(right), ignoreCase = true)
                FileSortField.SIZE -> left.size.compareTo(right.size)
                FileSortField.DATE -> left.modifiedTimeMillis.compareTo(right.modifiedTimeMillis)
            }
            val directed = if (direction == FileSortDirection.ASCENDING) primary else -primary
            if (directed != 0) directed else left.file.name.compareTo(right.file.name, ignoreCase = true)
        }
    }

    fun sort(
        files: List<File>,
        field: FileSortField,
        direction: FileSortDirection
    ): List<File> = files.sortedWith { left, right ->
        if (left.isDirectory != right.isDirectory) {
            if (left.isDirectory) -1 else 1
        } else {
            val primary = compareValues(left, right, field)
            val directed = if (direction == FileSortDirection.ASCENDING) primary else -primary
            if (directed != 0) directed else left.name.compareTo(right.name, ignoreCase = true)
        }
    }

    fun filter(files: List<File>, query: String): List<File> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return files
        return files.filter { it.name.contains(normalized, ignoreCase = true) }
    }

    fun composeFileName(nameInput: String, extensionInput: String): String {
        val name = nameInput.trim()
        val extension = extensionInput.trim()
        return if (extension.isEmpty()) name else "$name.$extension"
    }

    fun validateName(input: String): String? {
        val name = input.trim()
        return when {
            name.isEmpty() -> "名称不能为空"
            name == "." || name == ".." -> "名称无效"
            name.any { it in INVALID_NAME_CHARS } -> "名称不能包含 \\ / : * ? \" < > |"
            else -> null
        }
    }

    fun validateExtension(input: String): String? {
        val extension = input.trim()
        if (extension.isEmpty()) return null
        return when {
            extension.any { it in INVALID_NAME_CHARS } -> "扩展名不能包含 \\ / : * ? \" < > |"
            else -> null
        }
    }

    private fun compareValues(left: File, right: File, field: FileSortField): Int = when (field) {
        FileSortField.NAME -> left.name.compareTo(right.name, ignoreCase = true)
        FileSortField.TYPE -> extension(left).compareTo(extension(right), ignoreCase = true)
        FileSortField.SIZE -> left.length().compareTo(right.length())
        FileSortField.DATE -> left.lastModified().compareTo(right.lastModified())
    }

    private fun extension(file: File): String =
        if (file.isDirectory) "" else file.extension.lowercase(Locale.ROOT)

    private fun metadataExtension(metadata: BrowserFileMetadata): String =
        if (metadata.isDirectory) "" else metadata.file.extension.lowercase(Locale.ROOT)

    private val INVALID_NAME_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
}

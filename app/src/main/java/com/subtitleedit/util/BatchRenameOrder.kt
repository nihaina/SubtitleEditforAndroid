package com.subtitleedit.util

import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import java.io.File

/** Ordering changes numbering within this rename operation, without changing browser settings. */
internal object BatchRenameOrder {
    fun sort(files: List<File>, field: FileSortField, reversed: Boolean): List<File> {
        val sorted = FileBrowserOrder.sort(files, field, FileSortDirection.ASCENDING)
        return if (reversed) sorted.asReversed() else sorted
    }

    fun move(files: List<File>, fromIndex: Int, toIndex: Int): List<File> {
        if (fromIndex !in files.indices || toIndex !in files.indices || fromIndex == toIndex) return files
        return files.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    /** Reconcile a saved ordering with the current selection without adding stale file paths. */
    fun restore(files: List<File>, savedPaths: List<String>): List<File> {
        val remaining = files.associateByTo(linkedMapOf()) { it.absolutePath }
        return buildList {
            savedPaths.forEach { path -> remaining.remove(path)?.let(::add) }
            addAll(remaining.values)
        }
    }
}

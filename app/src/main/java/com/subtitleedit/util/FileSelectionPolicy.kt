package com.subtitleedit.util

import java.io.File

internal object FileSelectionPolicy {
    fun existingFiles(paths: Iterable<String>): List<File> =
        paths.map(::File).filter { it.exists() }
}

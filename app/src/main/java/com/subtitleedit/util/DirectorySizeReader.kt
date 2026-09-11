package com.subtitleedit.util

import java.io.File

internal object DirectorySizeReader {
    fun size(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

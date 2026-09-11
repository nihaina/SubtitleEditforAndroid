package com.subtitleedit.util

import java.io.File

internal object FilePathPolicy {
    fun canonicalOrAbsolute(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}

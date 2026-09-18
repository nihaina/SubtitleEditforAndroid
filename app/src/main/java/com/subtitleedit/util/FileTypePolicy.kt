package com.subtitleedit.util

import java.io.File

internal object FileTypePolicy {
    val textExtensions = setOf("txt", "md", "log", "json", "xml", "csv", "ini", "conf")

    val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v",
        "ts", "3gp", "mpg", "mpeg", "mts", "m2ts"
    )

    fun isVideo(file: File): Boolean = file.extension.lowercase() in videoExtensions

    fun isText(file: File): Boolean = file.extension.lowercase() in textExtensions
}

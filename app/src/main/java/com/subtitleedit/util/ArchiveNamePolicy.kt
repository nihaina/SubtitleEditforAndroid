package com.subtitleedit.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 归档文件名生成与校验规则。 */
internal object ArchiveNamePolicy {
    fun defaultName(sources: List<File>): String =
        if (sources.size == 1) {
            sources.first().let { source ->
                if (source.isDirectory) source.name else source.nameWithoutExtension.ifBlank { source.name }
            }
        } else {
            "archive-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}"
        }

    fun stripExtension(name: String): String =
        listOf(".tar.bz2", ".tar.gz", ".tar.xz", ".zip", ".7z", ".tar")
            .firstOrNull { name.endsWith(it, ignoreCase = true) }
            ?.let { name.dropLast(it.length) }
            ?: name

    fun isValidName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')
}

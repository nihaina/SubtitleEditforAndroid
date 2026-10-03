package com.subtitleedit.util

import java.util.Locale

/** Formats byte counts with two decimals for MB/GB and one for KB. */
internal object ByteSizeFormat {
    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
        bytes >= GB -> "%.2f GB".format(locale, bytes / GB.toDouble())
        bytes >= MB -> "%.2f MB".format(locale, bytes / MB.toDouble())
        else -> "%.1f KB".format(locale, bytes / 1024.0)
    }

    private const val MB = 1024L * 1024L
    private const val GB = MB * 1024L
}

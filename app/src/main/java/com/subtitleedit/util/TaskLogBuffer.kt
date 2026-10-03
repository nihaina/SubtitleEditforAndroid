package com.subtitleedit.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe, size-capped buffer for the runtime log shown on long-task pages.
 * Each appended line is prefixed with an HH:mm:ss timestamp.
 */
internal class TaskLogBuffer(
    private val maxChars: Int = 16_000,
    private val clock: () -> Date = ::Date
) {
    private val builder = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Appends a timestamped line and returns the current visible text. */
    @Synchronized
    fun append(message: String): String {
        builder.append('[').append(timeFormat.format(clock())).append("] ").appendLine(message)
        trim()
        return builder.toString()
    }

    /** Appends a line without timestamp and returns the current visible text. */
    @Synchronized
    fun appendRaw(line: String): String {
        builder.appendLine(line)
        trim()
        return builder.toString()
    }

    @Synchronized
    fun clear() = builder.setLength(0)

    @Synchronized
    fun text(): String = builder.toString()

    private fun trim() {
        if (builder.length > maxChars) builder.delete(0, builder.length - maxChars)
    }
}

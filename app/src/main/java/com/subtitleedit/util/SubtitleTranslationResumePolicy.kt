package com.subtitleedit.util

internal object SubtitleTranslationResumePolicy {
    /** Returns the zero-based line to translate again, one cue before the last valid output. */
    fun resumeIndex(validOutputLines: Int): Int {
        require(validOutputLines >= 0) { "有效字幕行数不能小于 0" }
        return (validOutputLines - 2).coerceAtLeast(0)
    }
}

package com.subtitleedit.util

import java.io.IOException

/** TXT parsing skips blank lines; restore predicted lines into the original physical layout. */
internal fun restoreTxtLineLayout(source: String, textByLine: Map<Int, String>): String {
    var lineIndex = 0
    return Regex("[^\r\n]+").replace(source) { match ->
        val line = match.value
        if (line.isBlank()) {
            line
        } else {
            val replacement = textByLine[++lineIndex]
            if (replacement == null) line else {
                line.takeWhile { it.isWhitespace() } + replacement.trim() +
                    line.takeLastWhile { it.isWhitespace() }
            }
        }
    }
}

/** Keeps completed TXT requests when the queue is stopped or a later request must be retried. */
internal class TxtTranslationSession(
    text: String,
    wordStart: (String, Int) -> Int
) {
    private val segments = TxtTranslationSegmenter.split(text, wordStart = wordStart)
    private val translations = mutableListOf<String>()

    constructor(
        text: String,
        wordStart: (String, Int) -> Int,
        completedTranslations: List<String>
    ) : this(text, wordStart) {
        completedTranslations.take(segments.size).forEach(::appendTranslation)
    }

    val totalCount: Int get() = segments.size
    val completedCount: Int get() = translations.size
    fun completedTranslationsSnapshot(): List<String> = translations.toList()
    val nextText: String get() = segments[completedCount].text
    var translatedText: String = ""
        private set

    fun appendTranslation(translation: String) {
        check(completedCount < totalCount) { "所有文本段落均已翻译" }
        if (translation.isBlank()) throw IOException("AI 未返回文本翻译结果")
        // Advance only after a complete response, so failed requests resume at the same chunk.
        val candidate = translations + translation
        val merged = TxtTranslationSegmenter.merge(candidate, segments.take(candidate.size))
        translations += translation
        translatedText = merged
    }
}

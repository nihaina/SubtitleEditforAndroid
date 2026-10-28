package com.subtitleedit.util

import java.io.IOException

/** Runs punctuation prediction over the same plain-text chunks used by TXT translation. */
internal class TxtPunctuationSession(
    text: String,
    wordStart: (String, Int) -> Int
) {
    private val segments = TxtTranslationSegmenter.split(text, wordStart = wordStart)
    private val predictions = mutableListOf<String>()

    constructor(
        text: String,
        wordStart: (String, Int) -> Int,
        completedPredictions: List<String>
    ) : this(text, wordStart) {
        completedPredictions.take(segments.size).forEach(::appendPrediction)
    }

    val totalCount: Int get() = segments.size
    val completedCount: Int get() = predictions.size
    fun completedPredictionsSnapshot(): List<String> = predictions.toList()
    val nextText: String get() = segments[completedCount].text
    var predictedText: String = ""
        private set

    fun appendPrediction(prediction: String) {
        check(completedCount < totalCount) { "所有文本段落均已完成标点预测" }
        if (prediction.isBlank()) throw IOException("AI 未返回标点预测结果")
        val sourceSegment = segments[completedCount].text
        if (withoutPunctuation(sourceSegment.trim()) != withoutPunctuation(prediction.trim())) {
            throw IOException("标点预测修改了原文内容或空白格式")
        }
        val merged = TxtTranslationSegmenter.mergePunctuation(
            predictions + prediction,
            segments.take(predictions.size + 1)
        )
        predictions += prediction
        predictedText = merged
    }

    private fun withoutPunctuation(text: String): String = buildString {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val type = Character.getType(codePoint)
            if (type !in PUNCTUATION_TYPES) appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
        }
    }

    private companion object {
        val PUNCTUATION_TYPES = setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt()
        )
    }
}

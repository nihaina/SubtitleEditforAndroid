package com.subtitleedit.translation

import com.subtitleedit.util.TxtTranslationSegmenter
import java.text.BreakIterator
import java.util.Locale

/** Keeps each Foxlet request small while leaving subword tokenization to the model. */
internal object FoxletTextChunker {
    // Foxlet wraps model input at 127 SentencePiece tokens plus EOS. This is a separate,
    // conservative request budget because the SDK does not expose the model's token count.
    private const val MAX_CODE_POINTS = 96
    private const val BOUNDARY_LOOKAHEAD = 16
    private const val LINE_BREAK = "(?>\\r\\n|[\\r\\n\\u0085\\u2028])"
    private val paragraphBreak = Regex("$LINE_BREAK[ \\t]*+$LINE_BREAK|\\u2029")

    fun split(text: String, locale: Locale): List<TxtTranslationSegmenter.Segment> {
        val result = ArrayList<TxtTranslationSegmenter.Segment>()
        val sentences by lazy(LazyThreadSafetyMode.NONE) { BreakIterator.getSentenceInstance(locale) }
        val words by lazy(LazyThreadSafetyMode.NONE) { BreakIterator.getWordInstance(locale) }
        val paragraphs = paragraphBreak.findAll(text).iterator()
        var paragraphEnd = if (paragraphs.hasNext()) paragraphs.next().range.last + 1 else text.length
        var start = 0
        while (start < text.length) {
            // Keep separate paragraphs independent even if they fit in the same request.
            if (start == paragraphEnd) {
                paragraphEnd = if (paragraphs.hasNext()) paragraphs.next().range.last + 1 else text.length
            }
            val earliest = moveForward(text, start, MAX_CODE_POINTS / 2)
            var hardEnd = minOf(paragraphEnd, moveForward(text, earliest, MAX_CODE_POINTS / 2))
            if (hardEnd == paragraphEnd) {
                result += TxtTranslationSegmenter.Segment(text.substring(start, hardEnd))
                start = hardEnd
                continue
            }
            if (text[hardEnd - 1] == '\r' && text[hardEnd] == '\n') hardEnd--

            // Favor a natural boundary in the latter half so a short heading or isolated
            // space near the start does not produce a tiny request on its own.
            val end = lineBreakAtOrBefore(text, hardEnd, earliest) ?: run {
                // A bounded window keeps sentence and word boundary scans linear even for
                // very long paragraphs without punctuation. Include text past the cap so
                // the iterator cannot mistake a truncated word for a word boundary.
                val window = text.substring(start, minOf(paragraphEnd, moveForward(text, hardEnd, BOUNDARY_LOOKAHEAD)))
                val boundary = sentences.boundaryAtOrBefore(window, hardEnd - start, earliest - start)
                    ?: words.boundaryAtOrBefore(window, hardEnd - start, earliest - start)
                if (boundary == null) hardEnd else start + boundary
            }
            result += TxtTranslationSegmenter.Segment(text.substring(start, end))
            start = end
        }
        return result
    }

    private fun moveForward(text: String, start: Int, codePoints: Int): Int {
        var offset = start
        repeat(codePoints) {
            if (offset == text.length) return offset
            offset += Character.charCount(text.codePointAt(offset))
        }
        return offset
    }

    private fun lineBreakAtOrBefore(text: String, end: Int, earliest: Int): Int? {
        for (offset in end downTo earliest) {
            when (text[offset - 1]) {
                '\n', '\u0085', '\u2028', '\u2029' -> return offset
                '\r' -> if (text[offset] != '\n') return offset
            }
        }
        return null
    }

    private fun BreakIterator.boundaryAtOrBefore(text: String, end: Int, earliest: Int): Int? {
        setText(text)
        return (if (isBoundary(end)) end else preceding(end)).takeIf { it >= earliest }
    }
}

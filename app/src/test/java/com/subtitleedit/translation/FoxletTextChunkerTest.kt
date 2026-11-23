package com.subtitleedit.translation

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxletTextChunkerTest {
    @Test fun prefersALineBreakOverALaterSentenceBoundary() {
        val line = "This paragraph ends before the line break, close to the natural split.\r\n"
        val text = line + "This sentence ends. Another sentence extends well beyond the split."
        val chunks = split(text)

        assertEquals(line, chunks.first())
        assertPreserved(text, chunks)
    }

    @Test fun prefersSentenceThenWordBoundaries() {
        val sentence = "This is the first complete sentence and has enough text to be a chunk. "
        val sentenceText = sentence + "Another sentence that continues for a long time before it comes to a close."
        val sentenceChunks = split(sentenceText)
        assertEquals(sentence, sentenceChunks.first())
        assertPreserved(sentenceText, sentenceChunks)

        val words = "word ".repeat(42)
        val wordChunks = split(words)
        assertEquals("word ".repeat(19), wordChunks.first())
        assertPreserved(words, wordChunks)
    }

    @Test fun limitsUnicodeAndNeverSplitsSurrogatePairsOrCrLf() {
        val text = "A".repeat(95) + "\r\n" + "🎉𠮷".repeat(215) + " ".repeat(210) + "？".repeat(211)
        val chunks = FoxletTextChunker.split(text, Locale.JAPANESE).map { it.text }

        assertEquals("A".repeat(95), chunks.first())
        assertTrue(chunks[1].startsWith("\r\n"))
        assertPreserved(text, chunks)
    }

    @Test fun keepsShortTextTogether() {
        val text = "第一句。 第二句。\r\n"
        assertEquals(listOf(text), split(text))
        assertTrue(split("").isEmpty())
    }

    @Test fun keepsEvenShortParagraphsIndependent() {
        val text = "First paragraph\r\n \r\nSecond paragraph\n\nThird paragraph"
        val chunks = split(text)
        assertEquals(listOf("First paragraph\r\n \r\n", "Second paragraph\n\n", "Third paragraph"), chunks)
        assertPreserved(text, chunks)
    }

    private fun split(text: String): List<String> = FoxletTextChunker.split(text, Locale.ENGLISH).map { it.text }

    private fun assertPreserved(text: String, chunks: List<String>) {
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.codePointCount(0, it.length) in 1..96 })
        assertFalse(chunks.any { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
        assertFalse(chunks.zipWithNext().any { (first, second) -> first.endsWith('\r') && second.startsWith('\n') })
    }
}

package com.subtitleedit.view

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SourceDocumentLineDiffTest {
    @Test
    fun splitPreservesLineEndingsAndTrailingEmptyRow() {
        assertEquals(
            listOf(
                SourceDocumentLine("first", "\r\n"),
                SourceDocumentLine("second", "\n"),
                SourceDocumentLine("", "")
            ),
            SourceDocumentLineDiffs.split("first\r\nsecond\n")
        )
    }

    @Test
    fun compareFindsOneChangedRowWithoutIncludingUnchangedSuffix() {
        val old = listOf(
            SourceDocumentLine("header", "\n"),
            SourceDocumentLine("one", "\n"),
            SourceDocumentLine("two", "\n"),
            SourceDocumentLine("tail", "")
        )
        val updated = listOf(
            SourceDocumentLine("header", "\n"),
            SourceDocumentLine("one", "\n"),
            SourceDocumentLine("TWO", "\n"),
            SourceDocumentLine("tail", "")
        )

        val source = updated.joinToString("") { it.text + it.lineEnding }
        val diff = SourceDocumentLineDiffs.diff(
            old.size,
            source,
            oldLineLength = { index -> old[index].serializedLength },
            oldLineMatchesAt = { index, content, offset ->
                SourceDocumentLineDiffs.matchesAt(content, offset, old[index].text, old[index].lineEnding)
            }
        )

        assertEquals(2, diff.unchangedPrefix)
        assertEquals(3, diff.oldChangedEnd)
        assertEquals(1, diff.newChangedCount)
    }

    @Test
    fun compareFindsInsertedRowsAndKeepsTheUnchangedTail() {
        val old = listOf(
            SourceDocumentLine("header", "\n"),
            SourceDocumentLine("one", "\n"),
            SourceDocumentLine("tail", "")
        )
        val updated = listOf(
            SourceDocumentLine("header", "\n"),
            SourceDocumentLine("one", "\n"),
            SourceDocumentLine("new", "\n"),
            SourceDocumentLine("tail", "")
        )

        val source = updated.joinToString("") { it.text + it.lineEnding }
        val diff = SourceDocumentLineDiffs.diff(
            old.size,
            source,
            oldLineLength = { index -> old[index].serializedLength },
            oldLineMatchesAt = { index, content, offset ->
                SourceDocumentLineDiffs.matchesAt(content, offset, old[index].text, old[index].lineEnding)
            }
        )

        assertEquals(2, diff.unchangedPrefix)
        assertEquals(2, diff.oldChangedEnd)
        assertEquals(1, diff.newChangedCount)
    }

    @Test
    fun comparePreservesImplicitTrailingEmptyRowWhenDeletingFinalLine() {
        val old = listOf(
            SourceDocumentLine("first", "\n"),
            SourceDocumentLine("last", "")
        )
        val diff = SourceDocumentLineDiffs.diff(
            old.size,
            "first\n",
            oldLineLength = { index -> old[index].serializedLength },
            oldLineMatchesAt = { index, content, offset ->
                SourceDocumentLineDiffs.matchesAt(content, offset, old[index].text, old[index].lineEnding)
            }
        )

        assertEquals(1, diff.unchangedPrefix)
        assertEquals(2, diff.oldChangedEnd)
        assertEquals(listOf(SourceDocumentLine("", "")), diff.replacement)
    }

    @Test
    fun compareDoesNotDuplicateAnUnchangedTrailingEmptyRow() {
        val old = SourceDocumentLineDiffs.split("first\n")
        val diff = SourceDocumentLineDiffs.diff(
            old.size,
            "first\n",
            oldLineLength = { index -> old[index].serializedLength },
            oldLineMatchesAt = { index, content, offset ->
                SourceDocumentLineDiffs.matchesAt(content, offset, old[index].text, old[index].lineEnding)
            }
        )

        assertEquals(2, diff.unchangedPrefix)
        assertEquals(0, diff.oldChangedCount)
        assertEquals(0, diff.newChangedCount)
    }

    @Test
    fun rowPatchReconstructsDocumentsWithMixedLineEndings() {
        val random = Random(0x51A7)
        val alphabet = listOf('a', 'b', ' ', '\r', '\n', '\u2028')
        repeat(500) {
            val oldContent = buildString {
                repeat(random.nextInt(0, 50)) { append(alphabet.random(random)) }
            }
            val newContent = buildString {
                repeat(random.nextInt(0, 50)) { append(alphabet.random(random)) }
            }
            val oldLines = SourceDocumentLineDiffs.split(oldContent)
            val diff = SourceDocumentLineDiffs.diff(
                oldLines.size,
                newContent,
                oldLineLength = { index -> oldLines[index].serializedLength },
                oldLineMatchesAt = { index, content, offset ->
                    val line = oldLines[index]
                    SourceDocumentLineDiffs.matchesAt(content, offset, line.text, line.lineEnding)
                }
            )
            val patched = oldLines.take(diff.unchangedPrefix) +
                diff.replacement + oldLines.drop(diff.oldChangedEnd)
            assertEquals(newContent, patched.joinToString("") { it.text + it.lineEnding })
        }
    }
}

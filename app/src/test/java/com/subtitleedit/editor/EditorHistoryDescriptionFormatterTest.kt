package com.subtitleedit.editor

import com.subtitleedit.EditorEditHistory
import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHistoryDescriptionFormatterTest {
    @Test
    fun sourceSplitDescribesOnlyTheLineWhereTheBreakWasInserted() {
        val before = "first\r\nsecond\r\nthird"
        val edit = EditorSourceTextEdit.fromRanges(
            before = before,
            after = "first\r\nsec\r\nond\r\nthird",
            oldStart = 7,
            oldEnd = 15,
            newStart = 7,
            newEnd = 17,
            lineNumber = 1
        )

        val description = EditorHistoryDescriptionFormatter.describeSourceTextChange(edit, before)

        assertEquals("第2行换行", description)
    }

    @Test
    fun largeListChangeKeepsHistoryDescriptionBounded() {
        val deleted = (0 until 2_000).map { index ->
            SubtitleEntry(index = index + 1, text = "x".repeat(1_000))
        }
        val difference = EditorEditHistory.ListDifference(
            deleted = deleted,
            added = emptyList(),
            modified = emptyList(),
            selected = emptyList(),
            deselected = emptyList(),
            orderChanged = false
        )

        val description = EditorHistoryDescriptionFormatter.describeListStateChange(difference)

        assertTrue(description.contains("其余 1900 项变更"))
        assertTrue(description.length < 30_000)
    }
}

package com.subtitleedit.editor

import com.subtitleedit.EditorEditHistory
import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHistoryDescriptionFormatterTest {
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

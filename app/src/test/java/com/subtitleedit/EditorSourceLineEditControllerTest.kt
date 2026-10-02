package com.subtitleedit

import com.subtitleedit.editor.EditorSourceLineEditController
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EditorSourceLineEditControllerTest {
    @Test
    fun resolvesTextEditToSrtCueAndPreservesStableId() {
        val lines = mutableListOf(
            "1", "00:00:00,000 --> 00:00:01,000", "旧文本", "",
            "2", "00:00:02,000 --> 00:00:03,000", "第二条"
        )
        val first = SubtitleEntry(1, 0, 1000, "旧文本", stableId = 77L)
        val second = SubtitleEntry(2, 2000, 3000, "第二条", stableId = 88L)
        val controller = EditorSourceLineEditController(
            lineCount = { lines.size },
            lineText = { lines[it] },
            currentFormat = { SubtitleParser.SubtitleFormat.SRT },
            entries = { listOf(first, second) }
        )

        lines[2] = "新文本"
        val update = controller.resolve(2, 1, 1)

        assertNotNull(update)
        assertEquals(0, update!!.entryIndex)
        assertEquals("新文本", update.entry.text)
        assertEquals(77L, update.entry.stableId)
    }

    @Test
    fun invalidatingLineIndexHandlesLrcTagCountChangingOnSameLine() {
        val lines = mutableListOf(
            "[00:01.00][00:02.00]A",
            "",
            "[00:03.00]B"
        )
        val first = SubtitleEntry(1, 1000, 2000, "A", stableId = 11L)
        val second = SubtitleEntry(2, 2000, 3000, "A", stableId = 12L)
        val third = SubtitleEntry(3, 3000, 4000, "B", stableId = 13L)
        val controller = EditorSourceLineEditController(
            lineCount = { lines.size },
            lineText = { lines[it] },
            currentFormat = { SubtitleParser.SubtitleFormat.LRC },
            entries = { listOf(first, second, third) }
        )

        // Seed the cached map before the same physical line changes its cue count.
        assertEquals(2, controller.resolve(2, 1, 1)!!.entryIndex)
        lines[0] = "[00:01.00]A"
        controller.invalidateLineIndex()

        val update = controller.resolve(2, 1, 1)
        assertNotNull(update)
        assertEquals(1, update!!.entryIndex)
        assertEquals(12L, update.entry.stableId)
    }
}

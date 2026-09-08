package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorCommandTest {
    @Test
    fun textAndTimeCommandsUpdateTheSharedDocument() {
        val viewModel = EditorViewModel()
        viewModel.subtitleEntries = mutableListOf(
            SubtitleEntry(startTime = 100L, endTime = 500L, text = "旧文本")
        )

        val textResult = viewModel.execute(EditorCommand.UpdateText(0, "新文本"))
        val timeResult = viewModel.execute(EditorCommand.UpdateTime(0, endTime = 900L))

        assertEquals(setOf(0), textResult.changedPositions)
        assertEquals(setOf(0), timeResult.changedPositions)
        assertEquals("新文本", viewModel.subtitleDocument.entries.single().text)
        assertEquals(900L, viewModel.subtitleDocument.entries.single().endTime)
        assertTrue(viewModel.subtitleDocument.entries.single().endTimeModified)
    }

    @Test
    fun deleteAndInsertCommandsPreserveStableEntries() {
        val viewModel = EditorViewModel()
        val first = SubtitleEntry(text = "第一")
        val second = SubtitleEntry(text = "第二")
        viewModel.subtitleEntries = mutableListOf(first, second)

        val deleteResult = viewModel.execute(EditorCommand.Delete(setOf(0)))
        val insertResult = viewModel.execute(
            EditorCommand.Insert(0, listOf(SubtitleEntry(text = "插入")))
        )

        assertTrue(deleteResult.structureChanged)
        assertTrue(insertResult.structureChanged)
        assertEquals(listOf("插入", "第二"), viewModel.subtitleDocument.entries.map { it.text })
        assertEquals(second.stableId, viewModel.subtitleDocument.entries[1].stableId)
    }
}

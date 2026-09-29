package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorCommandTest {
    @Test
    fun loadingAndSavingUsesTheSharedSubtitleDocument() {
        val viewModel = EditorViewModel()
        val source = "1\n00:00:01,000 --> 00:00:02,000\n你好\n"

        val document = viewModel.loadSubtitleContent(source, "sample.srt")
        val saved = viewModel.buildSaveContent(requireNonEmptyList = true)

        assertEquals(SubtitleParser.SubtitleFormat.SRT, document.format)
        assertEquals("你好", viewModel.subtitleDocument.entries.single().text)
        assertTrue(saved?.contains("00:00:01,000 --> 00:00:02,000") == true)
        assertTrue(saved?.contains("你好") == true)
    }

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
        assertEquals("新文本", viewModel.document.value.entries.single().text)
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

    @Test
    fun bulkTextCommandRemovesBlankRowsAndUpdatesIndices() {
        val viewModel = EditorViewModel()
        viewModel.subtitleEntries = mutableListOf(
            SubtitleEntry(index = 1, text = "第一"),
            SubtitleEntry(index = 2, text = "第二"),
            SubtitleEntry(index = 3, text = "第三")
        )

        val result = viewModel.execute(
            EditorCommand.UpdateTexts(listOf(1 to "", 2 to "更新"))
        )

        assertEquals(1, result.removedCount)
        assertEquals(listOf("第一", "更新"), viewModel.subtitleDocument.entries.map { it.text })
        assertEquals(listOf(1, 2), viewModel.subtitleDocument.entries.map { it.index })
    }

    @Test
    fun mergeCommandJoinsTextWithLineBreakAndKeepsWholeTimeRange() {
        val viewModel = EditorViewModel()
        viewModel.subtitleEntries = mutableListOf(
            SubtitleEntry(index = 1, startTime = 100L, endTime = 500L, text = "第一"),
            SubtitleEntry(index = 2, startTime = 600L, endTime = 900L, text = "第二"),
            SubtitleEntry(index = 3, startTime = 1_000L, endTime = 1_200L, text = "第三")
        )

        val result = viewModel.execute(EditorCommand.Merge(listOf(0, 1)))

        assertTrue(result.structureChanged)
        assertEquals(1, result.removedCount)
        assertEquals(listOf("第一\n第二", "第三"),
            viewModel.subtitleDocument.entries.map { it.text })
        assertEquals(100L, viewModel.subtitleDocument.entries[0].startTime)
        assertEquals(900L, viewModel.subtitleDocument.entries[0].endTime)
        assertEquals(listOf(1, 2), viewModel.subtitleDocument.entries.map { it.index })
    }

    @Test
    fun mergeCommandRejectsNonConsecutiveRows() {
        val viewModel = EditorViewModel()
        viewModel.subtitleEntries = mutableListOf(
            SubtitleEntry(text = "第一"),
            SubtitleEntry(text = "第二"),
            SubtitleEntry(text = "第三")
        )

        val result = viewModel.execute(EditorCommand.Merge(listOf(0, 2)))

        assertTrue(!result.structureChanged)
        assertEquals(listOf("第一", "第二", "第三"),
            viewModel.subtitleDocument.entries.map { it.text })
    }

    @Test
    fun splitCommandKeepsLeftIdentityAndCreatesRightEntry() {
        val viewModel = EditorViewModel()
        val original = SubtitleEntry(
            index = 1,
            startTime = 100L,
            endTime = 900L,
            text = "你好世界"
        )
        viewModel.subtitleEntries = mutableListOf(original)

        val result = viewModel.execute(
            EditorCommand.Split(0, 400L, "你好", "世界")
        )

        assertTrue(result.structureChanged)
        assertEquals(listOf("你好", "世界"),
            viewModel.subtitleDocument.entries.map { it.text })
        assertEquals(listOf(100L, 400L),
            viewModel.subtitleDocument.entries.map { it.startTime })
        assertEquals(listOf(400L, 900L),
            viewModel.subtitleDocument.entries.map { it.endTime })
        assertEquals(original.stableId, viewModel.subtitleDocument.entries[0].stableId)
        assertTrue(original.stableId != viewModel.subtitleDocument.entries[1].stableId)
        assertEquals(listOf(1, 2), viewModel.subtitleDocument.entries.map { it.index })
    }

    @Test
    fun extendCommandUsesAdjacentTimesForAllSelectedRows() {
        val viewModel = EditorViewModel()
        viewModel.subtitleEntries = mutableListOf(
            SubtitleEntry(startTime = 100L, endTime = 500L, text = "第一"),
            SubtitleEntry(startTime = 600L, endTime = 900L, text = "第二"),
            SubtitleEntry(startTime = 1_000L, endTime = 1_200L, text = "第三")
        )

        val previousResult = viewModel.execute(
            EditorCommand.ExtendToAdjacent(setOf(1, 2), towardPrevious = true)
        )
        assertEquals(setOf(1, 2), previousResult.changedPositions)
        assertEquals(listOf(100L, 500L, 900L),
            viewModel.subtitleDocument.entries.map { it.startTime })

        val nextViewModel = EditorViewModel()
        nextViewModel.subtitleEntries = mutableListOf(
            SubtitleEntry(startTime = 100L, endTime = 500L, text = "第一"),
            SubtitleEntry(startTime = 600L, endTime = 900L, text = "第二"),
            SubtitleEntry(startTime = 1_000L, endTime = 1_200L, text = "第三")
        )
        val nextResult = nextViewModel.execute(
            EditorCommand.ExtendToAdjacent(setOf(0, 1), towardPrevious = false)
        )
        assertEquals(setOf(0, 1), nextResult.changedPositions)
        assertEquals(listOf(600L, 1_000L, 1_200L),
            nextViewModel.subtitleDocument.entries.map { it.endTime })
    }
}

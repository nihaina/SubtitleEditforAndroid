package com.subtitleedit.usecase

import com.subtitleedit.EditorCommand
import com.subtitleedit.EditorDocumentState
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorUseCaseTest {
    @Test
    fun loadAndSaveUseCasesKeepDocumentFormatAndContent() {
        val source = "1\n00:00:00,000 --> 00:00:01,000\nhello\n"
        val document = LoadSubtitleDocumentUseCase()(source, "sample.srt")

        assertEquals(SubtitleParser.SubtitleFormat.SRT, document.format)
        assertEquals("hello", document.entries.single().text)
        val saved = SaveSubtitleDocumentUseCase()(
            document = document,
            sourceContent = source,
            sourceViewMode = false
        )
        assertNotNull(saved)
        assertTrue(saved!!.contains("00:00:00,000 --> 00:00:01,000"))
        assertTrue(saved.contains("hello"))
    }

    @Test
    fun convertUseCaseConvertsSrtToVtt() {
        val source = "1\n00:00:00,000 --> 00:00:01,000\nhello\n"

        val result = ConvertSubtitleFormatUseCase()(
            source,
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.SubtitleFormat.VTT
        )

        assertTrue(result.startsWith("WEBVTT"))
        assertTrue(result.contains("00:00:00.000 --> 00:00:01.000"))
    }

    @Test
    fun applyAndSyncUseCasesUpdateDocumentAndSource() {
        val state = EditorDocumentState().apply {
            currentFormat = SubtitleParser.SubtitleFormat.SRT
            originalFileContent = "1\n00:00:00,000 --> 00:00:01,000\nold\n"
            subtitleEntries = mutableListOf(
                SubtitleEntry(startTime = 0L, endTime = 1_000L, text = "old")
            )
        }

        val editResult = ApplySubtitleEditUseCase()(state, EditorCommand.UpdateText(0, "new"))
        val updatedSource = SyncSourceDocumentUseCase()(
            content = state.originalFileContent,
            format = state.currentFormat,
            oldEntries = listOf(SubtitleEntry(startTime = 0L, endTime = 1_000L, text = "old")),
            newEntries = state.subtitleEntries
        )

        assertEquals(setOf(0), editResult.changedPositions)
        assertTrue(updatedSource.contains("new"))
    }

    @Test
    fun searchReplaceUseCaseSupportsContentAndEntryReplacement() {
        val useCase = SearchReplaceSubtitleUseCase()
        val entries = listOf(SubtitleEntry(text = "hello"), SubtitleEntry(text = "world"))

        val contentResult = useCase.replaceAllInContent("hello hello", "hello", "hi")
        val updates = useCase.collectEntryUpdates(entries, "hell", "hi")

        assertEquals(2, contentResult.matchCount)
        assertEquals("hi hi", contentResult.newContent)
        assertEquals(1, updates.size)
        assertEquals("hio", updates.single().newText)
        assertNotNull(useCase.replaceFirstText("hello", "he", "yo"))
    }
}

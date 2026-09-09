package com.subtitleedit.repository

import android.content.Context
import android.net.Uri
import com.subtitleedit.EditorCommand
import com.subtitleedit.EditorViewModel
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.usecase.ConvertSubtitleFormatUseCase
import com.subtitleedit.usecase.LoadSubtitleDocumentUseCase
import com.subtitleedit.usecase.SaveSubtitleDocumentUseCase
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import com.subtitleedit.util.subtitle.SubtitleDocument
import java.io.IOException
import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRepositoryTest {
    @Test
    fun loadUsesFileNameForSourceOnlyDocuments() {
        val content = "[Script Info]\r\nTitle: example\r\n"

        val document = DefaultSubtitleRepository().load(content, "example.ssa")

        assertEquals(SubtitleFormat.SSA, document.format)
        assertEquals(content, document.header)
        assertTrue(document.entries.isEmpty())
    }

    @Test
    fun saveAndLoadPreserveWebVttMetadata() {
        val repository = DefaultSubtitleRepository()
        val entry = SubtitleEntry(
            index = 1,
            startTime = 1_000L,
            endTime = 2_000L,
            text = "first\nsecond",
            cueIdentifier = "cue-one",
            cueSettings = "align:start position:10%"
        )
        val document = SubtitleDocument(
            format = SubtitleFormat.VTT,
            entries = listOf(entry),
            header = "WEBVTT\n\nSTYLE\n::cue { color: lime; }",
            footer = "NOTE preserved footer"
        )

        val restored = repository.load(repository.save(document), "sample.vtt")

        assertEquals(document.format, restored.format)
        assertEquals(document.header, restored.header)
        assertEquals(document.footer, restored.footer)
        assertEquals(entry.copy(stableId = restored.entries.single().stableId), restored.entries.single())
    }

    @Test
    fun conversionPreservesCueContentWithoutLeakingWebVttSections() {
        val repository = DefaultSubtitleRepository()
        val content = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nhello\n\nNOTE footer\n"

        val converted = repository.convert(content, SubtitleFormat.VTT, SubtitleFormat.SRT)
        val document = repository.load(converted, "sample.srt")

        assertEquals(SubtitleFormat.SRT, document.format)
        assertEquals("hello", document.entries.single().text)
        assertEquals(1_000L, document.entries.single().startTime)
        assertEquals(2_000L, document.entries.single().endTime)
        assertFalse(converted.contains("NOTE footer"))
    }

    @Test
    fun useCasesForwardArgumentsToInjectedRepository() {
        val document = SubtitleDocument(SubtitleFormat.SRT, listOf(SubtitleEntry(text = "hello")))
        val repository = RecordingSubtitleRepository(document)

        assertSame(document, LoadSubtitleDocumentUseCase(repository)("source", "file.srt"))
        assertEquals(listOf("source" to "file.srt"), repository.loads)
        assertEquals("saved", SaveSubtitleDocumentUseCase(repository)(document, "raw", false))
        assertSame(document, repository.saves.single())
        assertEquals(
            "converted",
            ConvertSubtitleFormatUseCase(repository)("source", SubtitleFormat.SRT, SubtitleFormat.VTT)
        )
        assertEquals(listOf(Triple("source", SubtitleFormat.SRT, SubtitleFormat.VTT)), repository.conversions)
    }

    @Test
    fun sourceModeAndRejectedEmptyListDoNotSerialize() {
        val document = SubtitleDocument(SubtitleFormat.TXT, emptyList())
        val repository = RecordingSubtitleRepository(document)
        val save = SaveSubtitleDocumentUseCase(repository)
        val source = "  raw\r\n\r\ntext  "

        assertEquals(source, save(document, source, true, requireNonEmptyList = true))
        assertEquals("", save(document, "", true))
        assertNull(save(document, source, false, requireNonEmptyList = true))
        assertTrue(repository.saves.isEmpty())
        assertEquals("saved", save(document, source, false))
    }

    @Test
    fun repositoryFailureIsNotReportedAsSuccessfulSave() {
        val document = SubtitleDocument(SubtitleFormat.SRT, emptyList())
        val failure = IOException("cannot serialize")
        val repository = RecordingSubtitleRepository(document).apply { saveFailure = failure }

        val thrown = assertThrows(IOException::class.java) {
            SaveSubtitleDocumentUseCase(repository)(document, "source", false)
        }

        assertSame(failure, thrown)
    }

    @Test
    fun viewModelUsesInjectedRepositoryForLoadingAndSaving() {
        val document = SubtitleDocument(SubtitleFormat.SRT, listOf(SubtitleEntry(text = "before")))
        val repository = RecordingSubtitleRepository(document)
        val viewModel = EditorViewModel(repository)

        viewModel.loadSubtitleContent("source", "sample.srt")
        viewModel.execute(EditorCommand.UpdateText(0, "after"))

        assertEquals(listOf("source" to "sample.srt"), repository.loads)
        assertEquals("after", viewModel.document.value.entries.single().text)
        assertEquals("saved", viewModel.buildSaveContent())
        assertEquals("after", repository.saves.single().entries.single().text)
        assertEquals("before", document.entries.single().text)
    }

    private class RecordingSubtitleRepository(
        private val document: SubtitleDocument
    ) : SubtitleRepository {
        private val storageRepository = DefaultSubtitleRepository()
        val loads = mutableListOf<Pair<String, String?>>()
        val saves = mutableListOf<SubtitleDocument>()
        val conversions = mutableListOf<Triple<String, SubtitleFormat, SubtitleFormat>>()
        var saveFailure: IOException? = null

        override fun load(content: String, fileName: String?): SubtitleDocument {
            loads += content to fileName
            return document
        }

        override fun save(document: SubtitleDocument): String {
            saveFailure?.let { throw it }
            saves += document
            return "saved"
        }

        override fun convert(content: String, from: SubtitleFormat, to: SubtitleFormat): String {
            conversions += Triple(content, from, to)
            return "converted"
        }

        override fun readFile(file: File, charset: Charset?): String =
            storageRepository.readFile(file, charset)

        override fun readUri(context: Context, uri: Uri, charset: Charset?): String =
            storageRepository.readUri(context, uri, charset)

        override fun writeFile(file: File, content: String, charset: Charset) {
            storageRepository.writeFile(file, content, charset)
        }

        override fun writeUri(context: Context, uri: Uri, content: String, charset: Charset) {
            storageRepository.writeUri(context, uri, content, charset)
        }
    }
}

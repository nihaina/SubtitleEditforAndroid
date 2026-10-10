package com.subtitleedit.editor

import com.subtitleedit.EditorViewModel
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSubtitlePreviewControllerTest {
    @Test
    fun vttMenuColorCreatesAColoredAssPlaybackTrackWithoutChangingTheVttDocument() = runBlocking {
        val model = EditorViewModel()
        model.loadSubtitleContent(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<b>Hello</b>\n", "test.vtt"
        )
        model.applySubtitleColor(setOf(0), 0x80123456.toInt(), emptySet())
        val coloredDocument = model.subtitleDocument
        val source = model.sourceViewContent

        val preview = preview(SubtitleFormat.VTT, coloredDocument.entries, false, source)

        assertEquals("ass", preview.extension)
        assertTrue(preview.content.contains("\\1c&H563412&"))
        assertTrue(preview.content.contains("\\1a&H7F&"))
        assertTrue(preview.content.contains("\\b1"))
        assertTrue(preview.content.contains("Hello"))
        assertFalse(preview.content.contains("<c."))
        assertEquals(coloredDocument, model.subtitleDocument)
        assertEquals(source, model.sourceViewContent)
        assertTrue(model.buildSaveContent()!!.startsWith("WEBVTT"))
    }

    @Test
    fun vttListPreviewUsesCurrentRowsWhileReadingTheCssFromSource() = runBlocking {
        val source = "WEBVTT\n\nSTYLE\n::cue(.red) { color:rgb(255,0,0); }\n\n" +
            "00:00:01.000 --> 00:00:02.000\n<c.red>Old</c>\n"
        val rows = SubtitleParser.parseVTT(source).map {
            it.copy(startTime = 3_000, endTime = 4_000, text = "<c.red>Edited</c>")
        }

        val preview = preview(SubtitleFormat.VTT, rows, false, source)

        assertTrue(preview.content.contains("0:00:03.00,0:00:04.00"))
        assertTrue(preview.content.contains("Edited"))
        assertTrue(preview.content.contains("\\1c&H0000FF&"))
        assertFalse(preview.content.contains("Old"))
    }

    @Test
    fun vttSourcePreviewParsesTheCurrentSourceAndTimestampMapInsteadOfStaleRows() = runBlocking {
        val source = "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:90000\n\n" +
            "STYLE\n::cue(.blue) { color:#0000FF; }\n\n" +
            "00:00:01.000 --> 00:00:02.000\n<c.blue>Source</c>\n"

        val preview = preview(SubtitleFormat.VTT, listOf(SubtitleEntry(text = "Stale")), true, source)

        assertTrue(preview.content.contains("0:00:02.00,0:00:03.00"))
        assertTrue(preview.content.contains("Source"))
        assertTrue(preview.content.contains("\\1c&HFF0000&"))
        assertFalse(preview.content.contains("Stale"))
    }

    @Test
    fun vttSourcePreviewDecodesEntitiesOnceWithoutTurningLiteralTagsIntoFormatting() = runBlocking {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n" +
            "&lt;i&gt;literal&lt;/i&gt; &amp;lt;b&amp;gt; <b>bold</b>\n"

        val preview = preview(SubtitleFormat.VTT, emptyList(), true, source)

        assertTrue(preview.content.contains("<i>literal</i> &lt;b&gt; {\\b1}bold{\\b0}"))
        assertFalse(preview.content.contains("\\i1"))
    }

    @Test
    fun vttListPreviewKeepsLiteralTagsWhenOnlyCueTimesHaveChanged() = runBlocking {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n&lt;i&gt;literal&lt;/i&gt;\n"
        val rows = SubtitleParser.parseVTT(source).map { it.copy(startTime = 3_000, endTime = 4_000) }

        val preview = preview(SubtitleFormat.VTT, rows, false, source)

        assertTrue(preview.content.contains("0:00:03.00,0:00:04.00"))
        assertTrue(preview.content.contains("<i>literal</i>"))
        assertFalse(preview.content.contains("\\i1"))
    }

    @Test
    fun vttListPreviewKeepsEntitiesAfterRowRemovalWhileUsingFreshEditedText() = runBlocking {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nRemoved\n\n" +
            "00:00:03.000 --> 00:00:04.000\n&lt;i&gt;literal&lt;/i&gt;\n\n" +
            "00:00:05.000 --> 00:00:06.000\nOld\n"
        val rows = SubtitleParser.parseVTT(source).drop(1).mapIndexed { index, entry ->
            if (index == 1) entry.copy(text = "<i>Edited</i>") else entry
        }

        val preview = preview(SubtitleFormat.VTT, rows, false, source)

        assertTrue(preview.content.contains("<i>literal</i>"))
        assertTrue(preview.content.contains("{\\i1}Edited{\\i0}"))
        assertFalse(preview.content.contains("Old"))
        assertFalse(preview.content.contains("Removed"))
    }

    @Test
    fun srtAndRawAssPreviewsKeepTheirExistingFormatsAndContent() = runBlocking {
        val rows = listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "<font color=red>Hello</font>"))
        val srt = preview(SubtitleFormat.SRT, rows, false, "old source")
        assertEquals("srt", srt.extension)
        assertEquals(SubtitleParser.toSRT(rows), srt.content)
        val source = "[Script Info]\nTitle: raw ASS\n"
        val ass = preview(SubtitleFormat.ASS, rows, true, source)
        assertEquals("ass", ass.extension)
        assertEquals(source, ass.content)
    }

    private data class Preview(val extension: String, val content: String)

    private suspend fun preview(
        format: SubtitleFormat,
        entries: List<SubtitleEntry>,
        sourceViewMode: Boolean,
        source: String
    ): Preview {
        val cache = Files.createTempDirectory("subtitle-preview-test-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val track = CompletableDeferred<File?>()
        val controller = EditorSubtitlePreviewController(cache, scope) { track.complete(it) }
        try {
            controller.schedule(format, entries, sourceViewMode, source)
            val file = withTimeout(5_000) { track.await() } ?: error("Preview track missing")
            return Preview(file.extension, file.readText())
        } finally {
            controller.release()
            scope.cancel()
            cache.deleteRecursively()
        }
    }
}

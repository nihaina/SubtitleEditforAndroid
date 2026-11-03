package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EditorSourceContextRefreshTest {
    @Test fun acceptedFullParseAfterRemovingLrcOffsetRestoresLocalEdits() = runBlocking {
        val before = "[offset:500]\n[00:01.00]One\n[00:03.00]Two\n"
        val after = before.removePrefix("[offset:500]\n")
        verifyContextRefresh(before, after, SubtitleFormat.LRC, "Two")
    }

    @Test fun acceptedFullParseAfterRemovingWordTimestampsRestoresLocalVttEdits() = runBlocking {
        val before = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nOne <00:00:01.500>word\n\n" +
            "00:00:03.000 --> 00:00:04.000\nTwo\n"
        val after = before.replace("<00:00:01.500>", "")
        verifyContextRefresh(before, after, SubtitleFormat.VTT, "Two")
    }

    @Test fun acceptedFullParseAfterRemovingDuplicateCueRestoresLocalVttEdits() = runBlocking {
        val duplicate = "00:00:01.000 --> 00:00:02.000\nDuplicate\n\n"
        val before = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nOne\n\n" + duplicate +
            "00:00:03.000 --> 00:00:04.000\nTwo\n"
        val after = before.replace(duplicate, "")
        verifyContextRefresh(before, after, SubtitleFormat.VTT, "Two")
    }

    @Test fun refreshingAStillGlobalDocumentKeepsFullParseRequired() {
        val content = "[offset:500]\n[00:01.00]One\n[00:03.00]Two\n"
        val lines = content.trimEnd().split('\n')
        val rows = SubtitleParser.parseLRC(content)
        val controller = EditorSourceLineEditController({ lines.size }, { lines[it] }, { SubtitleFormat.LRC }, { rows })
        controller.prepareDocument(content, SubtitleFormat.LRC)
        val offset = content.indexOf("Two")

        controller.prepareDocument(content, SubtitleFormat.LRC)

        assertTrue(controller.requiresFullParse(content, content.replace("Two", "Edited"),
            SubtitleFormat.LRC, offset, offset + 3, offset + 6))
    }

    @Test fun mapsInsideAMultiLinePasteTriggerAFullParseBeforeLaterLocalEdits() {
        val before = "WEBVTT\n\nfirst-id\n00:00:01.000 --> 00:00:02.000\nOne\n\n" +
            "second-id\n00:00:03.000 --> 00:00:04.000\nTwo\n"
        val inserted = "NOTE pasted metadata\n\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:1800000\n\n"
        val start = before.indexOf("second-id")
        val after = before.replaceRange(start, start, inserted)
        var rows = SubtitleParser.parseVTT(before)
        var lines = before.split('\n')
        val controller = EditorSourceLineEditController({ lines.size }, { lines[it] }, { SubtitleFormat.VTT }, { rows })
        controller.prepareDocument(before, SubtitleFormat.VTT)

        assertTrue(controller.requiresFullParse(before, after, SubtitleFormat.VTT, start, start, start + inserted.length))
        // Accept the full parse and update both the rows and cached map presence.
        rows = SubtitleParser.parseVTT(after)
        lines = after.split('\n')
        controller.prepareDocument(after, SubtitleFormat.VTT)
        val body = after.indexOf("Two")
        val edited = after.replace("Two", "Edited")
        assertFalse(controller.requiresFullParse(after, edited, SubtitleFormat.VTT, body, body + 3, body + 6))
        lines = edited.split('\n')
        val line = after.substring(0, body).count { it == '\n' }
        controller.invalidateLineIndex(line)
        val result = controller.resolveBlock(after, edited, line, 1, 1, body, body)!!
        assertEquals(23_000L, result.entries.single().startTime)
    }

    @Test fun globalMarkersInsideChangedFragmentsCannotBeMissedByTheBoundaryLines() {
        val cases = listOf(
            Triple(SubtitleFormat.LRC, "[00:01.00]One\n", "plain\n[offset:500]\nmore plain\n"),
            Triple(SubtitleFormat.VTT, "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nOne\n",
                "plain\n<00:00:01.500>word\nmore plain\n")
        )
        cases.forEach { (format, before, inserted) ->
            val after = before + inserted
            val lines = before.split('\n')
            val rows = SubtitleParser.parseDocument(before, format = format).entries
            val controller = EditorSourceLineEditController({ lines.size }, { lines[it] }, { format }, { rows })
            controller.prepareDocument(before, format)
            assertTrue(controller.requiresFullParse(before, after, format, before.length, before.length, after.length))
            // Even if the document cache began before the marker was added, a multi-line
            // replacement/removal must inspect the interior of the old fragment too.
            controller.prepareDocument(before, format)
            assertTrue(controller.requiresFullParse(after, before, format, before.length, after.length, before.length))
        }
    }

    /** Mirrors installation only after a generation-checked full source parse is accepted. */
    private suspend fun verifyContextRefresh(before: String, after: String, format: SubtitleFormat, target: String) {
        var rows: List<SubtitleEntry> = SubtitleParser.parseDocument(before, format = format).entries
        var lines = before.split('\n')
        val lineController = EditorSourceLineEditController({ lines.size }, { lines[it] }, { format }, { rows })
        lineController.prepareDocument(before, format)
        val probeOffset = after.indexOf(target)
        val edited = after.replace(target, "Edited")
        assertTrue(lineController.requiresFullParse(after, edited, format, probeOffset, probeOffset + target.length, probeOffset + 6))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val installed = CompletableDeferred<Unit>()
        val preview = EditorSourcePreviewController(
            scope, { true }, { false }, { 1L }, { format }, { after },
            onParsed = { _, source, parsed ->
                rows = parsed.entries
                lines = source.split('\n')
                lineController.prepareDocument(source, format)
                installed.complete(Unit)
            }, debounceMillis = 0L
        )
        try {
            preview.schedule()
            withTimeout(5_000L) { installed.await() }
            assertFalse(lineController.requiresFullParse(after, edited, format,
                probeOffset, probeOffset + target.length, probeOffset + 6))
            lines = edited.split('\n')
            val targetLine = after.substring(0, probeOffset).count { it == '\n' }
            lineController.invalidateLineIndex(targetLine)
            val update = lineController.resolveBlock(after, edited, targetLine, 1, 1, probeOffset, probeOffset)!!
            val expected = SubtitleParser.parseDocument(edited, format = format).entries
                .subList(update.entryIndex, update.entryIndex + update.entries.size)
            assertEquals(expected.map { it.startTime }, update.entries.map { it.startTime })
            assertEquals(expected.map { it.endTime }, update.entries.map { it.endTime })
            assertEquals(expected.map { it.text }, update.entries.map { it.text })
            assertEquals(rows.subList(update.entryIndex, update.entryIndex + update.entries.size).map { it.stableId },
                update.entries.map { it.stableId })
        } finally {
            preview.cancel()
            scope.cancel()
        }
    }
}

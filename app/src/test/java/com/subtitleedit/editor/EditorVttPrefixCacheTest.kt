package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import org.junit.Assert.*
import org.junit.Test

/** Prefix work is bounded by invalidation, not repeated for every character edit. */
class EditorVttPrefixCacheTest {
    @Test fun twentyThousandCueDocumentReusesItsMappedPrefixAcrossThirtyEdits() {
        val fixture = Fixture(document(20_000, secondMapAt = 10_000), offsets = true)
        val target = 19_999
        val stableId = fixture.rows[target].stableId

        fixture.edit(target, "edited 0")
        val warmedReads = fixture.controller.vttPrefixLinesRead
        assertTrue(warmedReads >= 80_000)

        repeat(30) { index ->
            val update = fixture.edit(target, "edited ${index + 1}")
            assertEquals(warmedReads, fixture.controller.vttPrefixLinesRead)
            assertEquals(stableId, update.entries.single().stableId)
            assertEquals(target * 3_000L + 20_000L, update.entries.single().startTime)
        }
    }

    @Test fun bothBlockPathsReuseThePrefixForTheOldAndNewSource() {
        listOf(false, true).forEach { offsets ->
            val fixture = Fixture(document(20, secondMapAt = 10), offsets)
            fixture.edit(19, "changed first")
            val firstReads = fixture.controller.vttPrefixLinesRead
            val prefixLines = fixture.source.substringBefore("cue-19\n").count { it == '\n' }.toLong()
            assertEquals(prefixLines, firstReads)

            fixture.edit(19, "changed again")

            assertEquals(firstReads, fixture.controller.vttPrefixLinesRead)
            assertEquals(77_000L, fixture.rows[19].startTime)
        }
    }

    @Test fun documentsWithoutAMapDoNotReadThePrefixAtAll() {
        listOf(false, true).forEach { offsets ->
            val fixture = Fixture(document(100, withMap = false), offsets)

            repeat(5) { index -> fixture.edit(99, "changed $index") }

            assertEquals(0L, fixture.controller.vttPrefixLinesRead)
            assertEquals(297_000L, fixture.rows[99].startTime)
        }
    }

    @Test fun movingBackBeforeTheSecondMapRebuildsTheCorrectEarlierContext() {
        listOf(false, true).forEach { offsets ->
            val fixture = Fixture(document(20, secondMapAt = 10), offsets)
            fixture.edit(19, "late edit")
            val lateReads = fixture.controller.vttPrefixLinesRead

            fixture.edit(2, "early edit")

            assertTrue(fixture.controller.vttPrefixLinesRead > lateReads)
            assertEquals(16_000L, fixture.rows[2].startTime)
            fixture.edit(19, "late edit again")
            assertEquals(77_000L, fixture.rows[19].startTime)
        }
    }

    @Test fun invalidatingAnEarlierMapCannotReuseItsPreviousOffset() {
        listOf(false, true).forEach { offsets ->
            val fixture = Fixture(document(20, secondMapAt = 10), offsets)
            fixture.edit(19, "first edit")
            val readCount = fixture.controller.vttPrefixLinesRead
            fixture.replaceSource(
                fixture.source.replace(SECOND_MAP, SECOND_MAP.replace("1800000", "2700000")),
                prepare = false,
                invalidatedLine = fixture.source.substringBefore(SECOND_MAP).count { it == '\n' }
            )

            fixture.edit(19, "after map edit")

            assertTrue(fixture.controller.vttPrefixLinesRead > readCount)
            assertEquals(87_000L, fixture.rows[19].startTime)
        }
    }

    @Test fun fullParseInstallationRefreshesThePresenceAndAbsenceOfMaps() {
        val fixture = Fixture(document(5, withMap = false), offsets = true)
        fixture.edit(4, "before map")
        assertEquals(0L, fixture.controller.vttPrefixLinesRead)
        fixture.replaceSource(fixture.source.replace("WEBVTT\n", "WEBVTT\n$FIRST_MAP\n"), prepare = true)

        fixture.edit(4, "with map")

        assertTrue(fixture.controller.vttPrefixLinesRead > 0)
        assertEquals(22_000L, fixture.rows[4].startTime)
        fixture.replaceSource(fixture.source.replace("$FIRST_MAP\n", ""), prepare = true)
        fixture.edit(4, "without map again")
        assertEquals(0L, fixture.controller.vttPrefixLinesRead)
        assertEquals(12_000L, fixture.rows[4].startTime)
    }

    @Test fun cachedOffsetsRemainCorrectForBomAndCrLfWhenTextLengthChanges() {
        listOf(false, true).forEach { offsets ->
            val fixture = Fixture("\uFEFF" + document(30, secondMapAt = 10).replace("\n", "\r\n"), offsets)
            fixture.edit(29, "longer replacement")
            val reads = fixture.controller.vttPrefixLinesRead

            repeat(8) { index -> fixture.edit(29, "x".repeat(index + 1)) }

            assertEquals(reads, fixture.controller.vttPrefixLinesRead)
            assertEquals(107_000L, fixture.rows[29].startTime)
            fixture.edit(5, "earlier longer body")
            assertEquals(25_000L, fixture.rows[5].startTime)
            fixture.edit(29, "after earlier length change")
            assertEquals(107_000L, fixture.rows[29].startTime)
        }
    }

    private class Fixture(initial: String, private val offsets: Boolean) {
        var source = initial
            private set
        var rows = parse(initial).toMutableList()
            private set
        private var lines = split(initial)
        val controller = EditorSourceLineEditController({ lines.size }, { lines[it] }, { SubtitleFormat.VTT }, { rows })

        init { controller.prepareDocument(initial, SubtitleFormat.VTT) }

        fun edit(position: Int, text: String): EditorSourceLineEditController.BlockUpdate {
            val before = source
            val oldText = rows[position].text
            val cueIdStart = before.indexOf("cue-$position\n").takeIf { it >= 0 }
                ?: before.indexOf("cue-$position\r\n")
            assertTrue(cueIdStart >= 0)
            val start = before.indexOf(oldText, cueIdStart)
            assertTrue(start >= 0)
            val after = before.replaceRange(start, start + oldText.length, text)
            val line = split(before.substring(0, start)).size - 1
            controller.invalidateLineIndex(line)
            assertFalse(controller.requiresFullParse(before, after, SubtitleFormat.VTT,
                start, start + oldText.length, start + text.length))
            lines = split(after)
            val update = controller.resolveBlock(before, after, line, 1, 1,
                start.takeIf { offsets }, start.takeIf { offsets })!!
            assertEquals(position, update.entryIndex)
            assertEquals(1, update.oldEntryCount)
            assertEquals(text, update.entries.single().text)
            assertEquals(rows[position].stableId, update.entries.single().stableId)
            rows[position] = update.entries.single().copy(index = position + 1)
            source = after
            return update
        }

        fun replaceSource(content: String, prepare: Boolean, invalidatedLine: Int = 0) {
            source = content
            lines = split(content)
            val parsed = parse(content)
            rows = parsed.mapIndexed { index, row -> row.copy(stableId = rows[index].stableId) }.toMutableList()
            if (prepare) controller.prepareDocument(content, SubtitleFormat.VTT)
            else controller.invalidateLineIndex(invalidatedLine)
        }
    }

    private companion object {
        const val FIRST_MAP = "X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000"
        const val SECOND_MAP = "X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:1800000"

        fun parse(content: String): List<SubtitleEntry> = SubtitleParser.parseDocument(content, format = SubtitleFormat.VTT).entries
        fun split(content: String) = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        fun document(count: Int, secondMapAt: Int? = null, withMap: Boolean = true) = buildString {
            append("WEBVTT\n")
            if (withMap) append(FIRST_MAP).append('\n')
            append('\n')
            repeat(count) { index ->
                if (index == secondMapAt) append(SECOND_MAP).append("\n\n")
                append("cue-").append(index).append('\n')
                append(timestamp(index * 3)).append(" --> ").append(timestamp(index * 3 + 2)).append('\n')
                append("body ").append(index).append("\n\n")
            }
        }

        fun timestamp(seconds: Int): String = "%02d:%02d:%02d.000".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    }
}

package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import org.junit.Assert.*
import org.junit.Test

/** A local cue edit must retain the same active timestamp map as the full VTT parser. */
class EditorVttTimestampMapEditTest {
    @Test fun secondTimestampMapAppliesToTextEditsInBothBlockPaths() {
        forEachPath { offsets ->
            val source = twoMaps()
            val update = resolve(source, source.replace("second body", "edited body"), "second body", offsets)
            assertEquals(1, update.entryIndex)
            assertEquals(1, update.oldEntryCount)
            assertEquals(23_000L, update.entries.single().startTime)
            assertEquals(24_000L, update.entries.single().endTime)
        }
    }

    @Test fun localUndoUsesTheSameSecondMapAndRetainsTheCueIdentity() {
        forEachPath { offsets ->
            val original = twoMaps()
            val edited = original.replace("second body", "edited body")
            val rows = parse(original)
            val changed = resolve(original, edited, "second body", offsets, rows)
            assertEquals(rows[1].stableId, changed.entries.single().stableId)
            val current = rows.toMutableList().also { it[1] = changed.entries.single() }

            val restored = resolve(edited, original, "edited body", offsets, current)

            assertEquals(rows[1].stableId, restored.entries.single().stableId)
            assertEquals("second body", restored.entries.single().text)
            assertEquals(23_000L, restored.entries.single().startTime)
        }
    }

    @Test fun aFutureMapCannotOffsetAnEarlierCue() {
        forEachPath { offsets ->
            val source = twoMaps(includeFirstMap = false)
            val update = resolve(source, source.replace("first body", "edited first"), "first body", offsets)
            assertEquals(1_000L, update.entries.single().startTime)
            assertEquals(2_000L, update.entries.single().endTime)
        }
    }

    @Test fun aMapInsideTheAffectedBlockSupersedesThePrefixMap() {
        forEachPath { offsets ->
            val source = "WEBVTT\n$FIRST_MAP\n\n" +
                "00:00:01.000 --> 00:00:02.000\nfirst body\n\n" +
                "$SECOND_MAP\n00:00:03.000 --> 00:00:04.000\nsecond body\n"
            val update = resolve(source, source.replace("second body", "edited second"), "second body", offsets)
            assertEquals(23_000L, update.entries.single().startTime)
        }
    }

    @Test fun aPrefixMapInsideNoteStyleOrRegionMetadataDoesNotBecomeActive() {
        forEachPath { offsets ->
            listOf("NOTE mapping example", "STYLE", "REGION").forEach { marker ->
                val source = "WEBVTT\n$FIRST_MAP\n\n" +
                    "00:00:01.000 --> 00:00:02.000\nfirst body\n\n" +
                    "$marker\n$SECOND_MAP\n\n" +
                    "00:00:03.000 --> 00:00:04.000\nsecond body\n"
                val update = resolve(source, source.replace("second body", "edited second"), "second body", offsets)
                assertEquals(13_000L, update.entries.single().startTime)
            }
        }
    }

    @Test fun malformedMapAndMapLikeIdentifiersDoNotReplaceTheActiveMap() {
        forEachPath { offsets ->
            listOf("X-TIMESTAMP-MAP label", "X-TIMESTAMP-MAP=LOCAL:00:00:00.000").forEach { identifier ->
                val source = "WEBVTT\n$FIRST_MAP\n\n" +
                    "00:00:01.000 --> 00:00:02.000\nfirst body\n\n" +
                    "$identifier\n00:00:03.000 --> 00:00:04.000\nsecond body\n"
                val update = resolve(source, source.replace("second body", "edited second"), "second body", offsets)
                assertEquals(identifier, update.entries.single().cueIdentifier)
                assertEquals(13_000L, update.entries.single().startTime)
            }
        }
    }

    @Test fun aWebVttLikeCueIdentifierStillGetsTheActiveMapPrefix() {
        forEachPath { offsets ->
            val source = twoMaps().replace("second-id", "WEBVTT speaker")
            val update = resolve(source, source.replace("second body", "edited second"), "second body", offsets)
            assertEquals("WEBVTT speaker", update.entries.single().cueIdentifier)
            assertEquals(23_000L, update.entries.single().startTime)
        }
    }

    @Test fun multilineEditsKeepTheMapAcrossBomAndCrLfDocuments() {
        forEachPath { offsets ->
            val source = "\uFEFF" + twoMaps().replace("\n", "\r\n")
            val after = source.replace("second body", "second body\r\ncontinued")
            val update = resolve(source, after, "second body", offsets, newLineCount = 2)
            assertEquals("second body\ncontinued", update.entries.single().text)
            assertEquals(23_000L, update.entries.single().startTime)
        }
    }

    @Test fun aCueSplitKeepsBothNewCuesOnTheCurrentlyActiveMap() {
        forEachPath { offsets ->
            val source = twoMaps()
            val oldBlock = "second-id\n00:00:03.000 --> 00:00:04.000\nsecond body"
            val newBlock = "second-id\n00:00:03.000 --> 00:00:03.500\nleft body\n\n" +
                "right-id\n00:00:03.500 --> 00:00:04.000\nright body"
            val after = source.replace(oldBlock, newBlock)
            val update = resolve(source, after, "second-id", offsets, oldLineCount = 3, newLineCount = 7)
            assertEquals(1, update.entryIndex)
            assertEquals(1, update.oldEntryCount)
            assertEquals(listOf(23_000L, 23_500L), update.entries.map { it.startTime })
            assertEquals(listOf(23_500L, 24_000L), update.entries.map { it.endTime })
            assertEquals(listOf("left body", "right body"), update.entries.map { it.text })
        }
    }

    private fun resolve(
        before: String,
        after: String,
        target: String,
        offsets: Boolean,
        rows: List<SubtitleEntry> = parse(before),
        oldLineCount: Int = 1,
        newLineCount: Int = 1
    ): EditorSourceLineEditController.BlockUpdate {
        val beforeOffset = before.indexOf(target)
        assertTrue(beforeOffset >= 0)
        val startLine = before.substring(0, beforeOffset).replace("\r\n", "\n").replace('\r', '\n').count { it == '\n' }
        val lines = after.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        assertEquals(before.substring(0, beforeOffset), after.substring(0, beforeOffset))
        val controller = EditorSourceLineEditController({ lines.size }, { lines[it] }, { SubtitleFormat.VTT }, { rows })
        controller.prepareDocument(before, SubtitleFormat.VTT)
        val update = controller.resolveBlock(
            before, after, startLine, oldLineCount, newLineCount,
            oldStartOffset = beforeOffset.takeIf { offsets },
            newStartOffset = beforeOffset.takeIf { offsets }
        )
        assertNotNull("Block path offsets=$offsets must remain local", update)
        val resolved = checkNotNull(update)
        val expected = parse(after).subList(resolved.entryIndex, resolved.entryIndex + resolved.entries.size)
        assertEquals(expected.map { it.startTime }, resolved.entries.map { it.startTime })
        assertEquals(expected.map { it.endTime }, resolved.entries.map { it.endTime })
        assertEquals(expected.map { it.text }, resolved.entries.map { it.text })
        assertEquals(expected.map { it.cueIdentifier }, resolved.entries.map { it.cueIdentifier })
        return resolved
    }

    private fun parse(content: String) = SubtitleParser.parseDocument(content, format = SubtitleFormat.VTT).entries

    private fun forEachPath(action: (Boolean) -> Unit) = listOf(false, true).forEach(action)

    private fun twoMaps(includeFirstMap: Boolean = true) = "WEBVTT\n" +
        (if (includeFirstMap) "$FIRST_MAP\n" else "") + "\n" +
        "first-id\n00:00:01.000 --> 00:00:02.000\nfirst body\n\n" +
        "$SECOND_MAP\n\nsecond-id\n00:00:03.000 --> 00:00:04.000\nsecond body\n"

    private companion object {
        const val FIRST_MAP = "X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000"
        const val SECOND_MAP = "X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:1800000"
    }
}

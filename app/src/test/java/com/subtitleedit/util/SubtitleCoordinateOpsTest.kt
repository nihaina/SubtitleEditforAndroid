package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.WebVttCueProcessing
import com.subtitleedit.util.subtitle.WebVttPreviewWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCoordinateOpsTest {
    @Test
    fun assPositionIsReadAsNormalizedPercent() {
        val coordinate = SubtitleCoordinateOps.fromText("{\\pos(192,144)}Hello")

        requireNotNull(coordinate)
        assertEquals(50f, coordinate.xPercent, 0.001f)
        assertEquals(50f, coordinate.yPercent, 0.001f)
    }

    @Test
    fun assPositionIsInsertedWithoutDroppingOtherOverrideTags() {
        val result = SubtitleCoordinateOps.set(
            "{\\an8\\i1}Hello",
            SubtitleCoordinate(25f, 75f)
        )

        assertEquals("{\\pos(96,216)\\an8\\i1}Hello", result)
    }

    @Test
    fun assPositionDoesNotRemoveLiteralEmptyBracesInsideText() {
        val result = SubtitleCoordinateOps.set("Hello {} world", SubtitleCoordinate(50f, 50f))

        assertEquals("{\\pos(192,144)}Hello {} world", result)
    }

    @Test
    fun vttTextAlignmentIsUsedWhenCueSettingsHaveNoCoordinates() {
        val entry = SubtitleEntry(text = "{\\an7}Hello")

        val coordinate = SubtitleCoordinateOps.fromEntry(entry, SubtitleFormat.VTT)

        requireNotNull(coordinate)
        assertEquals(20f, coordinate.xPercent, 0.001f)
        assertEquals(20f, coordinate.yPercent, 0.001f)
    }

    @Test
    fun vttCoordinateSettingsPreserveOtherCueAttributes() {
        val settings = WebVttCueProcessing.settingsForCoordinate(
            "region:main align:center size:80% position:10%,start line:20%,center",
            25f,
            75f
        )

        assertEquals("region:main align:center size:80% position:25%,start line:75%,center", settings)
    }

    @Test
    fun vttPreviewEmitsAssPositionForCoordinateSettings() {
        val document = SubtitleDocument(
            format = SubtitleFormat.VTT,
            entries = listOf(
                SubtitleEntry(
                    startTime = 1_000,
                    endTime = 2_000,
                    text = "Hello",
                    cueSettings = "position:25% line:75%"
                )
            ),
            header = "WEBVTT"
        )

        val preview = WebVttPreviewWriter.write(document)

        assertTrue(preview.contains("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\pos(96,216)}Hello"))
    }
}

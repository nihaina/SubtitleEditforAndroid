package com.subtitleedit.usecase

import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.LrcVariant
import com.subtitleedit.util.subtitle.SubtitleDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveSubtitleDocumentUseCaseTest {
    @Test
    fun lrcSourceSaveCleansManualLyricTagsWithoutRebuildingTheSource() {
        val source = "\uFEFF[ti:<i>Title</i>]\r\n[offset:500]\r\n" +
            "[re: Subtitle Edit - LRC No End Time]\r\n\r\n" +
            "[00:01.234] [00:03.500]  <b><font color=red>One</font></b>  \r\n" +
            "Comment <b>unchanged</b>\r\n[00:05.00]\r\n[00:06.125]<i>Two</i>"
        val document = SubtitleParser.parseDocument(source, "song.lrc")

        val saved = SaveSubtitleDocumentUseCase()(document, source, sourceViewMode = true)!!

        assertEquals(
            "\uFEFF[ti:<i>Title</i>]\r\n[offset:500]\r\n" +
                "[re: Subtitle Edit - LRC No End Time]\r\n\r\n" +
                "[00:01.234] [00:03.500]  One  \r\n" +
                "Comment <b>unchanged</b>\r\n[00:05.00]\r\n[00:06.125]Two",
            saved
        )
        val restored = SubtitleParser.parseDocument(saved, "song.lrc")
        assertEquals(LrcVariant.NO_END_TIME, restored.lrcVariant)
        assertEquals(document.entries.map { it.startTime }, restored.entries.map { it.startTime })
        assertEquals(document.entries.take(2).map { it.endTime }, restored.entries.take(2).map { it.endTime })
        assertEquals(listOf("One", "One", "Two"), restored.entries.map { it.text })
    }

    @Test
    fun listAndSourceSaveRemoveTheSameLyricTagsInEveryLrcVariant() {
        val sources = listOf(
            "[00:01.00]<i><b><font color=red>Hello</font></b></i>\n[00:02.00]\n",
            "[00:01.234]<i><b><font color=red>Hello</font></b></i>\n[00:02.345]\n",
            "[re: Subtitle Edit - LRC No End Time]\n[00:01.00]<i><b><font color=red>Hello</font></b></i>\n"
        )
        sources.forEach { source ->
            val document = SubtitleParser.parseDocument(source, "song.lrc")
            val save = SaveSubtitleDocumentUseCase()
            assertEquals(
                save(document, source, sourceViewMode = false),
                save(document, source, sourceViewMode = true)
            )
        }
    }

    @Test
    fun sourceSaveLeavesUnformattedLrcAndOtherFormatsUntouched() {
        val lrc = "[ti:<title>]\r[00:01.00]Plain\r[00:02.00]\r"
        val save = SaveSubtitleDocumentUseCase()
        assertEquals(lrc, save(SubtitleDocument(SubtitleParser.SubtitleFormat.LRC, emptyList()), lrc, true))
        val raw = "  <i>raw</i>\r\n\r\n<font color=red>text</font>  "
        listOf(SubtitleParser.SubtitleFormat.SRT, SubtitleParser.SubtitleFormat.VTT, SubtitleParser.SubtitleFormat.TXT).forEach { format ->
            assertEquals(raw, save(SubtitleDocument(format, emptyList()), raw, true))
        }
    }
}

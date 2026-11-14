package com.subtitleedit.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSubtitleSelectionTest {
    private val first = PlayerSubtitleOption("internal:4", "First", PlayerSubtitleKind.INTERNAL, mpvTrackId = 4)
    private val second = PlayerSubtitleOption("internal:2", "Second", PlayerSubtitleKind.INTERNAL, mpvTrackId = 2)
    private val external = PlayerSubtitleOption("external:/media/a.srt", "a.srt", PlayerSubtitleKind.EXTERNAL,
        path = "/media/a.srt")
    private val off = PlayerSubtitleOption("none", "", PlayerSubtitleKind.NONE)

    @Test fun firstListedInternalSubtitleWinsOverTheSidecarFallback() {
        assertEquals(first, PlayerSubtitleSelection.initial(null, listOf(first, second), external))
    }

    @Test fun sidecarIsSelectedWhenThereAreNoInternalSubtitles() {
        assertEquals(external, PlayerSubtitleSelection.initial(null, emptyList(), external))
    }

    @Test fun explicitOffIsPreservedAcrossReloadInsteadOfBeingReplacedByTheDefault() {
        assertEquals(off, PlayerSubtitleSelection.initial(off, listOf(first, second), external))
    }

    @Test fun explicitInternalOrExternalChoiceWinsOverTheFirstTrack() {
        assertEquals(second, PlayerSubtitleSelection.initial(second, listOf(first, second), external))
        assertEquals(external, PlayerSubtitleSelection.initial(external, listOf(first, second), external))
    }

    @Test fun missingSubtitlesRemainOff() {
        assertEquals(off, PlayerSubtitleSelection.initial(null, emptyList(), null))
        assertEquals(off, PlayerSubtitleSelection.initial(null, emptyList(), first))
    }
}

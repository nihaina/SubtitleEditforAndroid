package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertMetadataSelectionTest {
    private val video = MediaConvertSource(true, 1.0)
    private val audio = MediaConvertSource(false, 1.0)

    @Test fun homogeneousSelectionsAllowSingleAndBatchEditing() {
        assertEquals(MediaConvertMetadataSelection.SINGLE_VIDEO, selection(video))
        assertEquals(MediaConvertMetadataSelection.SINGLE_AUDIO, selection(audio))
        assertEquals(MediaConvertMetadataSelection.BATCH_VIDEO, selection(video, video))
        assertEquals(MediaConvertMetadataSelection.BATCH_AUDIO, selection(audio, audio))
        assertTrue(selection(video, video).editable)
        assertTrue(selection(audio, audio).batch)
    }

    @Test fun mixedAndUnknownStreamsCannotApplyBatchInformation() {
        assertEquals(MediaConvertMetadataSelection.MIXED, selection(audio, video))
        assertFalse(selection(audio, video).editable)
        assertEquals(MediaConvertMetadataSelection.UNAVAILABLE, selection(null))
        assertFalse(selection(audio, null).editable)
        assertEquals(MediaConvertMetadataSelection.READING, MediaConvertMetadataSelection.fromSources(listOf(audio, null), true))
        assertEquals(MediaConvertMetadataSelection.EMPTY, selection())
    }

    private fun selection(vararg sources: MediaConvertSource?) =
        MediaConvertMetadataSelection.fromSources(sources.toList(), pending = false)
}

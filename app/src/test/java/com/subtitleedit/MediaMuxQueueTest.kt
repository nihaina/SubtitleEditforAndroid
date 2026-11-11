package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaMuxQueueTest {
    private fun video(id: String) = MediaMuxFile(id, "file:///$id.mp4", "$id.mp4", MediaMuxColumn.VIDEO)
    private fun audio(id: String) = MediaMuxFile(id, "file:///$id.mp3", "$id.mp3", MediaMuxColumn.AUDIO)

    @Test fun importedFilesFillTheFirstFreeMatchingCellWithoutReplacingExistingInputs() {
        val existing = MediaMuxRow("first", video("existing"), audio("original"))
        var nextId = 0
        val added = MediaMuxQueue.add(listOf(existing, MediaMuxRow("blank")),
            listOf(audio("a"), video("v1"), video("v2"), audio("b"))) { "new${nextId++}" }
        assertEquals(existing, added[0])
        assertEquals(listOf("existing", "v1", "v2"), added.map { it.video?.id })
        assertEquals(listOf("original", "a", "b"), added.map { it.audio?.id })
        assertEquals("blank", added[1].id)
    }

    @Test fun removingAnInputLeavesAnEmptyCellAndReimportHasItsOwnIdentity() {
        val original = video("first-instance")
        val result = MediaMuxResult("file:///output.mp4", "output.mp4")
        val removed = MediaMuxQueue.remove(listOf(MediaMuxRow("row", original, audio("a"), result)), "row", MediaMuxColumn.VIDEO)
        assertNull(removed.single().video)
        assertNull(removed.single().output)
        assertEquals("a", removed.single().audio?.id)
        val newInstance = original.copy(id = "second-instance")
        val reimported = MediaMuxQueue.add(removed, listOf(newInstance))
        assertEquals(newInstance, reimported.single().video)
        assertNotEquals(original.id, reimported.single().video?.id)
    }

    @Test fun movingAColumnShiftsBlankCellsAndKeepsOutputWithItsVideo() {
        val first = video("v1")
        val result = MediaMuxResult("file:///out.mp4", "out.mp4")
        val rows = listOf(MediaMuxRow("r1", first, audio("a1"), result),
            MediaMuxRow("r2", null, audio("a2")), MediaMuxRow("r3", video("v3")))
        val moved = MediaMuxQueue.move(rows, MediaMuxColumn.VIDEO, 0, 2)
        assertEquals(listOf(null, "v3", "v1"), moved.map { it.video?.id })
        assertEquals(listOf("a1", "a2", null), moved.map { it.audio?.id })
        assertEquals(result, moved[2].output)
        assertNull(moved[0].output)
        assertEquals(rows.map { it.id }, moved.map { it.id })
        val movedAudio = MediaMuxQueue.move(moved, MediaMuxColumn.AUDIO, 2, 0)
        assertEquals(listOf(null, "a1", "a2"), movedAudio.map { it.audio?.id })
        assertEquals(moved.map { it.video }, movedAudio.map { it.video })
    }

    @Test fun eligibilityIsTemporaryAndDoesNotMutateRowsWhenSwitchingModes() {
        val complete = MediaMuxRow("both", video("v"), audio("a"))
        val videoOnly = MediaMuxRow("video", video("single"))
        val audioOnly = MediaMuxRow("audio", audio = audio("single"))
        assertTrue(complete.eligible(MediaMuxMode.MERGE))
        assertFalse(complete.eligible(MediaMuxMode.SPLIT))
        assertFalse(videoOnly.eligible(MediaMuxMode.MERGE))
        assertTrue(videoOnly.eligible(MediaMuxMode.SPLIT))
        assertFalse(audioOnly.eligible(MediaMuxMode.MERGE))
        assertFalse(audioOnly.eligible(MediaMuxMode.SPLIT))
        assertFalse(MediaMuxRow("empty").eligible(MediaMuxMode.SPLIT))
    }
}

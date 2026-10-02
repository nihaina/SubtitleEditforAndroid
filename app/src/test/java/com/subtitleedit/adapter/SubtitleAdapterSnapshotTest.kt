package com.subtitleedit.adapter

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAdapterSnapshotTest {

    @Test
    fun listSnapshot_isDetachedFromMutableDocumentEntry() {
        val adapter = SubtitleAdapter(
            onItemClick = { _, _ -> },
            onItemLongClick = { _, _ -> },
            onTimeClick = { _, _, _ -> },
            onTextClick = { _, _ -> },
            onJumpToTimeClick = { _, _ -> },
            onSetTimeClick = { _, _ -> }
        )
        val entry = SubtitleEntry(startTime = 1_000L, endTime = 2_000L, text = "原文")

        adapter.submitList(listOf(entry))
        val snapshot = adapter.currentList.single()
        assertNotSame(entry, snapshot)

        entry.startTime = 4_000L
        entry.text = "拖拽后"

        assertEquals(1_000L, snapshot.startTime)
        assertEquals("原文", snapshot.text)
    }

    @Test
    fun replaceListSnapshot_updatesDisplayedTimingWithoutSharingDocumentObject() {
        val adapter = SubtitleAdapter(
            onItemClick = { _, _ -> },
            onItemLongClick = { _, _ -> },
            onTimeClick = { _, _, _ -> },
            onTextClick = { _, _ -> },
            onJumpToTimeClick = { _, _ -> },
            onSetTimeClick = { _, _ -> }
        )
        val documentEntry = SubtitleEntry(startTime = 1_000L, endTime = 2_000L, text = "字幕")
        adapter.submitList(listOf(documentEntry))

        documentEntry.startTime = 3_500L
        documentEntry.endTime = 5_000L
        adapter.replaceListSnapshot(listOf(documentEntry))

        val displayed = adapter.currentList.single()
        assertEquals(3_500L, displayed.startTime)
        assertEquals(5_000L, displayed.endTime)
        assertNotSame(documentEntry, displayed)
    }

    @Test
    fun positionOfStableId_resolvesCurrentPositionAfterRowsAreInserted() {
        val adapter = SubtitleAdapter(
            onItemClick = { _, _ -> },
            onItemLongClick = { _, _ -> },
            onTimeClick = { _, _, _ -> },
            onTextClick = { _, _ -> },
            onJumpToTimeClick = { _, _ -> },
            onSetTimeClick = { _, _ -> }
        )
        val first = SubtitleEntry(startTime = 1_000L, endTime = 2_000L, text = "第一行")
        val retained = SubtitleEntry(startTime = 3_000L, endTime = 4_000L, text = "保留行")
        adapter.submitList(listOf(first, retained))

        val inserted = SubtitleEntry(startTime = 0L, endTime = 500L, text = "插入行")
        adapter.replaceListSnapshot(listOf(inserted, first, retained))

        assertEquals(2, adapter.positionOfStableId(retained.stableId))
        assertEquals(-1, adapter.positionOfStableId(999_999L))
    }

    @Test
    fun searchHighlight_tracksStableRowAcrossPositionChanges() {
        val adapter = SubtitleAdapter(
            onItemClick = { _, _ -> },
            onItemLongClick = { _, _ -> },
            onTimeClick = { _, _, _ -> },
            onTextClick = { _, _ -> },
            onJumpToTimeClick = { _, _ -> },
            onSetTimeClick = { _, _ -> }
        )
        val first = SubtitleEntry(text = "第一行")
        val target = SubtitleEntry(text = "目标词")
        adapter.submitList(listOf(first, target))

        val before = adapter.composeRevision.value
        adapter.highlightSearchResult(1, "目标", stableId = target.stableId)
        assertTrue(adapter.composeRevision.value > before)
        assertEquals(target.stableId, adapter.searchHighlightStableId())

        adapter.replaceListSnapshot(listOf(SubtitleEntry(text = "新增"), first, target))
        assertEquals(target.stableId, adapter.searchHighlightStableId())
        assertEquals(2, adapter.positionOfStableId(requireNotNull(adapter.searchHighlightStableId())))
    }
}

package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryChangeAccumulatorTest {
    @Test
    fun `repeated changes and rename endpoints are delivered once`() {
        val changes = DirectoryChangeAccumulator("/storage/media")
        changes.add("old.srt")
        changes.add("new.srt")
        changes.add("new.srt")

        val batch = changes.drain()!!
        assertEquals("/storage/media", batch.directoryPath)
        assertEquals(setOf("old.srt", "new.srt"), batch.changedNames)
        assertFalse(batch.fullRefresh)
        assertNull(changes.drain())
    }

    @Test
    fun `child count invalidations do not enumerate other directories`() {
        val changes = DirectoryChangeAccumulator("/storage")
        changes.add("Movies", countDirectory = "/storage/Movies")
        changes.add("Movies", countDirectory = "/storage/Movies")

        val batch = changes.drain()!!
        assertEquals(setOf("Movies"), batch.changedNames)
        assertEquals(setOf("/storage/Movies"), batch.countChangedDirectories)
    }

    @Test
    fun `unknown events require a full refresh and next batch starts clean`() {
        val changes = DirectoryChangeAccumulator("/storage")
        changes.add("removed.srt")
        changes.add(null, requireFullRefresh = true)
        assertTrue(changes.drain()!!.fullRefresh)

        changes.add("updated.srt")
        val next = changes.drain()!!
        assertFalse(next.fullRefresh)
        assertEquals(setOf("updated.srt"), next.changedNames)
    }

    @Test
    fun `count only batch is delivered without a named file`() {
        val changes = DirectoryChangeAccumulator("/storage")
        changes.add(null, countDirectory = "/storage/Movies")
        assertEquals(setOf("/storage/Movies"), changes.drain()!!.countChangedDirectories)
        assertNull(changes.drain())
    }
}

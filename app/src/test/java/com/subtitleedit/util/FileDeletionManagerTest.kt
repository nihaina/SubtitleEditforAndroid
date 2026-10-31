package com.subtitleedit.util

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDeletionManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deletesNestedFilesAndDirectoriesAndReportsItemProgress() = runBlocking {
        val root = temporaryFolder.newFolder("root")
        File(root, "first.srt").writeText("one")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "second.srt").writeText("two")
        val reports = mutableListOf<Triple<String, Long, Long>>()

        val result = FileDeletionManager.delete(listOf(root)) { message, completed, total ->
            reports += Triple(message, completed, total)
        }

        assertTrue(result.allDeleted)
        assertEquals(listOf(root), result.deletedSources)
        assertFalse(root.exists())
        assertTrue(reports.isNotEmpty())
        assertEquals(4L, reports.last().second)
        assertEquals(4L, reports.last().third)
        assertTrue(reports.any { it.first == "正在准备删除..." })
    }
}

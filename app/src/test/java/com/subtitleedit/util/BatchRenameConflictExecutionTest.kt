package com.subtitleedit.util

import com.subtitleedit.util.ArchiveManager.ConflictPolicy
import com.subtitleedit.util.ArchiveManager.ConflictResolution
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchRenameConflictExecutionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun file(name: String, content: String) = temporaryFolder.newFile(name).apply { writeText(content) }
    private fun target(name: String) = File(temporaryFolder.root, name)
    private fun children() = temporaryFolder.root.list().orEmpty().sorted()

    @Test
    fun aDuplicatePreviewCanBeResolvedByAutomaticRenameAndThenExecuted() = runBlocking {
        val first = file("a.txt", "first")
        val second = file("b.txt", "second")
        val preview = BatchRename.preview(listOf(first, second), BatchRename.Options(pattern = "same"))
        assertEquals(listOf("same.txt", "same.txt"), preview.map { it.target.name })
        val plan = BatchRenameConflictResolver.resolve(preview) { ConflictResolution(ConflictPolicy.RENAME) }

        assertEquals(2, BatchRename.execute(plan))
        assertEquals("first", target("same.txt").readText())
        assertEquals("second", target("same (1).txt").readText())
        assertEquals(listOf("same (1).txt", "same.txt"), children())
    }

    @Test
    fun overwriteReplacesAnExistingFileOnlyAfterAnExplicitDecision() = runBlocking {
        val source = file("source.txt", "new")
        val destination = file("target.txt", "old")
        val plan = BatchRenameConflictResolver.resolve(listOf(BatchRename.Entry(source, destination))) { conflict ->
            assertEquals("target.txt", conflict.entryName)
            assertEquals("old", destination.readText())
            assertEquals("new", source.readText())
            ConflictResolution(ConflictPolicy.OVERWRITE)
        }

        assertEquals(1, BatchRename.execute(plan))
        assertFalse(source.exists())
        assertEquals("new", destination.readText())
        assertEquals(listOf("target.txt"), children())
    }

    @Test
    fun laterDuplicateOverwriteKeepsTheLaterContentAndRemovesTheDisplacedSource() = runBlocking {
        val first = file("a.txt", "first")
        val second = file("b.txt", "second")
        val plan = BatchRenameConflictResolver.resolve(
            listOf(first, second).map { BatchRename.Entry(it, target("same.txt")) }
        ) { ConflictResolution(ConflictPolicy.OVERWRITE) }

        assertEquals(2, BatchRename.execute(plan))
        assertEquals("second", target("same.txt").readText())
        assertEquals(listOf("same.txt"), children())
    }

    @Test
    fun skipPreservesTheConflictingSourceAndContinuesOtherRenames() = runBlocking {
        val first = file("a.txt", "first")
        val second = file("b.txt", "second")
        val plan = BatchRenameConflictResolver.resolve(
            listOf(first, second).map { BatchRename.Entry(it, target("same.txt")) }
        ) { ConflictResolution(ConflictPolicy.SKIP) }

        assertEquals(1, BatchRename.execute(plan))
        assertEquals("first", target("same.txt").readText())
        assertEquals("second", second.readText())
        assertEquals(listOf("b.txt", "same.txt"), children())
    }

    @Test
    fun publishFailureRestoresTheOriginalSourceAndOverwrittenTarget() = runBlocking {
        val source = file("source.txt", "new")
        val destination = file("target.txt", "old")
        val plan = BatchRenameConflictResolver.resolve(listOf(BatchRename.Entry(source, destination))) {
            ConflictResolution(ConflictPolicy.OVERWRITE)
        }
        var moves = 0

        val failure = assertThrows(IOException::class.java) {
            BatchRename.execute(plan) { from, to ->
                moves++
                if (moves == 3) throw IOException("publish failed")
                Files.move(from.toPath(), to.toPath())
            }
        }

        assertTrue(failure.message.orEmpty().contains("已恢复原名称"))
        assertEquals("new", source.readText())
        assertEquals("old", destination.readText())
        assertEquals(listOf("source.txt", "target.txt"), children())
    }

    @Test
    fun failureRestoresDiscardedSourcesAndEveryOverwrittenTarget() = runBlocking {
        val first = file("a.txt", "first")
        val second = file("b.txt", "second")
        val third = file("c.txt", "third")
        val original = file("same.txt", "old")
        val plan = BatchRenameConflictResolver.resolve(listOf(
            BatchRename.Entry(first, original),
            BatchRename.Entry(second, original),
            BatchRename.Entry(third, target("third.txt"))
        )) { ConflictResolution(ConflictPolicy.OVERWRITE, applyToAll = true) }
        var moves = 0

        assertThrows(IOException::class.java) {
            BatchRename.execute(plan) { from, to ->
                moves++
                if (moves == 6) throw IOException("second publish failed")
                Files.move(from.toPath(), to.toPath())
            }
        }

        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertEquals("third", third.readText())
        assertEquals("old", original.readText())
        assertEquals(listOf("a.txt", "b.txt", "c.txt", "same.txt"), children())
    }

    @Test
    fun overwritingADirectoryReplacesItsContentsAndRemovesOnlyTheApprovedBackup() = runBlocking {
        val source = temporaryFolder.newFolder("source")
        File(source, "new.txt").writeText("new")
        val destination = temporaryFolder.newFolder("target")
        File(destination, "old.txt").writeText("old")
        val plan = BatchRenameConflictResolver.resolve(listOf(BatchRename.Entry(source, destination))) {
            ConflictResolution(ConflictPolicy.OVERWRITE)
        }

        assertEquals(1, BatchRename.execute(plan))
        assertEquals("new", File(destination, "new.txt").readText())
        assertFalse(File(destination, "old.txt").exists())
        assertEquals(listOf("target"), children())
    }

    @Test
    fun aNewConflictCreatedBySkippingCanUseAutomaticRenameWithoutLosingTheSkippedFile() = runBlocking {
        val first = file("a.txt", "first")
        val second = file("ba.txt", "second")
        val occupied = file("bba.txt", "old")
        val seen = mutableListOf<String>()
        val plan = BatchRenameConflictResolver.resolve(listOf(
            BatchRename.Entry(first, second), BatchRename.Entry(second, occupied)
        )) { conflict ->
            seen += conflict.entryName
            ConflictResolution(if (conflict.entryName == "bba.txt") ConflictPolicy.SKIP else ConflictPolicy.RENAME)
        }

        assertEquals(listOf("bba.txt", "ba.txt"), seen)
        assertEquals(1, BatchRename.execute(plan))
        assertEquals("first", target("ba (1).txt").readText())
        assertEquals("second", second.readText())
        assertEquals("old", occupied.readText())
        assertEquals(listOf("ba (1).txt", "ba.txt", "bba.txt"), children())
    }

    @Test
    fun replanningAfterASkipDoesNotDiscardPreviouslyOverwrittenBatchSources() = runBlocking {
        val first = file("a.txt", "first")
        val second = file("c.txt", "second")
        val retained = file("b.txt", "retained")
        val occupied = file("occupied.txt", "old")
        val plan = BatchRenameConflictResolver.resolve(listOf(
            BatchRename.Entry(first, retained),
            BatchRename.Entry(second, retained),
            BatchRename.Entry(retained, occupied)
        )) { conflict ->
            ConflictResolution(if (conflict.entryName == "occupied.txt" || conflict.existingSize == 8L) {
                ConflictPolicy.SKIP
            } else ConflictPolicy.OVERWRITE)
        }

        assertEquals(0, BatchRename.execute(plan))
        assertTrue(plan.discardedSources.isEmpty())
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertEquals("retained", retained.readText())
        assertEquals("old", occupied.readText())
    }
}

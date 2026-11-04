package com.subtitleedit.util

import com.subtitleedit.util.ArchiveManager.ConflictPolicy
import com.subtitleedit.util.ArchiveManager.ConflictResolution
import com.subtitleedit.util.ArchiveManager.DestinationConflict
import com.subtitleedit.util.ArchiveManager.DestinationConflictException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchRenameConflictResolverTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun existingDestinationReportsMetadataAndRenameReservesLaterRequestedNames() = runBlocking {
        val first = file("first.txt", "one")
        val second = file("second.txt", "two")
        val target = file("same.txt", "existing")
        val later = File(temporaryFolder.root, "same (1).txt")
        var conflict: DestinationConflict? = null

        val plan = BatchRenameConflictResolver.resolve(listOf(
            entry(first, target), entry(second, later)
        )) {
            conflict = it
            ConflictResolution(ConflictPolicy.RENAME)
        }

        assertEquals(listOf("same (2).txt", "same (1).txt"), plan.entries.map { it.target.name })
        assertEquals("same.txt", conflict?.entryName)
        assertEquals(3L, conflict?.sourceSize)
        assertEquals(8L, conflict?.existingSize)
        assertFalse(requireNotNull(conflict).archiveInternal)
        assertEquals("existing", target.readText())
        assertEquals(listOf("first.txt", "same.txt", "second.txt"), children())
    }

    @Test
    fun duplicateOutputsOfferAConflictAndKeepTheEarlierResultWhenSkipping() = runBlocking {
        val first = file("a.txt", "a")
        val second = file("b.txt", "bb")
        val target = File(temporaryFolder.root, "same.txt")
        val seen = mutableListOf<DestinationConflict>()

        val plan = BatchRenameConflictResolver.resolve(listOf(entry(first, target), entry(second, target))) {
            seen += it
            ConflictResolution(ConflictPolicy.SKIP)
        }

        assertEquals(listOf(entry(first, target)), plan.entries)
        assertEquals(1, seen.size)
        assertEquals(1L, seen.single().existingSize)
        assertTrue(plan.discardedSources.isEmpty())
        assertEquals(listOf("a.txt", "b.txt"), children())
    }

    @Test
    fun laterOverwriteReplacesEarlierResultOnlyInThePlanAndPreservesItUntilCommit() = runBlocking {
        val sources = listOf(file("a.txt", "a"), file("b.txt", "b"), file("c.txt", "c"))
        val target = File(temporaryFolder.root, "same.txt")
        var prompts = 0

        val plan = BatchRenameConflictResolver.resolve(sources.map { entry(it, target) }) {
            prompts++
            ConflictResolution(ConflictPolicy.OVERWRITE, applyToAll = true)
        }

        assertEquals(1, prompts)
        assertEquals(listOf(entry(sources.last(), target)), plan.entries)
        assertEquals(sources.take(2), plan.discardedSources)
        assertTrue(plan.overwritten.isEmpty())
        sources.forEach { assertTrue(it.exists()) }
        assertFalse(target.exists())
    }

    @Test
    fun externalOverwriteIsExplicitAndOnlyBacksUpEachDestinationOnce() = runBlocking {
        val first = file("a.txt", "a")
        val second = file("b.txt", "b")
        val target = file("same.txt", "old")

        val plan = BatchRenameConflictResolver.resolve(listOf(entry(first, target), entry(second, target))) {
            ConflictResolution(ConflictPolicy.OVERWRITE)
        }

        assertEquals(listOf(entry(second, target)), plan.entries)
        assertEquals(listOf(target), plan.overwritten)
        assertEquals(listOf(first), plan.discardedSources)
        assertEquals("old", target.readText())
    }

    @Test
    fun applyToAllDoesNotLeakIntoTheNextRenameOperation() = runBlocking {
        val sources = listOf(file("a.txt", "a"), file("b.txt", "b"))
        val targets = listOf(file("x.txt", "x"), file("y.txt", "y"))
        val entries = sources.zip(targets).map { (source, target) -> entry(source, target) }
        var firstPrompts = 0
        val skipped = BatchRenameConflictResolver.resolve(entries) {
            firstPrompts++
            ConflictResolution(ConflictPolicy.SKIP, applyToAll = true)
        }
        var secondPrompts = 0
        val renamed = BatchRenameConflictResolver.resolve(entries) {
            secondPrompts++
            ConflictResolution(ConflictPolicy.RENAME)
        }

        assertEquals(1, firstPrompts)
        assertTrue(skipped.entries.isEmpty())
        assertEquals(2, secondPrompts)
        assertEquals(listOf("x (1).txt", "y (1).txt"), renamed.entries.map { it.target.name })
    }

    @Test
    fun swapsCyclesAndCaseOnlyChangesDoNotNeedAConflictDecision() = runBlocking {
        val sources = listOf(file("a.txt", "a"), file("b.txt", "b"), file("c.txt", "c"))
        val cycle = sources.mapIndexed { index, source -> entry(source, sources[(index + 1) % sources.size]) }
        val result = BatchRenameConflictResolver.resolve(cycle) { error("Cycle paths will be vacated") }
        val caseOnly = entry(sources.first(), File(temporaryFolder.root, "A.txt"))
        val renamed = BatchRenameConflictResolver.resolve(listOf(caseOnly)) { error("Same file") }

        assertEquals(cycle, result.entries)
        assertEquals(listOf(caseOnly), renamed.entries)
        assertTrue(result.overwritten.isEmpty())
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), children())
    }

    @Test
    fun unchangedSelectedSourcesAreConflictsAndCannotBeOverwritten() {
        val first = file("a.txt", "a")
        val retained = file("b.txt", "b")
        val entries = listOf(entry(first, retained), entry(retained, retained))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                BatchRenameConflictResolver.resolve(entries) { ConflictResolution(ConflictPolicy.OVERWRITE) }
            }
        }
        assertEquals("a", first.readText())
        assertEquals("b", retained.readText())
    }

    @Test
    fun skippingALaterSourceOffersAnotherConflictForTheEarlierDependentTarget() = runBlocking {
        val first = file("a.txt", "a")
        val second = file("b.txt", "b")
        val external = file("occupied.txt", "old")
        val entries = listOf(entry(first, second), entry(second, external))

        val promptedNames = mutableListOf<String>()
        val plan = BatchRenameConflictResolver.resolve(entries) { conflict ->
            promptedNames += conflict.entryName
            ConflictResolution(ConflictPolicy.SKIP)
        }

        assertEquals(listOf("occupied.txt", "b.txt"), promptedNames)
        assertTrue(plan.entries.isEmpty())
        assertEquals(listOf("a.txt", "b.txt", "occupied.txt"), children())
        assertEquals("b", second.readText())
    }

    @Test
    fun cancellationAndFailPolicyLeaveEveryFileUntouched() {
        val first = file("a.txt", "a")
        val target = file("existing.txt", "old")
        val entries = listOf(entry(first, target))

        assertThrows(CancellationException::class.java) {
            runBlocking { BatchRenameConflictResolver.resolve(entries) { throw CancellationException("cancel") } }
        }
        assertThrows(DestinationConflictException::class.java) {
            runBlocking { BatchRenameConflictResolver.resolve(entries) { ConflictResolution(ConflictPolicy.FAIL) } }
        }

        assertEquals("a", first.readText())
        assertEquals("old", target.readText())
        assertEquals(listOf("a.txt", "existing.txt"), children())
    }

    @Test
    fun hiddenFileAndDirectoryRenamesRetainTheirWholeBaseNames() = runBlocking {
        val source = file("source.txt", "a")
        val hidden = file(".txt", "old")
        val directory = temporaryFolder.newFolder("source-dir")
        val targetDirectory = temporaryFolder.newFolder("folder.with.dots")

        val plan = BatchRenameConflictResolver.resolve(listOf(entry(source, hidden), entry(directory, targetDirectory))) {
            ConflictResolution(ConflictPolicy.RENAME)
        }

        assertEquals(listOf(".txt (1)", "folder.with.dots (1)"), plan.entries.map { it.target.name })
        assertTrue(plan.overwritten.isEmpty())
    }

    @Test
    fun overwritingAnAncestorOfAnotherSelectedSourceIsRejectedWithoutMoving() {
        val source = temporaryFolder.newFolder("source")
        val target = temporaryFolder.newFolder("target")
        val nested = File(target, "nested.txt").apply { writeText("nested") }
        val entries = listOf(entry(source, target), entry(nested, File(target, "renamed.txt")))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { BatchRenameConflictResolver.resolve(entries) { ConflictResolution(ConflictPolicy.OVERWRITE) } }
        }
        assertEquals("nested", nested.readText())
        assertTrue(source.isDirectory)
    }

    private fun file(name: String, content: String): File = temporaryFolder.newFile(name).apply { writeText(content) }
    private fun entry(source: File, target: File) = BatchRename.Entry(source, target)
    private fun children(): List<String> = temporaryFolder.root.list().orEmpty().sorted()
}

package com.subtitleedit.util

import java.io.File
import com.subtitleedit.util.ArchiveManager.ConflictPolicy
import com.subtitleedit.util.ArchiveManager.ConflictResolution
import com.subtitleedit.util.ArchiveManager.DestinationConflictException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTransferManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun rejectsCopyIntoSourceOrDescendantBeforeCreatingAnything() = runBlocking {
        val source = temporaryFolder.newFolder("source")
        File(source, "subtitle.srt").writeText("original")
        val child = File(source, "child").apply { mkdir() }
        for (destination in listOf(source, child, File(child, "../child"))) {
            val error = runCatching { FileTransferManager.copy(listOf(source), destination) { _, _, _ -> } }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(child.listFiles().orEmpty().isEmpty())
            assertEquals("original", File(source, "subtitle.srt").readText())
        }
    }

    @Test fun validatesAllSourcesBeforeCopyingFirstFile() = runBlocking {
        val source = temporaryFolder.newFolder("source")
        val child = File(source, "child").apply { mkdir() }
        val other = temporaryFolder.newFile("other.txt").apply { writeText("untouched") }
        assertTrue(runCatching {
            FileTransferManager.copy(listOf(other, source), child) { _, _, _ -> }
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(child.listFiles().orEmpty().isEmpty())
    }

    @Test fun allowsSiblingWithSamePrefixAndPreservesExistingCopy() = runBlocking {
        val source = temporaryFolder.newFolder("source")
        File(source, "subtitle.srt").writeText("new")
        val destination = temporaryFolder.newFolder("source-other")
        File(destination, source.name).apply { mkdir() }
        File(destination, "source/subtitle.srt").writeText("old")
        var conflicts = 0
        FileTransferManager.copy(listOf(source), destination, onConflict = {
            conflicts++
            assertEquals("source", it.entryName)
            ConflictResolution(ConflictPolicy.RENAME)
        }) { _, _, _ -> }
        assertEquals(1, conflicts)
        assertEquals("old", File(destination, "source/subtitle.srt").readText())
        assertEquals("new", File(destination, "source (1)/subtitle.srt").readText())
    }

    @Test fun movingIntoSelfOrChildUsesTheSameBoundary() {
        val source = temporaryFolder.newFolder("source")
        val child = File(source, "child").apply { mkdir() }
        for (destination in listOf(source, child)) {
            assertTrue(runCatching { FileTransferManager.move(source, destination) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(source.isDirectory)
    }

    @Test fun nameConflictRequiresADecisionInsteadOfSilentlyRenaming() = runBlocking {
        val source = temporaryFolder.newFile("subtitle.srt").apply { writeText("new") }
        val destination = temporaryFolder.newFolder("destination")
        File(destination, source.name).writeText("old")
        assertTrue(runCatching {
            FileTransferManager.copy(listOf(source), destination) { _, _, _ -> }
        }.exceptionOrNull() is DestinationConflictException)
        assertEquals("old", File(destination, source.name).readText())
        assertEquals(listOf(source.name), destination.listFiles().orEmpty().map { it.name })
    }

    @Test fun overwriteReportsExistingMetadataAndPublishesCompleteCopy() = runBlocking {
        val source = temporaryFolder.newFile("subtitle.srt").apply { writeText("new contents") }
        val destination = temporaryFolder.newFolder("destination")
        val existing = File(destination, source.name).apply { writeText("old") }
        var conflicts = 0
        FileTransferManager.copy(listOf(source), destination, onConflict = {
            conflicts++
            assertEquals(source.length(), it.sourceSize)
            assertEquals(existing.length(), it.existingSize)
            assertEquals("subtitle.srt", it.entryName)
            ConflictResolution(ConflictPolicy.OVERWRITE)
        }) { _, _, _ -> }
        assertEquals(1, conflicts)
        assertEquals("new contents", existing.readText())
        assertEquals("new contents", source.readText())
        assertEquals(1, destination.listFiles().orEmpty().size)
    }

    @Test fun applyToAllSkipsLaterConflictsWithoutChangingExistingFiles() = runBlocking {
        val source = temporaryFolder.newFolder("source")
        val files = listOf("a.srt", "b.srt").map { File(source, it).apply { writeText("new") } }
        val destination = temporaryFolder.newFolder("destination")
        files.forEach { File(destination, it.name).writeText("old") }
        var conflicts = 0
        FileTransferManager.copy(files, destination, onConflict = {
            conflicts++
            ConflictResolution(ConflictPolicy.SKIP, applyToAll = true)
        }) { _, _, _ -> }
        assertEquals(1, conflicts)
        files.forEach { assertEquals("old", File(destination, it.name).readText()) }
        assertEquals(2, destination.listFiles().orEmpty().size)
    }

    @Test fun overwriteDirectoryMergesContentsAndAsksAboutNestedFileConflict() = runBlocking {
        val source = temporaryFolder.newFolder("folder")
        File(source, "shared.srt").writeText("new")
        File(source, "new.srt").writeText("added")
        val destination = temporaryFolder.newFolder("destination")
        val existing = File(destination, source.name).apply { mkdir() }
        File(existing, "shared.srt").writeText("old")
        File(existing, "keep.srt").writeText("keep")
        val conflicts = mutableListOf<String>()
        FileTransferManager.copy(listOf(source), destination, onConflict = {
            conflicts += it.entryName
            ConflictResolution(if (it.sourceIsDirectory) ConflictPolicy.OVERWRITE else ConflictPolicy.SKIP)
        }) { _, _, _ -> }
        assertEquals(listOf("folder", "folder/shared.srt"), conflicts)
        assertEquals("old", File(existing, "shared.srt").readText())
        assertEquals("added", File(existing, "new.srt").readText())
        assertEquals("keep", File(existing, "keep.srt").readText())
    }

    @Test fun cancellingOverwriteKeepsOriginalAndCleansTemporaryCopy() = runBlocking {
        val source = temporaryFolder.newFile("subtitle.srt").apply { writeText("new") }
        val destination = temporaryFolder.newFolder("destination")
        File(destination, source.name).writeText("old")
        val error = runCatching {
            FileTransferManager.copy(listOf(source), destination, onConflict = {
                ConflictResolution(ConflictPolicy.OVERWRITE)
            }) { _, completed, _ -> if (completed > 0L) throw CancellationException("cancel") }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals("old", File(destination, source.name).readText())
        assertEquals(listOf(source.name), destination.listFiles().orEmpty().map { it.name })
    }

    @Test fun conflictAppearingDuringCopyAlsoRequestsDecision() = runBlocking {
        val source = temporaryFolder.newFile("subtitle.srt").apply { writeText("new") }
        val destination = temporaryFolder.newFolder("destination")
        val target = File(destination, source.name)
        var conflicts = 0
        FileTransferManager.copy(listOf(source), destination, onConflict = {
            conflicts++
            ConflictResolution(ConflictPolicy.SKIP)
        }) { _, completed, _ -> if (completed > 0L && !target.exists()) target.writeText("arrived") }
        assertEquals(1, conflicts)
        assertEquals("arrived", target.readText())
        assertEquals(1, destination.listFiles().orEmpty().size)
    }

    @Test fun copyingInSameDirectoryCanRenameButCannotOverwriteTheSource() = runBlocking {
        val directory = temporaryFolder.newFolder("directory")
        val source = File(directory, "subtitle.srt").apply { writeText("original") }
        assertTrue(runCatching {
            FileTransferManager.copy(listOf(source), directory, onConflict = {
                ConflictResolution(ConflictPolicy.OVERWRITE)
            }) { _, _, _ -> }
        }.exceptionOrNull() is IllegalArgumentException)
        FileTransferManager.copy(listOf(source), directory, onConflict = {
            ConflictResolution(ConflictPolicy.RENAME)
        }) { _, _, _ -> }
        assertEquals("original", source.readText())
        assertEquals("original", File(directory, "subtitle (1).srt").readText())
    }

    @Test fun rejectsMovingFilesAndFoldersToTheirCurrentParentIncludingCanonicalAliases() {
        val directory = temporaryFolder.newFolder("directory")
        val file = File(directory, "subtitle.srt").apply { writeText("original") }
        val folder = File(directory, "folder").apply { mkdir() }
        for (source in listOf(file, folder)) {
            for (destination in listOf(directory, File(directory, "folder/.."))) {
                assertTrue(runCatching { FileTransferManager.move(source, destination) }.exceptionOrNull() is IllegalArgumentException)
            }
        }
        assertEquals("original", file.readText())
        assertTrue(folder.isDirectory)
        assertEquals(setOf("subtitle.srt", "folder"), directory.listFiles().orEmpty().mapTo(mutableSetOf()) { it.name })
    }

    @Test fun overwriteCanReplaceDirectoryWithFileWithoutLeavingBackup() = runBlocking {
        val source = temporaryFolder.newFile("item").apply { writeText("new file") }
        val destination = temporaryFolder.newFolder("destination")
        File(destination, "item/nested").apply { mkdirs() }
        File(destination, "item/nested/old.txt").writeText("old")
        FileTransferManager.copy(listOf(source), destination, onConflict = {
            assertEquals(true, it.existingIsDirectory)
            ConflictResolution(ConflictPolicy.OVERWRITE)
        }) { _, _, _ -> }
        assertEquals("new file", File(destination, "item").readText())
        assertEquals("new file", source.readText())
        assertEquals(listOf("item"), destination.listFiles().orEmpty().map { it.name })
    }

    @Test fun overwriteCanReplaceFileWithDirectory() = runBlocking {
        val source = temporaryFolder.newFolder("item")
        File(source, "new.txt").writeText("new")
        val destination = temporaryFolder.newFolder("destination")
        File(destination, "item").writeText("old file")
        FileTransferManager.copy(listOf(source), destination, onConflict = {
            assertEquals(false, it.existingIsDirectory)
            ConflictResolution(ConflictPolicy.OVERWRITE)
        }) { _, _, _ -> }
        assertEquals("new", File(destination, "item/new.txt").readText())
        assertEquals(1, destination.listFiles().orEmpty().size)
    }
}

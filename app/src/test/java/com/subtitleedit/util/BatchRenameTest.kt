package com.subtitleedit.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchRenameTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun previewPreservesEachExtensionAndNumbersFilesAndDirectoriesInSelectionOrder() {
        val subtitle = temporaryFolder.newFile("episode.srt")
        val directory = temporaryFolder.newFolder("season.one")
        val audio = temporaryFolder.newFile("recording.FLAC")

        val entries = BatchRename.preview(
            listOf(subtitle, directory, audio),
            BatchRename.Options(numberWidth = 4, startNumber = 9)
        )

        assertEquals(listOf("episode(0009).srt", "season.one(0010)", "recording(0011).FLAC"), entries.map { it.target.name })
        assertEquals(listOf(subtitle, directory, audio), entries.map { it.source })
        assertEquals(listOf("episode.srt", "recording.FLAC", "season.one"), children())
    }

    @Test
    fun extensionCanBeReplacedOrRemovedWithoutAddingAnExtensionToDirectories() {
        val file = temporaryFolder.newFile("sample.txt")
        val directory = temporaryFolder.newFolder("folder")
        val options = BatchRename.Options(pattern = BatchRename.FILE_NAME_TOKEN)

        assertEquals(listOf("sample.srt", "folder"), BatchRename.preview(listOf(file, directory), options.copy(extension = "srt")).map { it.target.name })
        assertEquals("sample", BatchRename.preview(listOf(file), options.copy(extension = "")).single().target.name)
    }

    @Test
    fun hiddenFileNamesAndTokenCharactersInOriginalNamesAreLiteral() {
        val hidden = temporaryFolder.newFile(".hidden")
        val literal = temporaryFolder.newFile("original${BatchRename.NUMBER_TOKEN}.txt")

        val preview = BatchRename.preview(listOf(hidden, literal), BatchRename.Options())

        assertEquals(".hidden(1)", preview[0].target.name)
        assertEquals("original${BatchRename.NUMBER_TOKEN}(2).txt", preview[1].target.name)
        assertEquals("", BatchRename.sourceExtension(hidden))
        assertEquals("txt", BatchRename.sourceExtension(literal))
    }

    @Test
    fun emptyOrErasedBaseNamesAreRejectedEvenWhenDifferentExtensionsPreventDuplicates() {
        val first = temporaryFolder.newFile("first.srt")
        val second = temporaryFolder.newFile("second.txt")

        assertThrows(IllegalArgumentException::class.java) {
            BatchRename.preview(listOf(first, second), BatchRename.Options(pattern = ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchRename.preview(listOf(first), BatchRename.Options(
                pattern = BatchRename.FILE_NAME_TOKEN,
                find = "first",
                replacement = ""
            ))
        }
        assertEquals(listOf("first.srt", "second.txt"), children())
    }

    @Test
    fun literalReplacementDoesNotInterpretRegexCharactersOrReplacementGroups() {
        val file = temporaryFolder.newFile("a[1].srt")
        val result = BatchRename.preview(listOf(file), BatchRename.Options(
            pattern = BatchRename.FILE_NAME_TOKEN,
            find = "[1]",
            replacement = "\$1"
        ))

        assertEquals("a\$1.srt", result.single().target.name)
    }

    @Test
    fun regexReplacementSupportsCaptureGroupsOnExpandedNames() {
        val file = temporaryFolder.newFile("sample-07.srt")
        val result = BatchRename.preview(listOf(file), BatchRename.Options(
            pattern = BatchRename.FILE_NAME_TOKEN,
            find = "(.+)-([0-9]+)",
            replacement = "\$2-\$1",
            regexEnabled = true
        ))

        assertEquals("07-sample.srt", result.single().target.name)
    }

    @Test
    fun invalidRulesAndDuplicateTargetsAreRejectedBeforeAnythingChanges() {
        val first = temporaryFolder.newFile("first.srt").apply { writeText("first") }
        val second = temporaryFolder.newFile("second.srt").apply { writeText("second") }
        val invalidOptions = listOf(
            BatchRename.Options(numberWidth = 0),
            BatchRename.Options(numberWidth = 19),
            BatchRename.Options(startNumber = -1),
            BatchRename.Options(startNumber = Long.MAX_VALUE),
            BatchRename.Options(pattern = ""),
            BatchRename.Options(pattern = "bad/name"),
            BatchRename.Options(pattern = "bad\u0000name"),
            BatchRename.Options(extension = "bad/name"),
            BatchRename.Options(find = "[", regexEnabled = true),
            BatchRename.Options(find = "(.)", replacement = "\$2", regexEnabled = true),
            BatchRename.Options(pattern = "same-name")
        )

        invalidOptions.forEach { options ->
            assertThrows(IllegalArgumentException::class.java) { BatchRename.preview(listOf(first, second), options) }
        }

        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertEquals(listOf("first.srt", "second.srt"), children())
    }

    @Test
    fun previewDoesNotRequireSourcesOrTargetsToExistAndAllowsMatchingNamesInDifferentParents() {
        val first = temporaryFolder.newFolder("one")
        val second = temporaryFolder.newFolder("two")
        val entries = BatchRename.preview(
            listOf(File(first, "missing.srt"), File(second, "missing.srt")),
            BatchRename.Options(pattern = BatchRename.FILE_NAME_TOKEN)
        )

        assertEquals(2, entries.size)
        assertFalse(entries.first().source.exists())
    }

    @Test
    fun executeRenamesFilesAndDirectoriesAndLeavesUnchangedEntriesAlone() {
        val file = temporaryFolder.newFile("old.txt").apply { writeText("content") }
        val directory = temporaryFolder.newFolder("folder")
        File(directory, "child.txt").writeText("inside")
        val unchanged = temporaryFolder.newFile("same.txt")

        val count = BatchRename.execute(listOf(
            BatchRename.Entry(file, File(file.parentFile, "new.txt")),
            BatchRename.Entry(directory, File(directory.parentFile, "renamed-folder")),
            BatchRename.Entry(unchanged, unchanged)
        ))

        assertEquals(2, count)
        assertEquals("content", File(temporaryFolder.root, "new.txt").readText())
        assertEquals("inside", File(temporaryFolder.root, "renamed-folder/child.txt").readText())
        assertEquals(listOf("new.txt", "renamed-folder", "same.txt"), children())
    }

    @Test
    fun executeDoesNotOverwriteAnUnselectedTargetOrChangeOtherSourcesBeforeValidation() {
        val first = temporaryFolder.newFile("first.txt").apply { writeText("one") }
        val second = temporaryFolder.newFile("second.txt").apply { writeText("two") }
        val occupied = temporaryFolder.newFile("occupied.txt").apply { writeText("unselected") }

        assertThrows(IllegalArgumentException::class.java) {
            BatchRename.execute(listOf(
                BatchRename.Entry(first, File(first.parentFile, "available.txt")),
                BatchRename.Entry(second, occupied)
            ))
        }

        assertEquals("one", first.readText())
        assertEquals("two", second.readText())
        assertEquals("unselected", occupied.readText())
        assertEquals(listOf("first.txt", "occupied.txt", "second.txt"), children())
    }

    @Test
    fun executeAllowsSwappingNamesAndChangingOnlyLetterCase() {
        val first = temporaryFolder.newFile("first.txt").apply { writeText("one") }
        val second = temporaryFolder.newFile("second.txt").apply { writeText("two") }

        assertEquals(2, BatchRename.execute(listOf(BatchRename.Entry(first, second), BatchRename.Entry(second, first))))
        assertEquals("two", first.readText())
        assertEquals("one", second.readText())
        assertEquals(1, BatchRename.execute(listOf(BatchRename.Entry(first, File(first.parentFile, "FIRST.txt")))))
        assertEquals("two", File(first.parentFile, "FIRST.txt").readText())
        assertEquals(listOf("FIRST.txt", "second.txt"), children())
    }

    @Test
    fun ancestorSelectionsAndCrossDirectoryTargetsAreRejectedWithoutMovingAnything() {
        val directory = temporaryFolder.newFolder("directory")
        val child = File(directory, "child.txt").apply { writeText("child") }
        val elsewhere = temporaryFolder.newFolder("elsewhere")

        assertThrows(IllegalArgumentException::class.java) {
            BatchRename.execute(listOf(
                BatchRename.Entry(directory, File(directory.parentFile, "new-directory")),
                BatchRename.Entry(child, File(directory, "new-child.txt"))
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchRename.execute(listOf(BatchRename.Entry(child, File(elsewhere, "child.txt"))))
        }

        assertEquals("child", child.readText())
        assertEquals(listOf("directory", "elsewhere"), children())
    }

    @Test
    fun stageFailureRestoresAlreadyStagedSources() {
        assertRollbackAfterFailure(failureAtMove = 2)
    }

    @Test
    fun targetFailureRestoresANameCycleAfterOneTargetHasAlreadyCommitted() {
        assertRollbackAfterFailure(failureAtMove = 5)
    }

    @Test
    fun rollbackFailureReportsTheRemainingLocationWithoutDeletingTheFile() {
        val first = temporaryFolder.newFile("first.txt").apply { writeText("one") }
        val second = temporaryFolder.newFile("second.txt").apply { writeText("two") }
        var moves = 0

        val error = assertThrows(IOException::class.java) {
            BatchRename.execute(listOf(
                BatchRename.Entry(first, File(first.parentFile, "new-first.txt")),
                BatchRename.Entry(second, File(second.parentFile, "new-second.txt"))
            )) { source, target ->
                moves++
                if (moves == 2 || moves == 3) throw IOException("simulated failure")
                Files.move(source.toPath(), target.toPath())
            }
        }

        assertTrue(error.message.orEmpty().contains("部分项目未能恢复"))
        val stranded = requireNotNull(temporaryFolder.root.listFiles()).single { it.name.startsWith(".subtitleedit-rename-") }
        assertTrue(error.message.orEmpty().contains(stranded.absolutePath))
        assertEquals("one", stranded.readText())
        assertEquals("two", second.readText())
    }

    private fun assertRollbackAfterFailure(failureAtMove: Int) {
        val files = listOf("a.txt", "b.txt", "c.txt").map { name ->
            temporaryFolder.newFile(name).apply { writeText(name) }
        }
        val entries = files.mapIndexed { index, source -> BatchRename.Entry(source, files[(index + 1) % files.size]) }
        var moves = 0

        val error = assertThrows(IOException::class.java) {
            BatchRename.execute(entries) { source, target ->
                moves++
                if (moves == failureAtMove) throw IOException("simulated failure")
                Files.move(source.toPath(), target.toPath())
            }
        }

        assertTrue(error.message.orEmpty().contains("已恢复原名称"))
        files.forEach { assertEquals(it.name, it.readText()) }
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), children())
    }

    private fun children(): List<String> = temporaryFolder.root.list().orEmpty().sorted()
}

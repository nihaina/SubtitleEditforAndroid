package com.subtitleedit.util

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDeletionCancellationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cancellingDuringPreparationLeavesAllFilesUntouched() {
        val source = temporaryFolder.newFile("keep.txt").apply { writeText("keep") }
        val task = Job()
        assertCancelled {
            runBlocking(task) {
                FileDeletionManager.delete(listOf(source)) { _, completed, _ ->
                    if (completed == 0L) task.cancel()
                }
            }
        }
        assertTrue(source.exists())
    }

    @Test
    fun cancellingWhileCountingDirectoryStopsBeforeDeletion() {
        val root = temporaryFolder.newFolder("counting")
        val child = File(root, "keep.txt").apply { writeText("keep") }
        val task = Job()
        val source = object : File(root.absolutePath) {
            override fun listFiles(): Array<File> {
                task.cancel()
                return arrayOf(child)
            }
        }
        assertCancelled {
            runBlocking(task) { FileDeletionManager.delete(listOf(source)) { _, _, _ -> } }
        }
        assertTrue(child.exists())
        assertTrue(root.exists())
    }

    @Test
    fun cancellingAfterOneFileKeepsTheRemainingFilesAndParent() {
        val root = temporaryFolder.newFolder("partial")
        val task = Job()
        val first = cancelAfterDeleting(File(root, "first.txt"), task)
        first.writeText("first")
        val remaining = File(root, "remaining.txt").apply { writeText("remaining") }
        val source = directoryWithChildren(root, arrayOf(first, remaining))

        assertCancelled {
            runBlocking(task) { FileDeletionManager.delete(listOf(source)) { _, _, _ -> } }
        }

        assertFalse(first.exists())
        assertTrue(remaining.exists())
        assertTrue(root.exists())
    }

    @Test
    fun cancellingWhileDeletingLastChildDoesNotDeleteItsParent() {
        val root = temporaryFolder.newFolder("parent")
        val task = Job()
        val last = cancelAfterDeleting(File(root, "last.txt"), task)
        last.writeText("last")
        val source = directoryWithChildren(root, arrayOf(last))

        assertCancelled {
            runBlocking(task) { FileDeletionManager.delete(listOf(source)) { _, _, _ -> } }
        }

        assertFalse(last.exists())
        assertTrue("the parent deletion must honor cancellation", root.exists())
    }

    private fun cancelAfterDeleting(file: File, task: Job): File = object : File(file.absolutePath) {
        override fun delete(): Boolean = super.delete().also { if (it) task.cancel() }
    }

    private fun directoryWithChildren(directory: File, children: Array<File>): File =
        object : File(directory.absolutePath) {
            override fun listFiles(): Array<File> = children
        }

    private fun assertCancelled(action: () -> Unit) {
        try {
            action()
            fail("expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation is the operation's result; already deleted files are not recreated.
        }
    }
}

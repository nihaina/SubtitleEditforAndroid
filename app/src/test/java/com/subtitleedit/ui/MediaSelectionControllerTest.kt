package com.subtitleedit.ui

import com.subtitleedit.model.BrowserFileMetadata
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaSelectionControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyMediaFilesCanEnterSelection() {
        val audio = temporary.newFile("one.wav")
        val video = temporary.newFile("two.mp4")
        val subtitle = temporary.newFile("three.srt")
        val folder = temporary.newFolder("folder.mp3")
        val selection = MediaSelectionController()
        listOf(audio, video, subtitle, folder).forEach { selection.select(it.path) }
        assertEquals(listOf(audio.path, video.path), selection.selectedPaths)
        selection.cancel()
        selection.updateVisiblePaths(listOf(audio, video, subtitle, folder).map { it.path })
        selection.selectVisible(MediaLibrarySelectionAction.SELECT_ALL)
        assertEquals(listOf(audio.path, video.path), selection.selectedPaths)
    }

    @Test fun directoryScopeUsesThePublishedMetadataInsteadOfCheckingFilesAgain() = runBlocking {
        val audio = File(temporary.root, "snapshot.wav")
        val folder = File(temporary.root, "folder.mp3")
        val subtitle = File(temporary.root, "subtitle.srt")
        val fallback = temporary.newFile("fallback.mp4")
        val metadata = listOf(
            BrowserFileMetadata(audio, isDirectory = false, size = 0L, modifiedTimeMillis = 0L),
            BrowserFileMetadata(folder, isDirectory = true, size = 0L, modifiedTimeMillis = 0L),
            BrowserFileMetadata(subtitle, isDirectory = false, size = 0L, modifiedTimeMillis = 0L)
        ).associateBy { it.path }
        val selection = MediaSelectionController()

        selection.refreshVisiblePaths(listOf(audio, folder, subtitle, fallback, audio).map { it.path }, metadata)
        selection.selectVisible(MediaLibrarySelectionAction.SELECT_ALL)

        assertFalse(audio.exists())
        assertEquals(listOf(audio.path, fallback.path), selection.selectedPaths)
    }

    @Test fun aPendingPageScopeCannotSelectThePreviousPagesFilesOrOverwriteANewerScope() = runBlocking {
        val previous = temporary.newFile("previous.wav").path
        val pending = temporary.newFile("pending.wav").path
        val latest = temporary.newFile("latest.wav").path
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { worker ->
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            executor.submit { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val selection = MediaSelectionController(worker)
            selection.updateVisiblePaths(listOf(previous))
            selection.select(previous)
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) { selection.refreshVisiblePaths(listOf(pending)) }
            try {
                selection.selectVisible(MediaLibrarySelectionAction.INVERT)
                assertEquals("The previous page is no longer a visible selection scope", listOf(previous), selection.selectedPaths)
                selection.updateVisiblePaths(listOf(latest))
            } finally { release.countDown() }
            refresh.join()
            selection.selectVisible(MediaLibrarySelectionAction.SELECT_ALL)
            assertEquals(listOf(previous, latest), selection.selectedPaths)
        }
    }

    @Test fun changingPagesAndInvertingVisibleFilesRetainsSelectionsElsewhere() {
        val files = (1..4).map { temporary.newFile("$it.wav").path }
        val selection = MediaSelectionController()
        selection.updateVisiblePaths(files.take(2))
        selection.select(files[0])
        selection.updateVisiblePaths(files.drop(2))
        selection.select(files[2])
        selection.selectVisible(MediaLibrarySelectionAction.INVERT)
        assertEquals(listOf(files[0], files[3]), selection.selectedPaths)
        selection.updateVisiblePaths(files)
        selection.selectVisible(MediaLibrarySelectionAction.SELECT_RANGE)
        assertEquals(files.toSet(), selection.selectedPaths.toSet())
    }

    @Test fun zeroSelectedStillAllowsCrossPageSelectionUntilCancelled() {
        val path = temporary.newFile("one.wav").path
        val selection = MediaSelectionController()
        selection.select(path)
        selection.toggle(path)
        assertTrue(selection.active)
        assertTrue(selection.selectedPaths.isEmpty())
        selection.cancel()
        assertFalse(selection.active)
    }

    @Test fun aForegroundQueueKeepsItsSelectionScopeWhenThePageBehindItRefreshes() {
        val files = (1..3).map { temporary.newFile("$it.wav").path }
        val selection = MediaSelectionController()
        selection.updateVisiblePaths(listOf(files[0]))
        selection.setOverlayPaths(listOf(files[1], files[1]))
        selection.updateVisiblePaths(listOf(files[2]))
        selection.selectVisible(MediaLibrarySelectionAction.SELECT_ALL)
        assertEquals(listOf(files[1]), selection.selectedPaths)
        selection.setOverlayPaths(null)
        selection.selectVisible(MediaLibrarySelectionAction.SELECT_ALL)
        assertEquals(listOf(files[1], files[2]), selection.selectedPaths)
    }

    @Test fun finishingActionsHonorsTheSwitchWithoutClearingNewerSelections() {
        val first = temporary.newFile("one.wav").path
        val second = temporary.newFile("two.wav").path
        val selection = MediaSelectionController()
        assertTrue(selection.closeAfterAction)
        selection.closeAfterAction = false
        selection.select(first)
        selection.finishAction(listOf(first))
        assertEquals(listOf(first), selection.selectedPaths)
        selection.closeAfterAction = true
        selection.select(second)
        selection.finishAction(listOf(first))
        assertEquals(listOf(second), selection.selectedPaths)
        assertTrue(selection.active)
        selection.finishAction(listOf(second))
        assertFalse(selection.active)
    }
}

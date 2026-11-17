package com.subtitleedit

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaLibraryFileObserver
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.FavoriteDirectoryStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainDirectorySourcesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun commonShortcutsStayOutOfLibraryUntilExplicitlyPromoted() {
        val app = compose.activity.applicationContext
        val store = FavoriteDirectoryStore(app)
        val previous = store.load()
        val root = File(app.cacheDir, "directory-sources-${System.nanoTime()}").apply { check(mkdirs()) }
        val common = File(root, "Common shortcut").apply { check(mkdir()) }
        val library = File(root, "Library source").apply { check(mkdir()) }
        val commonTrack = wave(common, "Common title")
        val libraryTrack = wave(library, "Library title")
        val historyOnlyPath = File(library, "not-indexed.wav").path
        val repository = MediaLibraryRepository.getInstance(app)
        val page = mutableIntStateOf(R.id.nav_favorites)
        val refresh = mutableIntStateOf(0)
        val state = MainTopLevelPagesState()
        try {
            // Simulate an old, incorrectly indexed common directory plus an unrelated history row.
            repository.indexTrack(commonTrack.path)
            repository.recordPlaybackProgress(commonTrack.path, "old-playback", 1_000L, 500L)
            repository.recordPlaybackProgress(historyOnlyPath, "history-only", 1_000L, 500L)
            store.replace(emptySet())
            assertTrue(store.addCommon(common))
            store.addMediaLibrary(library)
            compose.setContent {
                SubtitleEditComposeTheme {
                    Surface {
                        MainTopLevelPages(page.intValue, page.intValue, refresh.intValue, 1, state,
                            playerModeEnabled = true, viewMode = FileBrowserViewMode.DETAILED,
                            onOpenDirectory = {}, onToolbarChanged = { _, _, _ -> })
                    }
                }
            }
            compose.onNodeWithText(common.name).assertIsDisplayed()
            compose.onNodeWithText(library.name).assertIsDisplayed()
            compose.waitUntil(12_000L) {
                repository.isIndexedTrack(libraryTrack.path) && !repository.isIndexedTrack(commonTrack.path)
            }
            assertTrue(repository.getPlaybackHistory().any { it.path == commonTrack.path })
            assertTrue(repository.getPlaybackHistory().any { it.path == historyOnlyPath })
            assertEquals("Library title", repository.getStoredTrack(libraryTrack.path)?.title)

            compose.runOnIdle { page.intValue = R.id.nav_library }
            compose.onNodeWithText(library.name).assertIsDisplayed()
            compose.onNodeWithText(common.name).assertDoesNotExist()
            compose.waitUntil(5_000L) { state.mediaLibraryPage.loadedQuery != null }
            compose.runOnIdle {
                assertEquals(listOf(library), state.mediaLibraryDirectories)
                assertEquals(listOf(libraryTrack.path), state.mediaLibraryPage.content.allTracks.map { it.path })
            }

            // The same path may be added again from the library entry, upgrading its source.
            store.addMediaLibrary(common)
            compose.runOnIdle { refresh.intValue++ }
            compose.waitUntil(12_000L) { repository.isIndexedTrack(commonTrack.path) }
            compose.onNodeWithText(common.name).assertIsDisplayed()
            assertEquals("Common title", repository.getStoredTrack(commonTrack.path)?.title)
            compose.runOnIdle { page.intValue = R.id.nav_favorites }
            compose.onNodeWithText(common.name).assertIsDisplayed()
            compose.onNodeWithText(library.name).assertIsDisplayed()
            assertEquals(1, compose.onAllNodesWithText(common.name).fetchSemanticsNodes().size)
            assertTrue(store.load().commonDirectories.isEmpty())
            assertEquals(setOf(common.path, library.path), store.load().mediaLibraryPaths)
        } finally {
            store.replace(previous.paths, previous.mediaLibraryPaths)
            MediaLibraryFileObserver.syncRoots(app, previous.mediaLibraryDirectories)
            runBlocking { MediaLibraryFileObserver.reconcile(app, emptyList()) }
            repository.removeDirectoryFromLibrary(root, previous.mediaLibraryDirectories)
            repository.removePlaybackHistory(commonTrack.path)
            repository.removePlaybackHistory(historyOnlyPath)
            root.walkTopDown().filter { it.isFile }.forEach { PlayerTrackMetadataReader.invalidate(it.path) }
            root.deleteRecursively()
        }
    }

    private fun wave(directory: File, title: String): File = File(directory, "song.wav").apply {
        val count = 8_000
        writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(count); putInt(count * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array())
        MediaTagAudioRepository.write(this, MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, title))
    }
}

package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Global media selection is independent of whichever collection is currently visible. */
@RunWith(AndroidJUnit4::class)
class MediaSelectionPagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var directory: File? = null
    private var repository: MediaLibraryRepository? = null
    private var database: String? = null

    @After fun cleanup() {
        runBlocking { com.subtitleedit.player.PlayerLibraryWriter.execute {} }
        repository?.close()
        database?.let { compose.activity.deleteDatabase(it) }
        directory?.deleteRecursively()
    }

    @Test fun categoryFavoritesAndFolderFilesShareSelectionWithoutStartingPlayback() {
        val root = fixtureRoot()
        val tracks = (1..4).map { index -> MediaLibraryTrack(media(root, "$index.m4a").path, "Track $index") }
        val entries = tracks.drop(1).filterIndexed { index, _ -> index % 2 == 0 }
            .map { MediaFolderEntry(it.path, it.file.name, false, 1, 1_000L) } + listOf(
                MediaFolderEntry(File(root, "child").apply { mkdir() }.path, "child", true, 0, 0),
                MediaFolderEntry(File(root, "excluded.srt").apply { writeText("subtitle") }.path, "excluded.srt", false, 1, 0)
            )
        val selection = MediaSelectionController()
        val album = MediaAlbum("Selection album", tracks.map { MediaAlbumTrack(it) })
        val page = mutableStateOf(0)
        var playRequests = 0
        var folderRequests = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                CompositionLocalProvider(LocalMediaSelection provides selection) {
                    val holder = rememberSaveableStateHolder()
                    Surface(Modifier.fillMaxSize()) {
                        holder.SaveableStateProvider(page.value) {
                            when (page.value) {
                                0 -> MediaAlbumPageContent(
                                    albums = listOf(album),
                                    options = MediaAlbumViewOptions(viewMode = AlbumViewMode.LIST_NO_ART, groupTracks = false),
                                    onOptionsChanged = {},
                                    isActive = true,
                                    onPlay = { _, _ -> playRequests++ },
                                    onShuffle = { playRequests++ },
                                    onFavoriteSelection = { _, _ -> },
                                    onAddSelectionToPlaylist = { _, _ -> },
                                    onShowInfo = {}
                                )
                                1 -> FavoritesTabContent(
                                    tracks = listOf(tracks[0], tracks[2]), onPlay = { playRequests++ }, onPlayAll = {},
                                    onToggleFavorite = {}, onAddToPlaylist = {}, onRemoveFavorites = {},
                                    onAddSelectionToPlaylist = {}, onShowSelectionInfo = {}
                                )
                                else -> FilesTabContent(
                                    roots = listOf(root), rootPath = root.path, currentPath = root.path,
                                    entries = entries, rootMediaCounts = emptyMap(), selectedPaths = emptySet(), selectedDurationMs = 0,
                                    onOpenRoot = {}, onOpenFolder = { folderRequests++ }, onPlayFile = { playRequests++ },
                                    onLongPressFolder = {}, onLongPressFile = {}, onToggleSelection = {}, onClearSelection = {},
                                    onAdvancedSelection = {}, onFavoriteSelection = {}, onAddSelectionToPlaylist = {}, onShowInfo = {}
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("album-search").performTextReplacement("Selection")
        compose.onNodeWithTag("album-item:${album.key}").performClick()
        compose.onNodeWithTag("album-track:${tracks[0].path}").performTouchInput { longClick() }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.runOnIdle { page.value = 1 }
        compose.onNodeWithTag("library-favorite:${tracks[0].path}").assertIsSelected()
        compose.onNodeWithTag("library-favorite:${tracks[2].path}").performClick().assertIsSelected()
        compose.runOnIdle { page.value = 2 }
        compose.onNodeWithTag("library-file:${File(root, "child").path}").performClick()
        compose.runOnIdle { selection.selectVisible(MediaLibrarySelectionAction.SELECT_ALL) }
        compose.runOnIdle {
            assertEquals(tracks.map { it.path }.toSet(), selection.selectedPaths.toSet())
            assertEquals(1, folderRequests)
            assertEquals(0, playRequests)
            assertFalse(selection.contains(File(root, "excluded.srt").path))
        }
        compose.runOnIdle { page.value = 0 }
        compose.onNodeWithTag("album-track:${tracks[0].path}").assertIsSelected()
        compose.onNodeWithTag("album-tracks").performScrollToNode(hasTestTag("album-track:${tracks[2].path}"))
        compose.onNodeWithTag("album-track:${tracks[2].path}").assertIsSelected()
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.onNodeWithTag("album-detail-back").performClick()
        compose.onNodeWithTag("album-search").assertTextEquals("Selection")
        compose.runOnIdle {
            selection.selectVisible(MediaLibrarySelectionAction.INVERT)
            assertEquals(tracks.map { it.path }.toSet(), selection.selectedPaths.toSet())
        }
    }

    @Test fun returningToLibraryRetainsItsNestedFolderScrollAndPreparedSnapshot() {
        val root = fixtureRoot()
        val folder = File(root, "nested").apply { mkdir() }
        val files = (1..20).map { media(folder, "track-${it.toString().padStart(2, '0')}.m4a") }
        val db = "media-selection-retention-${System.nanoTime()}.db".also { database = it }
        val repo = MediaLibraryRepository.open(compose.activity, db) { metadata, duration ->
            MediaLibraryTrack(metadata.path, metadata.file.name, durationMs = duration)
        }.also { repository = it }
        files.forEach { repo.indexTrack(it.path) }
        val showing = mutableStateOf(true)
        val pageState = MediaLibraryPageState()
        var title = ""
        compose.setContent {
            SubtitleEditComposeTheme {
                val holder = rememberSaveableStateHolder()
                Surface(Modifier.fillMaxSize()) {
                    if (showing.value) holder.SaveableStateProvider("library") {
                        MediaLibraryScreen(0, 1, { value, _, _ -> title = value }, mediaLibraryDirectories = listOf(root),
                            pageState = pageState, libraryRepository = repo)
                    } else Text("Another page")
                }
            }
        }
        compose.onNodeWithText(root.name).performClick()
        compose.waitUntil(5_000L) { compose.onAllNodes(hasTestTag("library-file:${folder.path}")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-file:${folder.path}").performClick()
        compose.onNodeWithTag("library-files-list").performScrollToNode(hasTestTag("library-file:${files.last().path}"))
        compose.onNodeWithTag("library-file:${files.last().path}").assertIsDisplayed()
        compose.runOnIdle { showing.value = false }
        compose.runOnIdle { showing.value = true }
        compose.onNodeWithTag("library-file:${files.last().path}").assertIsDisplayed()
        compose.onNodeWithTag("library-file:${files.first().path}").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(folder.name, title)
            assertTrue(pageState.content.allTracks.size >= files.size)
        }
    }

    private fun fixtureRoot(): File = File(compose.activity.cacheDir, "media-selection-pages-${System.nanoTime()}")
        .apply { check(mkdirs()); directory = this }

    private fun media(root: File, name: String): File = File(root, name).apply {
        writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("media-tags/preservation.m4a").use { it.readBytes() })
    }
}

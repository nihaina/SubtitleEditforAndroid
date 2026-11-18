package com.subtitleedit.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.player.LocalPlayerLauncher
import com.subtitleedit.player.PlayerPlayMode
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaFolderCategoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: File? = null
    private val preferences by lazy { MediaFolderPreferences(compose.activity) }
    private lateinit var previousOptions: MediaCollectionViewOptions

    @Before fun useCoverList() {
        previousOptions = preferences.loadViewOptions()
        preferences.saveViewOptions(MediaCollectionViewOptions(AlbumViewMode.LIST_ART, AlbumGridSize.MEDIUM,
            showMoreInfo = true, quickSearch = true))
    }

    @After fun removeFixture() {
        if (::previousOptions.isInitialized) preferences.saveViewOptions(previousOptions)
        fixture?.deleteRecursively()
    }

    @Test fun overviewSettingsSwitchAllViewsAndKeepFolderPreferencesSeparate() {
        val root = File(compose.activity.cacheDir, "folders-${UUID.randomUUID()}").apply { mkdirs() }
        fixture = root
        val alpha = File(root, "Alpha").apply { mkdir() }
        val beta = File(root, "Beta").apply { mkdir() }
        val tracks = listOf(alpha, beta).map { folder -> indexed(File(folder, "track.wav").apply { createNewFile() }, 1000L) }
        val albumPreferences = MediaAlbumPreferences(compose.activity)
        val albumOptions = albumPreferences.load()
        val order = preferences.loadOrder()
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaAlbumScreen(0, listOf(root), true, catalogLoader = { tracks })
                }
            }
        }
        compose.onNodeWithTag("media-category:FOLDERS").performClick()
        waitForTag("folder-list")
        compose.onAllNodesWithTag("folder-artwork-disc", useUnmergedTree = true).fetchSemanticsNodes().let {
            assertEquals(2, it.size)
        }

        chooseView(AlbumViewMode.LIST_NO_ART)
        waitForTag("folder-list")
        compose.onNodeWithTag("folder-artwork-disc", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("folder-settings").performClick()
        settingsRow("folder-settings-grid-size").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.media_album_group_tracks)).assertDoesNotExist()
        pressBack()

        chooseView(AlbumViewMode.GRID)
        waitForTag("folder-grid")
        val alphaTile = compose.onNodeWithTag("folder-item:${alpha.path}")
        val mediumWidth = alphaTile.fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("folder-settings").performClick()
        settingsRow("folder-settings-grid-size").assertIsEnabled().performClick()
        compose.onNodeWithText(compose.activity.getString(AlbumGridSize.LARGE.labelRes)).performClick()
        compose.onNodeWithTag("folder-settings-back").performClick()
        waitForTag("folder-grid")
        assertTrue(alphaTile.fetchSemanticsNode().boundsInRoot.width > mediumWidth)

        chooseView(AlbumViewMode.LIST_ART)
        waitForTag("folder-list")
        waitForTag("folder-artwork-disc", unmerged = true)
        val betaTile = compose.onNodeWithTag("folder-item:${beta.path}")
        compose.onNodeWithTag("folder-search").performTextInput(alpha.name)
        betaTile.assertDoesNotExist()
        compose.onNodeWithTag("folder-settings").performClick()
        settingsRow("folder-settings-more-info").performClick()
        settingsRow("folder-settings-quick-search").performClick()
        compose.onNodeWithTag("folder-settings-back").performClick()
        waitForTag("folder-item:${beta.path}")
        betaTile.assertIsDisplayed()
        compose.onNodeWithTag("folder-search").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.media_lib_files_folder_count, 1)).assertDoesNotExist()

        compose.onNodeWithTag("media-category:ALBUMS").performClick()
        compose.onNodeWithTag("media-category:FOLDERS").performClick()
        waitForTag("folder-item:${beta.path}")
        compose.onNodeWithTag("folder-search").assertDoesNotExist()
        assertEquals(MediaCollectionViewOptions(AlbumViewMode.LIST_ART, AlbumGridSize.LARGE,
            showMoreInfo = false, quickSearch = false), preferences.loadViewOptions())
        assertEquals(order, preferences.loadOrder())
        assertEquals(albumOptions, albumPreferences.load())
    }

    @Test fun headerArtworkInheritsUntilAChildHasItsOwnAndRestoresOnUp() {
        val root = File(compose.activity.cacheDir, "folders-${UUID.randomUUID()}").apply { mkdirs() }
        fixture = root
        val parent = File(root, "Parent").apply { mkdir() }
        writeArtwork(File(parent, "cover.png"), Color.GREEN)
        val uncovered = File(parent, "Uncovered").apply { mkdir() }
        val unreadable = File(uncovered, "broken.png").apply { writeText("not an image") }
        val covered = File(parent, "Covered").apply { mkdir() }
        writeArtwork(File(covered, "cover.png"), Color.BLUE)
        val nested = File(covered, "Nested").apply { mkdir() }
        val subtitle = File(nested, "lyrics.srt").apply { writeText("subtitle") }
        val otherRoot = File(root, "Other").apply { mkdir() }
        val tracks = listOf(uncovered, covered, otherRoot).map { folder ->
            indexed(File(folder, "track.wav").apply { createNewFile() }, 1_000L)
        }
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaAlbumScreen(0, listOf(root), true, catalogLoader = { tracks })
                }
            }
        }
        compose.onNodeWithTag("media-category:FOLDERS").performClick()
        openFolder(parent)
        val artwork = compose.onNodeWithTag("folder-detail-artwork")
        fun headerColor(): Int = artwork.captureToImage().toPixelMap().let { pixels ->
            pixels[pixels.width / 2, pixels.height / 2].toArgb()
        }
        waitForTag("folder-detail-artwork")
        compose.waitUntil(10_000L) { headerColor() == Color.GREEN }

        openFolder(uncovered)
        waitForTag("folder-file:${unreadable.path}")
        assertEquals(Color.GREEN, headerColor())
        compose.onNodeWithTag("folder-up").performClick()

        openFolder(covered)
        compose.waitUntil(10_000L) { headerColor() == Color.BLUE }
        openFolder(nested)
        waitForTag("folder-file:${subtitle.path}")
        assertEquals(Color.BLUE, headerColor())
        compose.onNodeWithTag("folder-up").performClick()
        waitForTag("folder-item:${nested.path}")
        assertEquals(Color.BLUE, headerColor())
        compose.onNodeWithTag("folder-up").performClick()
        waitForTag("folder-item:${covered.path}")
        assertEquals(Color.GREEN, headerColor())
        compose.onNodeWithTag("folder-up").performClick()

        openFolder(otherRoot)
        compose.onNode(hasTestTag("folder-artwork-disc").and(hasAnyAncestor(hasTestTag("folder-detail-artwork"))),
            useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun folderTabDrillsIntoMixedRowsAndPlaysOnlyTheCurrentLevel() {
        val root = File(compose.activity.cacheDir, "folders-${UUID.randomUUID()}").apply { mkdirs() }
        fixture = root
        val registered = File(root, "ADM").apply { mkdir() }
        val group = File(registered, "1").apply { mkdir() }
        val playlist = File(group, "2").apply { mkdir() }
        val unrelated = File(group, "Unrelated").apply { mkdir() }
        File(unrelated, "lyrics.srt").writeText("subtitle")
        val otherMedia = File(playlist, "Nested").apply { mkdir() }
        val sidecars = File(playlist, "Sidecars").apply { mkdir() }
        val first = File(playlist, "a.wav").apply { createNewFile() }
        val second = File(playlist, "z.wav").apply { createNewFile() }
        val nestedTrack = File(otherMedia, "deep.wav").apply { createNewFile() }
        val subtitle = File(sidecars, "lyrics.srt").apply { writeText("subtitle") }
        val image = File(sidecars, "cover.png").also { writeArtwork(it, Color.GREEN) }
        val tracks = listOf(indexed(first, 2_000L), indexed(second, 4_000L), indexed(nestedTrack, 7_000L))
        val queues = CopyOnWriteArrayList<Pair<List<String>, PlayerPlayMode?>>()
        val openedSubtitles = CopyOnWriteArrayList<File>()
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlayerLauncher provides { paths, _, mode, _ -> queues.add(paths to mode) }) {
                        MediaAlbumScreen(0, listOf(registered, group), true, catalogLoader = { tracks },
                            onOpenSubtitle = { openedSubtitles += it })
                    }
                }
            }
        }

        val folders = compose.onNodeWithTag("media-category:FOLDERS")
        assertTrue(folders.fetchSemanticsNode().boundsInRoot.left >
            compose.onNodeWithTag("media-category:ALBUMS").fetchSemanticsNode().boundsInRoot.left)
        folders.performClick()
        waitForTag("folder-artwork-image", unmerged = true)
        compose.onNodeWithText(group.path, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("folder-item:${group.path}").performClick()
        waitForTag("folder-item:${playlist.path}")
        compose.onNodeWithTag("folder-item:${unrelated.path}").assertDoesNotExist()
        compose.onNodeWithTag("folder-detail").assertIsDisplayed()
        compose.onNodeWithTag("folder-play-all").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("folder-tracks-shuffle").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.media_album_detail_song_count, 0)).assertIsDisplayed()
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithTag("folder-item:${playlist.path}").performClick()
        waitForTag("folder-track:${first.path}")

        val up = compose.onNodeWithTag("folder-up").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val playAll = compose.onNodeWithTag("folder-play-all").assertIsDisplayed().assertIsEnabled()
        val shuffle = compose.onNodeWithTag("folder-tracks-shuffle").assertIsDisplayed().assertIsEnabled()
        assertTrue(up.right < playAll.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(playAll.fetchSemanticsNode().boundsInRoot.left < shuffle.fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithTag("media-category-tabs").assertDoesNotExist()
        compose.onNodeWithTag("album-group-selector").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.media_album_detail_song_count, 2)).assertIsDisplayed()
        compose.onNodeWithText("00:06").assertIsDisplayed()
        playAll.performClick()
        shuffle.performClick()
        compose.waitUntil(10_000L) { queues.size == 2 }
        assertTrue(queues.all { (paths, _) -> paths.toSet() == setOf(first.path, second.path) })
        assertEquals(setOf(null, PlayerPlayMode.SHUFFLE), queues.map { it.second }.toSet())

        compose.onNodeWithTag("folder-item:${otherMedia.path}").performClick()
        waitForTag("folder-track:${nestedTrack.path}")
        compose.onNodeWithText("00:07").assertIsDisplayed()
        compose.onNodeWithTag("folder-up").performClick()

        val sidecarTag = "folder-item:${sidecars.path}"
        waitForTag(sidecarTag)
        compose.onNodeWithTag("folder-tracks").performScrollToNode(hasTestTag(sidecarTag))
        val sidecarCount = compose.activity.getString(R.string.directory_item_count, 2)
        val countMatcher = hasText(sidecarCount).and(hasAnyAncestor(hasTestTag(sidecarTag)))
        compose.waitUntil(10_000L) { compose.onAllNodes(countMatcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(countMatcher, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(sidecarTag).performClick()
        waitForTag("folder-file:${image.path}")
        compose.onNodeWithTag("folder-file:${image.path}").assertIsDisplayed()
        compose.onNodeWithTag("folder-file:${subtitle.path}").performClick()
        assertEquals(listOf(subtitle), openedSubtitles.toList())
        compose.onNodeWithTag("folder-up").performClick()
        waitForTag(sidecarTag)
        pressBack()
        waitForTag("folder-item:${playlist.path}")
        compose.onNodeWithTag("folder-up").performClick()
        waitForTag("folder-list")
    }

    private fun openFolder(folder: File) {
        val tag = "folder-item:${folder.path}"
        waitForTag(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun writeArtwork(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun chooseView(mode: AlbumViewMode) {
        compose.onNodeWithTag("folder-settings").performClick()
        settingsRow("folder-settings-view-mode").performClick()
        compose.onNodeWithText(compose.activity.getString(mode.labelRes)).performClick()
        compose.onNodeWithTag("folder-settings-back").performClick()
    }

    private fun settingsRow(tag: String) = compose.onNodeWithTag("folder-settings-list")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }

    private fun waitForTag(tag: String, unmerged: Boolean = false) = compose.waitUntil(10_000L) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
    }

    private fun indexed(file: File, durationMs: Long) = MediaAlbumTrack(MediaLibraryTrack(file.path, file.name, durationMs = durationMs))
}

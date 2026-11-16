package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.player.PlayerLibraryWriter
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

@RunWith(AndroidJUnit4::class)
class MediaSelectionOptionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: Fixture? = null

    @After fun cleanup() {
        fixture?.let { f ->
            barrier {}
            f.repository.close()
            compose.activity.deleteDatabase(f.database)
            f.root.deleteRecursively()
        }
    }

    @Test fun optionsAreScrollableAndSingleFileActionsAreDisabledForMultipleFiles() {
        val f = Fixture().also { fixture = it }
        f.files.take(2).forEach { f.selection.select(it.path) }
        var received: Pair<MediaSelectionAction, List<String>>? = null
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaSelectionOptionsDialog(f.selection, onAction = { action, paths -> received = action to paths }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("media-selection-options-dialog").assertIsDisplayed()
        compose.onNodeWithTag("media-selection-close-after-action").assertIsOn()
            .performClick().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(f.selection.closeAfterAction) }
        action(MediaSelectionAction.FILE_INFO).assertIsNotEnabled()
        action(MediaSelectionAction.LOCATE_DIRECTORY).assertIsNotEnabled()
        action(MediaSelectionAction.EDIT_TAGS).assertIsEnabled()
        action(MediaSelectionAction.PLAY_NEXT).assertIsEnabled()
        action(MediaSelectionAction.SHARE).assertIsEnabled()
        action(MediaSelectionAction.CLEAR_HISTORY).assertIsEnabled()
        action(MediaSelectionAction.DELETE_FILES).assertIsEnabled()
        action(MediaSelectionAction.ADD_TO_QUEUE).performClick()
        compose.runOnIdle {
            assertEquals(MediaSelectionAction.ADD_TO_QUEUE to f.files.take(2).map { it.path }, received)
        }
    }

    @Test fun informationUsesThePlayerDialogAndKeepsTheCapturedFileAfterSelectionCloses() {
        val f = Fixture(withPlayableFixture = true).also { fixture = it }
        f.selection.select(f.files[0].path)
        f.selection.optionsOpen = true
        showHost(f)
        action(MediaSelectionAction.FILE_INFO).assertIsEnabled().performClick()
        compose.onNodeWithTag("player-track-info-dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(f.selection.active)
            f.selection.select(f.files[1].path)
        }
        compose.onNodeWithText(f.files[0].name).assertIsDisplayed()
        compose.onNodeWithTag("player-track-info-fields").assertIsDisplayed()
        compose.onNodeWithTag("media-selection-options-dialog").assertDoesNotExist()
    }

    @Test fun playNextUsesTheSelectedFilesAndClosesSelectionByDefault() {
        val f = Fixture().also { fixture = it }
        f.files.take(2).forEach { f.selection.select(it.path) }
        f.selection.optionsOpen = true
        var received = emptyList<String>()
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaSelectionActionHost(f.selection, onAddToQueue = {}, onPlayNext = { received = it },
                    onEditTags = {}, onLocate = {}, onDeleteFiles = { _, _ -> }, repository = f.repository)
            }
        }
        action(MediaSelectionAction.PLAY_NEXT).performClick()
        compose.runOnIdle {
            assertEquals(f.files.take(2).map { it.path }, received)
            assertFalse(f.selection.active)
            assertTrue(f.selection.selectedPaths.isEmpty())
        }
    }

    @Test fun favoriteRemovalAndHistoryClearApplyToTheSelectionOnly() {
        val f = Fixture().also { fixture = it }
        f.selection.closeAfterAction = false
        f.files.take(2).forEach { f.selection.select(it.path) }
        f.selection.optionsOpen = true
        barrier {
            f.repository.addTracksToPlaylists(listOf(MediaLibraryRepository.FAVORITES_PLAYLIST_ID), f.files[2].path)
            f.files.forEach { f.repository.recordPlaybackHistory(it.path, 1_000L, 0L, incrementPlayCount = true) }
        }
        showHost(f)
        action(MediaSelectionAction.ADD_TO_FAVORITES).performClick()
        compose.waitUntil(5_000L) { barrier { f.repository.getFavorites().size == 3 } }
        compose.runOnIdle {
            assertTrue(f.selection.active)
            f.selection.optionsOpen = true
        }
        action(MediaSelectionAction.REMOVE_FROM_FAVORITES).performClick()
        compose.waitUntil(5_000L) { barrier { f.repository.getFavorites().map { it.path } == listOf(f.files[2].path) } }
        compose.runOnIdle { f.selection.optionsOpen = true }
        action(MediaSelectionAction.CLEAR_HISTORY).performClick()
        compose.waitUntil(5_000L) { barrier { f.repository.getPlaybackHistory().map { it.path } == listOf(f.files[2].path) } }
        compose.runOnIdle { assertEquals(f.files.take(2).map { it.path }, f.selection.selectedPaths) }
        assertTrue(f.files.all { it.isFile })
    }

    private fun showHost(f: Fixture) {
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaSelectionActionHost(f.selection, onAddToQueue = {}, onPlayNext = {}, onEditTags = {}, onLocate = {},
                        onDeleteFiles = { _, success -> success() }, repository = f.repository)
                }
            }
        }
    }

    private fun action(action: MediaSelectionAction): androidx.compose.ui.test.SemanticsNodeInteraction {
        val tag = "media-selection-action:${action.name}"
        compose.onNodeWithTag("media-selection-action-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private inner class Fixture(withPlayableFixture: Boolean = false) {
        val root = File(compose.activity.cacheDir, "media-selection-options-${System.nanoTime()}").apply { check(mkdirs()) }
        val database = "media-selection-options-${System.nanoTime()}.db"
        val selection = MediaSelectionController()
        val files = (1..3).map { index ->
            File(root, "Selected $index.m4a").apply {
                if (withPlayableFixture) {
                    InstrumentationRegistry.getInstrumentation().context.assets.open("media-tags/preservation.m4a")
                        .use { input -> outputStream().use { output -> input.copyTo(output) } }
                } else writeBytes(byteArrayOf(0))
            }
        }
        val repository = MediaLibraryRepository.open(compose.activity, database) { metadata, duration ->
            MediaLibraryTrack(metadata.path, metadata.file.name, durationMs = duration)
        }
    }

    private fun <T> barrier(action: () -> T): T = runBlocking { PlayerLibraryWriter.execute(action) }
}

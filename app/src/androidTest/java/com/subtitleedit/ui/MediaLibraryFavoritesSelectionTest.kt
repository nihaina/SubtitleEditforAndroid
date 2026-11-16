package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryFavoritesSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixtureDirectory: File? = null

    @After fun removeFixtures() { fixtureDirectory?.deleteRecursively() }

    @Test fun longPressSelectsRowsAndCancellingRestoresPlayback() {
        val harness = showFavorites(3)
        row(harness.tracks[0]).performTouchInput { longClick() }
        row(harness.tracks[1]).performClick()
        row(harness.tracks[0]).assertIsSelected()
        row(harness.tracks[1]).assertIsSelected()
        compose.runOnIdle { assertTrue(harness.played.isEmpty()) }

        compose.onNodeWithTag("library-selection-expand").performClick()
        compose.onNodeWithTag("library-selection-remove:${harness.tracks[0].path}").performClick()
        row(harness.tracks[0]).assertIsNotSelected()
        row(harness.tracks[1]).assertIsSelected()
        compose.onNodeWithTag("library-selection-cancel").performClick()
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        row(harness.tracks[1]).performClick()
        compose.runOnIdle { assertEquals(listOf(1), harness.played) }
    }

    @Test fun rangeAndInvertApplyToFavoritesAndBatchActionRemovesFavorites() {
        val harness = showFavorites(4)
        row(harness.tracks[0]).performTouchInput { longClick() }
        row(harness.tracks[2]).performClick()
        advanced(R.string.media_lib_selection_range)
        harness.tracks.take(3).forEach { row(it).assertIsSelected() }
        row(harness.tracks.last()).assertIsNotSelected()
        advanced(R.string.media_lib_selection_invert)
        harness.tracks.take(3).forEach { row(it).assertIsNotSelected() }
        row(harness.tracks.last()).assertIsSelected()

        option(R.string.player_action_unfavorite)
        compose.runOnIdle {
            assertEquals(listOf(listOf(harness.tracks.last().path)), harness.removed)
            assertTrue(harness.played.isEmpty())
        }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
    }

    @Test fun selectedFilesCanBeAddedToPlaylistsAndSystemBackClearsSelection() {
        val harness = showFavorites(2)
        row(harness.tracks.first()).performTouchInput { longClick() }
        advanced(R.string.media_lib_selection_all)
        option(R.string.player_action_add_to_playlist)
        compose.runOnIdle { assertEquals(listOf(harness.tracks.map { it.path }), harness.added) }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()

        row(harness.tracks.first()).performTouchInput { longClick() }
        option(R.string.media_lib_files_info)
        compose.runOnIdle {
            assertEquals(listOf(listOf(harness.tracks.first().path)), harness.info)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.runOnIdle { assertFalse(compose.activity.isFinishing) }
    }

    @Test fun expandedSelectionPanelDoesNotCoverLastFavorite() {
        val harness = showFavorites(24)
        row(harness.tracks.first()).performTouchInput { longClick() }
        compose.onNodeWithTag("library-selection-expand").performClick()
        compose.onNodeWithTag("library-favorites-list").performScrollToIndex(harness.tracks.size)
        val last = row(harness.tracks.last()).fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("library-selection-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Last favorite stays above the selection panel", last.bottom <= panel.top + 1f)
        row(harness.tracks.last()).performClick()
        row(harness.tracks.last()).assertIsSelected()
    }

    private fun advanced(labelRes: Int) {
        compose.onNodeWithTag("library-selection-advanced").performClick()
        compose.onNodeWithText(compose.activity.getString(labelRes)).performClick()
    }

    private fun option(labelRes: Int) {
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(labelRes)).performClick()
    }

    private fun row(track: MediaLibraryTrack) = compose.onNodeWithTag("library-favorite:${track.path}")

    private fun showFavorites(count: Int): Harness {
        val root = File(compose.activity.cacheDir, "library-favorites-selection-${UUID.randomUUID()}").apply { mkdirs() }
        fixtureDirectory = root
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("media-tags/preservation.m4a").use { it.readBytes() }
        val tracks = (1..count).map { index ->
            val file = File(root, "Favorite ${index.toString().padStart(2, '0')}.m4a").apply { writeBytes(bytes) }
            MediaLibraryTrack(file.path, file.nameWithoutExtension, durationMs = 1_000L)
        }
        val harness = Harness(tracks)
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    FavoritesTabContent(
                        tracks = tracks,
                        onPlay = { harness.played += it },
                        onPlayAll = { harness.played += -1 },
                        onToggleFavorite = { harness.removed += listOf(it.path) },
                        onAddToPlaylist = { harness.added += listOf(it.path) },
                        onRemoveFavorites = { selection -> harness.removed += selection.map { it.path } },
                        onAddSelectionToPlaylist = { selection -> harness.added += selection.map { it.path } },
                        onShowSelectionInfo = { selection -> harness.info += selection.map { it.path } }
                    )
                }
            }
        }
        return harness
    }

    private class Harness(
        val tracks: List<MediaLibraryTrack>,
        val played: MutableList<Int> = mutableListOf(),
        val removed: MutableList<List<String>> = mutableListOf(),
        val added: MutableList<List<String>> = mutableListOf(),
        val info: MutableList<List<String>> = mutableListOf()
    )
}

package com.subtitleedit.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Queue batch actions use their selected snapshot and never toggle existing library membership. */
@RunWith(AndroidJUnit4::class)
class PlaybackQueueLibraryActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: Fixture? = null

    @After fun cleanup() {
        fixture?.let { f ->
            compose.runOnIdle { f.showing.value = false }
            barrier {}
            f.repository.close()
            compose.activity.deleteDatabase(f.database)
            f.directory.deleteRecursively()
        }
    }

    @Test fun favoritingASelectionTwiceRetainsAlreadyFavoritedAndUnselectedFiles() {
        val f = showActions()
        barrier {
            f.repository.addTracksToPlaylists(listOf(MediaLibraryRepository.FAVORITES_PLAYLIST_ID), f.tracks[0].path)
            f.repository.addTracksToPlaylists(listOf(MediaLibraryRepository.FAVORITES_PLAYLIST_ID), f.tracks[2].path)
        }
        repeat(2) {
            compose.onNodeWithTag("queue-action-favorite").performClick()
            assertEquals(f.tracks.map { track -> track.path }.toSet(),
                barrier { f.repository.getFavorites().map { track -> track.path }.toSet() })
            assertEquals(3, barrier { f.repository.getFavorites().size })
        }
        assertSourceAndFilesUnchanged(f)
    }

    @Test fun assigningToAPlaylistKeepsTheSelectedSnapshotWhenTheVisibleSelectionChanges() {
        val f = showActions()
        compose.onNodeWithTag("queue-action-playlist").performClick()
        val choice = "library-playlist-choice:${f.targetId}"
        compose.waitUntil(5_000L) {
            compose.onAllNodes(hasTestTag(choice)).fetchSemanticsNodes().size == 1
        }
        compose.runOnIdle { f.selected.value = listOf(f.tracks[2]) }
        compose.onNodeWithTag("library-playlist-choices").performScrollToNode(hasTestTag(choice))
        compose.onNodeWithTag(choice).performClick()
        assertEquals(f.tracks.take(2).map { it.path },
            barrier { f.repository.getPlaylistTracks(f.targetId).map { it.path } })
        assertTrue("Assignment must not also add the changed selection",
            barrier { f.repository.getPlaylistTracks(f.targetId).none { it.path == f.tracks[2].path } })
        assertSourceAndFilesUnchanged(f)
    }

    private fun showActions(): Fixture {
        val f = Fixture().also { fixture = it }
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (f.showing.value) {
                        val actions = rememberPlaybackQueueLibraryActions(f.repository)
                        Column {
                            Button(onClick = { actions.favorite(f.selected.value) },
                                modifier = Modifier.testTag("queue-action-favorite")) { Text("Favorite selected") }
                            Button(onClick = { actions.addToPlaylist(f.selected.value) },
                                modifier = Modifier.testTag("queue-action-playlist")) { Text("Assign selected") }
                        }
                    }
                }
            }
        }
        return f
    }

    private fun assertSourceAndFilesUnchanged(f: Fixture) {
        assertEquals(f.tracks.map { it.path },
            barrier { f.repository.getPlaylistTracks(f.sourceId).map { it.path } })
        assertTrue(f.tracks.all { it.file.isFile })
    }

    private inner class Fixture {
        val database = "queue-library-actions-${System.nanoTime()}.db"
        val directory = File(compose.activity.cacheDir, "queue-library-actions-${System.nanoTime()}")
            .apply { check(mkdirs()) }
        val tracks = (1..3).map { index ->
            val file = File(directory, "$index.wav").apply { writeBytes(byteArrayOf(0)) }
            MediaLibraryTrack(file.path, file.name, durationMs = index * 1_000L)
        }
        val repository = MediaLibraryRepository.open(compose.activity, database) { metadata, duration ->
            MediaLibraryTrack(metadata.path, metadata.file.name, durationMs = duration)
        }
        val sourceId = barrier {
            requireNotNull(repository.createPlaylist("Original source")).also { id ->
                tracks.forEach { assertTrue(repository.addTrackToPlaylist(id, it.path, it.durationMs)) }
            }
        }
        val targetId = barrier { requireNotNull(repository.createPlaylist("Batch target")) }
        val selected: MutableState<List<MediaLibraryTrack>> = mutableStateOf(tracks.take(2))
        val showing = mutableStateOf(true)
    }

    private fun <T> barrier(action: () -> T): T = runBlocking { PlayerLibraryWriter.execute(action) }
}

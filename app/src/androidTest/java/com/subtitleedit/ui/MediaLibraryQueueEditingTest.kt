package com.subtitleedit.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import com.subtitleedit.R
import com.subtitleedit.dragQueueWithLongPress
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.player.PlayerLibraryWriter
import com.subtitleedit.player.PlayerPanel
import com.subtitleedit.player.PlayerPanelSheet
import com.subtitleedit.player.PlayerPlayMode
import com.subtitleedit.player.PlayerQueueSource
import com.subtitleedit.player.PlayerQueueSourceKind
import com.subtitleedit.player.PlayerSystemVolume
import com.subtitleedit.player.PlayerTrack
import com.subtitleedit.player.PlayerViewModel
import com.subtitleedit.player.SystemMediaVolume
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Both surfaces edit the live queue; saved-only edits never change its source playlist or files. */
@RunWith(AndroidJUnit4::class)
class MediaLibraryQueueEditingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: Fixture? = null

    @After fun cleanup() {
        fixture?.let { f ->
            compose.runOnIdle { f.store.clear() }
            barrier()
            f.repository.close()
            compose.activity.deleteDatabase(f.database)
            val editor = f.preferences.edit()
            f.previousPreferences.forEach { (key, value) -> restore(editor, key, value) }
            f.tracks.forEach { editor.remove("last_position_${it.path}") }
            assertTrue(editor.commit())
            f.directory.deleteRecursively()
        }
    }

    @Test fun libraryDraggingAndRemovingRowsAreReflectedInThePlayerPanelWithoutChangingItsSource() {
        val f = showLiveQueue()
        compose.onNodeWithTag("library-queue-remove:${f.tracks[0].path}").performClick()
        drag("library-queue", f.tracks[3].path, f.tracks[1].path)
        val expected = listOf(f.tracks[3], f.tracks[1], f.tracks[2])
        compose.runOnIdle {
            assertEquals(expected, f.model.state.value.playlist)
            assertEquals(f.tracks[1], f.model.state.value.currentTrack)
            assertEquals(f.source, f.model.state.value.queueSource)
            f.model.openPanel(PlayerPanel.PLAYLIST)
        }
        compose.onNodeWithTag("player-queue-sort").assertIsDisplayed()
        compose.onNodeWithTag("player-queue-row:${f.tracks[0].path}").assertDoesNotExist()
        val tops = expected.map { compose.onNodeWithTag("player-queue-row:${it.path}")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertTrue("The panel uses the library's edited ordering", tops.zipWithNext().all { (a, b) -> a < b })
        compose.onNodeWithTag("player-queue-row:${expected.first().path}").performTouchInput { longClick() }
        pressBack()
        compose.onNodeWithTag("player-queue-sort").assertIsDisplayed()
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(PlayerPanel.PLAYLIST, f.model.state.value.panel) }
        compose.onNodeWithTag("player-queue-remove:${f.tracks[2].path}").performClick()
        compose.runOnIdle { f.model.closePanel() }
        compose.onNodeWithTag("library-queue-row:${f.tracks[2].path}").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(f.tracks[3], f.tracks[1]), f.model.state.value.playlist)
            assertEquals(f.tracks[1], f.model.state.value.currentTrack)
        }
        assertSourcesUnchanged(f)
        assertEquals(listOf(f.tracks[3].path, f.tracks[1].path), persistedPaths(f))
    }

    @Test fun librarySelectionRemovesOnlyTheQueueAndAnEmptyLiveQueueCannotRestoreTheOldSavedRows() {
        val f = showLiveQueue()
        compose.onNodeWithTag("library-queue-row:${f.tracks[0].path}").performTouchInput { longClick() }
        compose.onNodeWithTag("library-selection-panel").assertIsDisplayed()
        compose.onNodeWithTag("library-queue-remove:${f.tracks.first().path}").assertDoesNotExist()
        compose.onNodeWithTag("library-queue-check:${f.tracks[0].path}")
            .assertIsSelected()
        compose.onNodeWithTag("library-queue-check:${f.tracks[2].path}").assertDoesNotExist()
        compose.onNodeWithTag("library-queue-row:${f.tracks[2].path}").performClick()
        removeSelected()
        compose.runOnIdle {
            assertEquals(listOf(f.tracks[1], f.tracks[3]), f.model.state.value.playlist)
            assertEquals(f.tracks[1], f.model.state.value.currentTrack)
        }
        compose.onNodeWithTag("library-queue-row:${f.tracks[1].path}").performTouchInput { longClick() }
        compose.onNodeWithTag("library-selection-advanced").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_lib_selection_all)).performClick()
        removeSelected()
        barrier()
        compose.onNodeWithTag("library-queue-empty").assertIsDisplayed()
        f.tracks.forEach { compose.onNodeWithTag("library-queue-row:${it.path}").assertDoesNotExist() }
        compose.runOnIdle {
            assertTrue(f.model.state.value.playlist.isEmpty())
            assertTrue("An edited empty queue stays authoritative over the stale saved snapshot",
                f.model.state.value.queueVersion > 0)
        }
        assertTrue(persistedPaths(f).isEmpty())
        assertSourcesUnchanged(f)
        compose.runOnIdle { f.model.openQueue(f.tracks, 0, queueSource = f.source) }
        compose.onNodeWithTag("library-queue-row:${f.tracks.first().path}").assertIsDisplayed()
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(f.tracks, f.model.state.value.playlist) }
    }

    @Test fun savedOnlyRemovalPersistsAndPlaybackReceivesTheEditedQueueUntilItsNextReplacement() {
        val f = Fixture().also { fixture = it }
        val snapshot = mutableStateOf(f.libraryTracks to 1)
        barrier { f.repository.saveCurrentQueue(f.tracks.map { it.path }, 1) }
        val requests = mutableListOf<Triple<List<String>, Int, Boolean>>()
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaLibraryQueueContent(
                        savedQueue = snapshot.value,
                        onPlaySavedQueue = { tracks, index, all -> requests += Triple(tracks.map { it.path }, index, all) },
                        model = null,
                        repository = f.repository
                    )
                }
            }
        }
        compose.onNodeWithTag("library-queue-remove:${f.tracks.first().path}").performClick()
        val expected = f.tracks.drop(1).map { it.path }
        assertEquals(expected, persistedPaths(f))
        compose.onNodeWithTag("library-queue-row:${f.tracks[2].path}").performClick()
        compose.onNodeWithTag("library-queue-play-all").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple(expected, 1, false), Triple(expected, 0, true)), requests)
        }
        // Acknowledge the persisted edit, then supply a newly loaded queue from another source.
        val edited = barrier { f.repository.getCurrentQueue() }
        compose.runOnIdle { snapshot.value = edited }
        compose.waitForIdle()
        val replacement = listOf(f.libraryTracks[3], f.libraryTracks[2])
        barrier { f.repository.saveCurrentQueue(replacement.map { it.path }, 0) }
        compose.runOnIdle { snapshot.value = replacement to 0 }
        compose.onNodeWithTag("library-queue-row:${f.tracks[1].path}").assertDoesNotExist()
        compose.onNodeWithTag("library-queue-row:${f.tracks[3].path}").assertIsDisplayed()
        compose.onNodeWithTag("library-queue-play-all").performClick()
        compose.runOnIdle { assertEquals(Triple(replacement.map { it.path }, 0, true), requests.last()) }
        assertSourcesUnchanged(f)
    }

    private fun showLiveQueue(): Fixture {
        val f = Fixture().also { fixture = it }
        compose.runOnIdle { f.model.configure(f.tracks, 1, queueSource = f.source) }
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by f.model.state.collectAsState()
                    MediaLibraryQueueContent(
                        savedQueue = f.libraryTracks to 1,
                        onPlaySavedQueue = { _, _, _ -> error("The live queue must not be relaunched from saved data") },
                        model = f.model,
                        repository = f.repository
                    )
                    PlayerPanelSheet(state, f.model)
                }
            }
        }
        return f
    }

    private fun drag(prefix: String, from: String, to: String) {
        compose.onNodeWithTag("$prefix-sort").performClick()
        val list = compose.onNodeWithTag("$prefix-list").fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("$prefix-drag:$from", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithTag("$prefix-drag:$to", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        dragQueueWithLongPress(compose, compose.activity, start, end, holdBeforeMoveMs = 0L)
    }

    private fun removeSelected() {
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.player_queue_remove_selected)).performClick()
    }

    private fun persistedPaths(f: Fixture) = barrier { f.repository.getCurrentQueue().first.map { it.path } }

    private fun assertSourcesUnchanged(f: Fixture) {
        assertEquals(f.tracks.map { it.path }, barrier { f.repository.getPlaylistTracks(f.playlistId).map { it.path } })
        assertTrue(f.tracks.all { it.file.isFile })
    }

    private inner class Fixture {
        val database = "library-queue-editing-${System.nanoTime()}.db"
        val directory = File(compose.activity.cacheDir, "library-queue-editing-${System.nanoTime()}")
            .apply { check(mkdirs()) }
        val tracks = (1..4).map { number ->
            val file = File(directory, "$number.wav").apply { writeBytes(byteArrayOf(0)) }
            PlayerTrack(file.path, file.name, false)
        }
        val libraryTracks = tracks.map { MediaLibraryTrack(it.path, it.name, isVideo = it.isVideo) }
        val repository = MediaLibraryRepository.open(compose.activity, database) { metadata, duration ->
            MediaLibraryTrack(metadata.path, metadata.file.name, durationMs = duration)
        }
        val playlistId = barrier {
            requireNotNull(repository.createPlaylist("Original queue source")).also { id ->
                tracks.forEach { assertTrue(repository.addTrackToPlaylist(id, it.path)) }
            }
        }
        val source = PlayerQueueSource(PlayerQueueSourceKind.PLAYLIST, playlistId.toString())
        val preferences: SharedPreferences = compose.activity.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val preferenceKeys = listOf("default_play_mode", "end_behavior_enabled", "resume_playback", "show_loudness_waveform", "eq_enabled")
        val previousPreferences = preferenceKeys.associateWith { preferences.all[it] }
        val store = ViewModelStore()

        init {
            assertTrue(preferences.edit().putString("default_play_mode", PlayerPlayMode.REPEAT_ALL.name)
                .putBoolean("end_behavior_enabled", false).putBoolean("resume_playback", false)
                .putBoolean("show_loudness_waveform", false).putBoolean("eq_enabled", false).commit())
        }

        private val volume = object : PlayerSystemVolume {
            override fun read() = SystemMediaVolume(50, 0, 100)
            override fun setIndex(index: Int) = Unit
        }
        val model = compose.runOnIdle {
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(compose.activity.application as Application, repository, volume) as T
            })[PlayerViewModel::class.java]
        }
    }

    private fun barrier() = barrier {}
    private fun <T> barrier(action: () -> T): T = runBlocking { PlayerLibraryWriter.execute(action) }

    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            else -> error("Unexpected preference type for $key")
        }
    }
}

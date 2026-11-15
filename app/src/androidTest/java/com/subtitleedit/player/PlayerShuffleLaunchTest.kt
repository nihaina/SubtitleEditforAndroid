package com.subtitleedit.player

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Queue setup only: no player engine is attached and no user database is opened. */
@RunWith(AndroidJUnit4::class)
class PlayerShuffleLaunchTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun shuffleLaunchUsesRequestedStartAndKeepsTheDefaultPreference() {
        Fixture(PlayerPlayMode.REPEAT_ONE).use { fixture ->
            onMain { fixture.model.configure(fixture.tracks, 1, PlayerPlayMode.SHUFFLE) }
            val state = mainValue { fixture.model.state.value }
            assertEquals(PlayerPlayMode.SHUFFLE, state.mode)
            assertEquals(1, state.currentIndex)
            assertEquals(fixture.tracks[1].path, state.currentTrack?.path)
            assertEquals(listOf(0, 1, 2), state.shuffleOrder.sorted())
            assertEquals(1, state.shuffleOrder.first())
            assertEquals(PlayerPlayMode.REPEAT_ONE, PlayerPreferences.getInstance(app).defaultPlayMode)
            assertEquals(app.getString(R.string.player_mode_shuffle), state.gestureHint?.text)
        }
    }

    @Test fun regularLaunchKeepsDefaultModeAndRecreationDoesNotResetAnExplicitOverride() {
        Fixture(PlayerPlayMode.SEQUENCE).use { fixture ->
            onMain { fixture.model.configure(fixture.tracks, 0) }
            assertEquals(PlayerPlayMode.SEQUENCE, mainValue { fixture.model.state.value.mode })
            assertEquals(PlayerPlayMode.SEQUENCE, PlayerPreferences.getInstance(app).defaultPlayMode)
            assertNull(mainValue { fixture.model.state.value.gestureHint })
        }
        Fixture(PlayerPlayMode.REPEAT_ALL).use { fixture ->
            onMain { fixture.model.configure(fixture.tracks, 2, PlayerPlayMode.SHUFFLE) }
            val original = mainValue { fixture.model.state.value }
            onMain { fixture.model.configure(fixture.tracks.reversed(), 0, PlayerPlayMode.SEQUENCE) }
            val recreated = mainValue { fixture.model.state.value }
            assertEquals(PlayerPlayMode.SHUFFLE, recreated.mode)
            assertEquals(original.currentTrack, recreated.currentTrack)
            assertEquals(original.shuffleOrder, recreated.shuffleOrder)
            assertEquals(original.gestureHint, recreated.gestureHint)
            assertEquals(PlayerPlayMode.REPEAT_ALL, PlayerPreferences.getInstance(app).defaultPlayMode)
            onMain { fixture.model.clearHint() }
            onMain { fixture.model.configure(fixture.tracks, 0, PlayerPlayMode.SHUFFLE) }
            assertNull(mainValue { fixture.model.state.value.gestureHint })
        }
    }

    @Test fun shuffleKeepsTheSelectedFileWhenUnavailableAndDuplicateEntriesAreRemoved() {
        Fixture(PlayerPlayMode.SEQUENCE).use { fixture ->
            val missing = PlayerTrack(File(fixture.directory, "missing.wav").path, "Missing", false)
            val queue = listOf(missing, fixture.tracks[0], fixture.tracks[0], fixture.tracks[2])
            onMain { fixture.model.configure(queue, 3, PlayerPlayMode.SHUFFLE) }
            val state = mainValue { fixture.model.state.value }
            assertEquals(listOf(fixture.tracks[0], fixture.tracks[2]), state.playlist)
            assertEquals(fixture.tracks[2].path, state.currentTrack?.path)
            assertEquals(1, state.currentIndex)
            assertEquals(listOf(0, 1), state.shuffleOrder.sorted())
            assertEquals(1, state.shuffleOrder.first())
            assertNotEquals(PlayerPlayMode.SHUFFLE, PlayerPreferences.getInstance(app).defaultPlayMode)
        }
    }

    @Test fun shuffleWithNoAvailableFilesDoesNotAnnouncePlayback() {
        Fixture(PlayerPlayMode.SEQUENCE).use { fixture ->
            val missing = PlayerTrack(File(fixture.directory, "missing.wav").path, "Missing", false)
            onMain { fixture.model.configure(listOf(missing), 0, PlayerPlayMode.SHUFFLE) }
            val state = mainValue { fixture.model.state.value }
            assertNull(state.gestureHint)
            assertEquals(app.getString(R.string.player_playlist_empty), state.error)
            assertEquals(PlayerPlayMode.SEQUENCE, PlayerPreferences.getInstance(app).defaultPlayMode)
        }
    }

    private inner class Fixture(defaultMode: PlayerPlayMode) : Closeable {
        private val database = "shuffle-launch-${System.nanoTime()}.db"
        private val repository = MediaLibraryRepository.open(app, database)
        val directory = File(app.cacheDir, "shuffle-launch-${System.nanoTime()}").apply { mkdirs() }
        val tracks = (1..3).map { index ->
            val file = File(directory, "$index.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            PlayerTrack(file.path, file.name, false)
        }
        private val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val previousDefault = preferences.getString("default_play_mode", null)
        private val store = ViewModelStore()
        val model: PlayerViewModel

        init {
            check(preferences.edit().putString("default_play_mode", defaultMode.name).commit())
            model = mainValue {
                ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(app, repository) as T
                })[PlayerViewModel::class.java]
            }
        }

        override fun close() {
            onMain { store.clear() }
            runBlocking { PlayerLibraryWriter.execute {} }
            repository.close()
            app.deleteDatabase(database)
            val editor = preferences.edit()
            if (previousDefault == null) editor.remove("default_play_mode")
            else editor.putString("default_play_mode", previousDefault)
            tracks.forEach { editor.remove("last_position_${it.path}") }
            check(editor.commit())
            directory.deleteRecursively()
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun <T> mainValue(action: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(action()) }
        return value.get()
    }
}

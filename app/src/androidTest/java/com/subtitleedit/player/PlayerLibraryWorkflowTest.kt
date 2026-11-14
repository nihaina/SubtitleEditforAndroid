package com.subtitleedit.player

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/** Native playback and the actual dialogs, against a disposable library database. */
@RunWith(AndroidJUnit4::class)
class PlayerLibraryWorkflowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun actualPlaybackCollectionsDialogsAndFinalSavesRemainConsistent() {
        Fixture().use { fixture ->
            val model = mainValue { PlayerViewModel(app, fixture.repository) }
            val store = androidx.lifecycle.ViewModelStore()
            val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
            })
            onMain {
                provider[PlayerViewModel::class.java]
                model.configure(fixture.tracks, 0)
            }
            try {
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    var decor: View? = null
                    scenario.onActivity { activity ->
                        activity.window.hidePlayerSystemBars()
                        decor = activity.window.decorView
                        activity.setContent { SubtitleEditComposeTheme { PlayerScreen(model) } }
                    }
                    awaitState(model, "native audio actually starts") { it.ready && it.playing && it.positionMs > 250L }
                    onMain { model.pause() }
                    awaitState(model, "native pause settles") { !it.playing }
                    barrier()
                    val history = fixture.repository.getPlaybackHistory().single()
                    assertEquals(fixture.tracks[0].path, history.path)
                    assertEquals(1, history.playCount)
                    val durationBefore = fixture.repository.getAllDailyStats().sumOf { it.durationMs }
                    assertTrue("A short actual play must retain its subsecond listening tail", durationBefore > 0L)
                    Thread.sleep(500L)
                    onMain { model.saveProgress() }
                    barrier()
                    assertEquals("Paused time must not be counted", durationBefore,
                        fixture.repository.getAllDailyStats().sumOf { it.durationMs })

                    onMain { model.toggleFavorite() }
                    awaitState(model, "favorite write completes") { it.isFavorite }
                    assertEquals(fixture.tracks[0].path, fixture.repository.getFavorites().single().path)

                    onMain { model.openPlaylistPickerDialog() }
                    awaitState(model, "playlist picker opens") { it.showPlaylistPickerDialog }
                    await("picker keeps system bars hidden") { mainValue { barsHidden(requireNotNull(decor)) } }
                    val playlistId = AtomicReference<Long?>()
                    onMain { model.createUserPlaylist("Workflow playlist") { playlistId.set(it) } }
                    await("asynchronous playlist creation") { playlistId.get() != null }
                    onMain { model.addCurrentTrackToPlaylists(setOf(requireNotNull(playlistId.get()))) }
                    awaitState(model, "adding to playlist dismisses picker") { !it.showPlaylistPickerDialog }
                    barrier()
                    assertEquals(fixture.tracks[0].path,
                        fixture.repository.getPlaylistTracks(requireNotNull(playlistId.get())).single().path)

                    onMain { model.openTrackInfoDialog() }
                    awaitState(model, "metadata is loaded for the current track") {
                        it.showTrackInfoDialog && !it.trackInfoLoading && it.trackInfo?.filePath == fixture.tracks[0].path
                    }
                    await("metadata dialog keeps system bars hidden") { mainValue { barsHidden(requireNotNull(decor)) } }
                    // Natural completion while a dialog is open must not retarget that dialog.
                    onMain { model.seekTo(7_700L); model.play() }
                    awaitState(model, "natural end advances and closes the old track dialog") {
                        it.currentIndex == 1 && it.ready && it.playing && !it.showTrackInfoDialog && it.trackInfo == null
                    }
                    onMain { model.pause(); model.openPlaylistPickerDialog() }
                    awaitState(model, "second track picker loads membership without the previous track") {
                        it.showPlaylistPickerDialog && it.userPlaylists.isNotEmpty()
                    }
                    assertTrue(state(model).trackContainedPlaylistIds.isEmpty())
                    onMain { model.next() }
                    // At the sequence's end, no selection changed; explicitly select the first track.
                    onMain { model.playIndex(0) }
                    awaitState(model, "changing selection closes the playlist target") {
                        it.currentIndex == 0 && it.ready && it.playing && !it.showPlaylistPickerDialog
                    }
                    onMain { model.pause() }
                    barrier()
                    assertEquals("Replaying A after B must increment its history count", 2,
                        fixture.repository.getPlaybackHistory().first { it.path == fixture.tracks[0].path }.playCount)

                    onMain { model.setMode(PlayerPlayMode.REPEAT_ONE); model.seekTo(7_700L); model.play() }
                    await("a single-track repeat creates another play-through") {
                        barrier()
                        fixture.repository.getPlaybackHistory().first { it.path == fixture.tracks[0].path }.playCount >= 3
                    }
                    onMain { model.pause(); model.saveProgress() }
                }
            } finally {
                onMain { store.clear() }
                barrier()
            }
            val total = fixture.repository.getAllDailyStats().sumOf { it.durationMs }
            assertTrue("Final disposal must preserve completed listening intervals", total > 0L)
        }
    }

    private fun barsHidden(view: View): Boolean = ViewCompat.getRootWindowInsets(view)?.let {
        !it.isVisible(WindowInsetsCompat.Type.statusBars()) && !it.isVisible(WindowInsetsCompat.Type.navigationBars())
    } == true

    private inner class Fixture : Closeable {
        private val database = "player-workflow-${System.nanoTime()}.db"
        val repository = MediaLibraryRepository.open(app, database)
        private val directory = File(app.cacheDir, "player-library-${System.nanoTime()}").apply { mkdirs() }
        val tracks = listOf("first.wav", "second.wav").map { name ->
            val file = File(directory, name).apply { writeBytes(silenceWav()) }
            PlayerTrack(file.path, name, false)
        }
        private val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val keys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled", "show_loudness_waveform")
        private val saved = keys.associateWith { preferences.all[it] }
        init {
            assertTrue(preferences.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false)
                .putBoolean("eq_enabled", false).putBoolean("show_loudness_waveform", false).commit())
        }
        override fun close() {
            barrier()
            repository.close()
            app.deleteDatabase(database)
            val editor = preferences.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            tracks.forEach { editor.remove("last_position_${it.path}") }
            assertTrue(editor.commit())
            directory.deleteRecursively()
        }
    }

    private fun silenceWav(): ByteArray {
        val rate = 8_000
        val samples = rate * 8
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
    }
    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
        }
    }
    private fun barrier() = runBlocking { PlayerLibraryWriter.execute {} }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun awaitState(model: PlayerViewModel, label: String, predicate: (PlayerUiState) -> Boolean) = await(label) {
        val state = state(model)
        state.error?.let { throw AssertionError("$label: $it") }
        predicate(state)
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(60L)
        }
        throw AssertionError("Timed out: $label")
    }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun <T> mainValue(action: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(action()) }
        return value.get()
    }
}

package com.subtitleedit.player

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Queue persistence and real mpv playback use disposable files and an isolated library database. */
@RunWith(AndroidJUnit4::class)
class PlayerQueueEditingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun playNextInsertsRepeatedFilesAfterThePlayingOccurrenceWithoutChangingNativePlayback() = Fixture().use { f ->
        onMain { f.model.configure(f.tracks, 1, PlayerPlayMode.SHUFFLE, f.source) }
        f.openNative().use {
            awaitState(f.model, "fixture starts playing") { it.ready && it.playing && it.positionMs > 250L }
            onMain { f.model.pause(); f.model.seekTo(2_000L) }
            awaitState(f.model, "pause settles") { !it.playing && abs(it.positionMs - 2_000L) < 100L }
            val before = state(f.model)
            onMain { f.model.enqueueNextTracks(listOf(f.tracks[0], f.tracks[0])) }
            val after = state(f.model)
            assertEquals(before.currentTrack, after.currentTrack)
            assertEquals(before.positionMs, after.positionMs)
            assertEquals(before.durationMs, after.durationMs)
            assertEquals(before.queueVersion, after.queueVersion)
            assertTrue(after.ready)
            assertFalse(after.playing)
            assertEquals(listOf(f.tracks[0].path, f.tracks[1].path, f.tracks[0].path,
                f.tracks[0].path, f.tracks[2].path, f.tracks[3].path), after.playlist.map { it.path })
            assertEquals(6, after.playlist.map { it.queueEntryId }.toSet().size)
            assertEquals(2, PlayerPlaylistOrder.manualNext(after.currentIndex, after.playlist.size, after.mode, after.shuffleOrder))
            assertEquals(3, PlayerPlaylistOrder.manualNext(2, after.playlist.size, after.mode, after.shuffleOrder))
            assertEquals(f.tracks[1].path, mainValue { MPVLib.getPropertyString("path") })
            assertQueue(f, after)
            assertSourcesUnchanged(f)
        }
    }

    @Test fun firstPlayNextKeepsTheSavedCurrentOccurrenceAndInsertsBeforeItsSuccessor() = Fixture().use { f ->
        val missing = File(f.tracks[0].file.parentFile, "missing.wav").path
        f.repository.saveCurrentQueue(listOf(missing, f.tracks[0].path, f.tracks[1].path, f.tracks[0].path, f.tracks[3].path),
            3, allowDuplicates = true)
        onMain { f.model.enqueueNextTracks(listOf(f.tracks[2], f.tracks[2])) }
        awaitState(f.model, "saved queue has its next songs") { it.playlist.size == 6 }
        val state = state(f.model)
        assertEquals(2, state.currentIndex)
        assertEquals(listOf(f.tracks[0].path, f.tracks[1].path, f.tracks[0].path,
            f.tracks[2].path, f.tracks[2].path, f.tracks[3].path), state.playlist.map { it.path })
        assertFalse(state.playing)
        assertEquals(state.playlist.indices.toList(), state.shuffleOrder.sorted())
        assertFalse(mainValue { f.model.expanded.value })
        assertQueue(f, state)
    }

    @Test fun firstAppendsKeepTheDormantSavedQueueAndDoNotStartPlayback() = Fixture().use { f ->
        f.repository.saveCurrentQueue(listOf(f.tracks[0].path, f.tracks[1].path, f.tracks[0].path),
            2, allowDuplicates = true)
        onMain {
            f.model.appendQueueTracks(listOf(f.tracks[2]))
            f.model.appendQueueTracks(listOf(f.tracks[2]))
        }
        awaitState(f.model, "both additions preserve the saved queue") { it.playlist.size == 5 }
        val appended = state(f.model)
        assertEquals(listOf(f.tracks[0].path, f.tracks[1].path, f.tracks[0].path,
            f.tracks[2].path, f.tracks[2].path), appended.playlist.map { it.path })
        assertEquals(2, appended.currentIndex)
        assertFalse(appended.playing)
        assertFalse(mainValue { f.model.expanded.value })
        assertEquals(5, appended.playlist.map { it.queueEntryId }.toSet().size)
        assertQueue(f, appended)
        onMain {
            f.model.removeQueueEntries(appended.playlist.mapTo(hashSetOf()) { it.queueEntryId })
            f.model.appendQueueTracks(listOf(f.tracks[3]))
        }
        assertEquals(listOf(f.tracks[3].path), state(f.model).playlist.map { it.path })
        assertQueue(f, state(f.model))
    }

    @Test fun restoringRepeatedPathsPreservesTheSelectedOccurrenceAndGivesEveryRowAnIdentity() = Fixture().use { f ->
        onMain {
            f.model.restoreSession(listOf(f.tracks[0], f.tracks[0], f.tracks[1], f.tracks[0]),
                3, PlayerPlayMode.REPEAT_ALL, 0L, playing = false, expanded = false)
        }
        val restored = state(f.model)
        assertEquals(listOf(f.tracks[0].path, f.tracks[0].path, f.tracks[1].path, f.tracks[0].path),
            restored.playlist.map { it.path })
        assertEquals(4, restored.playlist.map { it.queueEntryId }.toSet().size)
        assertEquals(3, restored.currentIndex)
        assertQueue(f, restored)
    }

    @Test fun repeatedAppendsPersistDistinctOccurrencesAndEntryEditsKeepTheSelectedCopy() = Fixture().use { f ->
        onMain {
            f.model.configure(f.tracks, 0, queueSource = f.source)
            f.model.appendQueueTracks(listOf(f.tracks[0], f.tracks[0]))
        }
        val appended = state(f.model)
        assertEquals(6, appended.playlist.size)
        assertEquals(6, appended.playlist.map { it.queueEntryId }.toSet().size)
        assertEquals(f.tracks[0].queueEntryId, appended.currentTrack?.queueEntryId)
        assertQueue(f, appended)
        val copy = appended.playlist.last()
        onMain {
            f.model.playQueueEntry(copy.queueEntryId)
            f.model.moveQueueEntry(copy.queueEntryId, f.tracks[1].queueEntryId)
            f.model.removeQueueEntries(setOf(f.tracks[0].queueEntryId))
        }
        val edited = state(f.model)
        assertEquals(copy.queueEntryId, edited.currentTrack?.queueEntryId)
        assertEquals(2, edited.playlist.count { it.path == f.tracks[0].path })
        assertQueue(f, edited)
        val persisted = f.repository.getCurrentQueue()
        assertEquals(edited.currentIndex, persisted.second)
        assertEquals(copy.path, persisted.first[persisted.second].path)
        assertSourcesUnchanged(f)
    }

    @Test fun editsPersistWithoutChangingTheSourceAndANewQueueReplacesTheTemporaryEdits() = Fixture().use { f ->
        onMain { f.model.configure(f.tracks, 1, queueSource = f.source) }
        val original = state(f.model)
        onMain {
            f.model.moveQueueTrack(f.tracks[3].path, f.tracks[0].path)
            f.model.removeQueueTracks(setOf(f.tracks[0].path, f.tracks[2].path))
        }
        val edited = state(f.model)
        assertEquals(listOf(f.tracks[3], f.tracks[1]), edited.playlist)
        assertEquals(f.tracks[1], edited.currentTrack)
        assertEquals(original.queueVersion, edited.queueVersion)
        assertEquals(f.source, edited.queueSource)
        assertQueue(f, edited)
        assertSourcesUnchanged(f)

        onMain { f.model.removeQueueTracks(edited.playlist.mapTo(hashSetOf()) { it.path }) }
        assertTrue(state(f.model).playlist.isEmpty())
        assertEquals(-1, state(f.model).currentIndex)
        assertEquals(original.queueVersion, state(f.model).queueVersion)
        assertQueue(f, state(f.model))
        onMain { f.model.openQueue(f.tracks, 0, queueSource = f.source) }
        assertEquals(f.tracks, state(f.model).playlist)
        assertTrue(state(f.model).queueVersion > original.queueVersion)
        assertQueue(f, state(f.model))
        assertSourcesUnchanged(f)
    }

    @Test fun reorderingAndRemovingOtherRowsKeepNativePositionPauseAndPlaybackHistory() = Fixture().use { f ->
        onMain { f.model.configure(f.tracks, 1, PlayerPlayMode.SHUFFLE, f.source) }
        f.openNative().use {
            awaitState(f.model, "fixture starts playing") { it.ready && it.playing && it.positionMs > 250L }
            onMain { f.model.pause(); f.model.seekTo(2_500L) }
            awaitState(f.model, "pause and seek settle") { !it.playing && abs(it.positionMs - 2_500L) < 100L }
            barrier()
            val original = state(f.model)
            val originalTraversal = original.shuffleOrder.map { original.playlist[it].path }
            val history = f.repository.getPlaybackHistory().single()
            onMain {
                f.model.moveQueueTrack(f.tracks[3].path, f.tracks[0].path)
                f.model.removeQueueTracks(setOf(f.tracks[0].path, f.tracks[2].path))
            }
            val edited = state(f.model)
            assertEquals(f.tracks[1], edited.currentTrack)
            assertTrue(edited.ready)
            assertFalse(edited.playing)
            assertEquals(original.positionMs, edited.positionMs)
            assertEquals(original.durationMs, edited.durationMs)
            assertEquals(original.queueVersion, edited.queueVersion)
            assertEquals(originalTraversal.filter { it in edited.playlist.map { row -> row.path } },
                edited.shuffleOrder.map { edited.playlist[it].path })
            assertEquals(f.tracks[1].path, mainValue { MPVLib.getPropertyString("path") })
            assertEquals(true, mainValue { MPVLib.getPropertyBoolean("pause") })
            Thread.sleep(300L)
            assertTrue(abs(state(f.model).positionMs - original.positionMs) < 100L)
            barrier()
            assertEquals(history.playCount, f.repository.getPlaybackHistory().single().playCount)
            assertQueue(f, edited)
            assertSourcesUnchanged(f)
        }
    }

    @Test fun removingCurrentStartsItsReplacementAtZeroAndPreservesPauseThenClearsTheNativeQueue() = Fixture().use { f ->
        onMain { f.model.configure(f.tracks, 1, queueSource = f.source) }
        f.openNative().use {
            awaitState(f.model, "fixture starts playing") { it.ready && it.playing && it.positionMs > 250L }
            onMain { f.model.pause(); f.model.setResumePlayback(true) }
            assertTrue(f.preferences.edit().putLong("last_position_${f.tracks[2].path}", 4_000L).commit())
            val version = state(f.model).queueVersion
            onMain { f.model.removeQueueTracks(setOf(f.tracks[1].path)) }
            awaitState(f.model, "paused replacement is loaded from zero") {
                it.currentTrack == f.tracks[2] && it.ready && !it.playing && it.positionMs in 0L..100L
            }
            assertEquals(f.tracks[2].path, mainValue { MPVLib.getPropertyString("path") })
            assertEquals(true, mainValue { MPVLib.getPropertyBoolean("pause") })
            onMain { f.model.play() }
            awaitState(f.model, "replacement resumes") { it.playing && it.positionMs > 250L }
            onMain { f.model.removeQueueTracks(setOf(f.tracks[2].path, f.tracks[3].path)) }
            awaitState(f.model, "the preceding survivor continues playing") {
                it.currentTrack == f.tracks[0] && it.ready && it.playing && it.positionMs in 150L..2_000L
            }
            onMain { f.model.removeQueueTracks(setOf(f.tracks[0].path)); f.model.saveProgress() }
            awaitState(f.model, "removing the last row stops playback") {
                it.playlist.isEmpty() && it.currentIndex == -1 && !it.ready && !it.playing
            }
            assertEquals(version, state(f.model).queueVersion)
            assertTrue(state(f.model).shuffleOrder.isEmpty())
            await("native media stops") { mainValue { MPVLib.getPropertyString("path").isNullOrEmpty() } }
            assertQueue(f, state(f.model))
            assertSourcesUnchanged(f)
        }
        barrier()
        assertTrue("Disposing the player must not reintroduce removed queue rows", f.repository.getCurrentQueue().first.isEmpty())
    }

    private fun assertQueue(f: Fixture, state: PlayerUiState) {
        barrier()
        val persisted = f.repository.getCurrentQueue()
        assertEquals(state.playlist.map { it.path }, persisted.first.map { it.path })
        if (state.playlist.isNotEmpty()) assertEquals(state.currentIndex, persisted.second)
    }

    private fun assertSourcesUnchanged(f: Fixture) {
        barrier()
        assertEquals(f.tracks.map { it.path }, f.repository.getPlaylistTracks(f.playlistId).map { it.path })
        assertEquals(listOf(f.tracks[1].path), f.repository.getFavorites().map { it.path })
        assertTrue(f.tracks.all { it.file.isFile })
    }

    private inner class Fixture : Closeable {
        val root = File(app.cacheDir, "queue-edits-${System.nanoTime()}").apply { check(mkdirs()) }
        val tracks = (1..4).map { index ->
            val file = File(root, "$index.wav").apply { writeBytes(silenceWav()) }
            PlayerTrack(file.path, file.name, false)
        }
        private val database = "queue-edits-${System.nanoTime()}.db"
        val repository = MediaLibraryRepository.open(app, database) { metadata, duration ->
            MediaLibraryTrack(metadata.path, metadata.file.name, durationMs = duration)
        }
        val playlistId = requireNotNull(repository.createPlaylist("Original source"))
        val source = PlayerQueueSource(PlayerQueueSourceKind.PLAYLIST, playlistId.toString())
        val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val keys = listOf("default_play_mode", "end_behavior_enabled", "track_end_action", "queue_end_action",
            "queue_end_wrap", "queue_end_reshuffle", "resume_playback", "show_loudness_waveform", "eq_enabled")
        private val saved = keys.associateWith { preferences.all[it] }
        private val store = ViewModelStore()

        init {
            tracks.forEach { assertTrue(repository.addTrackToPlaylist(playlistId, it.path)) }
            assertTrue(repository.toggleFavorite(tracks[1].path))
            assertTrue(preferences.edit().putString("default_play_mode", PlayerPlayMode.REPEAT_ALL.name)
                .putBoolean("end_behavior_enabled", false).putBoolean("resume_playback", false)
                .putBoolean("show_loudness_waveform", false).putBoolean("eq_enabled", false).commit())
        }

        private val volume = object : PlayerSystemVolume {
            override fun read() = SystemMediaVolume(50, 0, 100)
            override fun setIndex(index: Int) = Unit
        }
        val model = mainValue {
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(app, repository, volume) as T
            })[PlayerViewModel::class.java]
        }

        fun openNative(): ActivityScenario<ComponentActivity> = ActivityScenario.launch(ComponentActivity::class.java).also { scenario ->
            scenario.onActivity { activity -> activity.setContent { SubtitleEditComposeTheme { PlayerScreen(model) } } }
        }

        override fun close() {
            onMain { store.clear() }
            barrier()
            repository.close()
            app.deleteDatabase(database)
            val editor = preferences.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            tracks.forEach { editor.remove("last_position_${it.path}") }
            assertTrue(editor.commit())
            root.deleteRecursively()
        }
    }

    private fun silenceWav(): ByteArray {
        val samples = 8_000 * 15
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
    }

    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            else -> error("Unsupported preference: $key")
        }
    }

    private fun barrier() = runBlocking { PlayerLibraryWriter.execute {} }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun <T> mainValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(action()) }
        return result.get()
    }
    private fun awaitState(model: PlayerViewModel, message: String, matches: (PlayerUiState) -> Boolean) = await(message) {
        val current = state(model)
        current.error?.let { throw AssertionError("$message: $it") }
        matches(current)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $message" }
            Thread.sleep(50L)
        }
    }
}

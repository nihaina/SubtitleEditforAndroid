package com.subtitleedit.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.MainActivity
import com.subtitleedit.MainViewModel
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.R
import com.subtitleedit.feature.ui.MainActivityScreenState
import com.subtitleedit.library.MediaLibraryFileObserver
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.ui.MediaTrackRevealOpacity
import com.subtitleedit.ui.MediaTrackRevealPulse
import com.subtitleedit.util.FavoriteDirectoryStore
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Both user-facing locate actions must reach the actual directory row without restarting audio. */
@RunWith(AndroidJUnit4::class)
class MainMediaLocationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun songInformationAndSelectionLocateTheFileAndEachFlashThreeTimes() = Fixture().use { fixture ->
        Session().use { session ->
            onMain {
                session.main.documentState.viewMode = FileBrowserViewMode.DETAILED
                session.main.documentState.sortField = FileSortField.NAME
                session.main.documentState.sortDirection = FileSortDirection.ASCENDING
                invokePage(session.activity, R.id.nav_library)
                session.player.openQueue(listOf(PlayerTrack(fixture.target.path, fixture.target.name, false)), 0)
            }
            compose.waitUntil(15_000L) {
                mainValue { session.player.state.value.let { it.ready && it.playing && it.positionMs > 250L } }
            }
            onMain { session.player.expand(); session.player.openTrackInfoDialog() }
            compose.waitUntil(10_000L) { mainValue { session.player.state.value.trackInfo?.title == "Located title" } }
            compose.onNodeWithTag("player-info-open-location").assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("player-info-open-location").performClick()
            val firstToken = assertThreePulses(session, fixture.target)
            assertPlaybackAndSourcesUnchanged(session, fixture)

            // Locate again while already in this directory. A new request must flash again.
            compose.mainClock.autoAdvance = true
            onMain {
                session.main.mediaSelection.select(fixture.target.path)
                session.main.mediaSelection.optionsOpen = true
            }
            compose.onNodeWithTag("media-selection-options-dialog").assertIsDisplayed()
            compose.onNodeWithTag("media-selection-action-list").performScrollToNode(
                hasTestTag("media-selection-action:LOCATE_DIRECTORY")
            )
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("media-selection-action:LOCATE_DIRECTORY").performClick()
            val secondToken = assertThreePulses(session, fixture.target)
            assertNotEquals("Repeated location needs a fresh animation request", firstToken, secondToken)
            assertPlaybackAndSourcesUnchanged(session, fixture)
            assertFalse(mainValue { session.main.mediaSelection.active })
            compose.mainClock.autoAdvance = true
        }
    }

    private fun assertThreePulses(session: Session, target: File): Long {
        advanceUntil {
            mainValue {
                screen(session.activity).let { it.selectedTopLevelItem == R.id.nav_directory &&
                    it.currentDirectory?.absolutePath == target.parentFile?.absolutePath &&
                    !it.isDirectoryLoading && it.directoryRevealRequest?.path == target.path }
            }
        }
        val token = requireNotNull(mainValue { screen(session.activity).directoryRevealRequest?.token })
        val tag = "directory-file:${target.path}"
        for (expected in 1..3) {
            advanceUntil { pulse(tag) == expected && opacity(tag) > 0.9f }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            compose.runOnIdle {
                assertEquals("The request remains active through all three pulses", token,
                    screen(session.activity).directoryRevealRequest?.token)
                assertFalse(session.player.expanded.value)
                assertFalse(session.player.state.value.showTrackInfoDialog)
            }
        }
        advanceUntil { mainValue { screen(session.activity).directoryRevealRequest == null } }
        advanceUntil { pulse(tag) == 0 }
        compose.mainClock.advanceTimeBy(4_000L)
        assertEquals("Highlight does not start again after its third pulse", 0, pulse(tag))
        return token
    }

    private fun assertPlaybackAndSourcesUnchanged(session: Session, fixture: Fixture) {
        onMain {
            assertEquals(fixture.target.path, session.player.state.value.currentTrack?.path)
            assertTrue(session.player.state.value.playing)
            assertEquals(fixture.target.path, MPVLib.getPropertyString("path"))
        }
        assertTrue(FavoriteDirectoryStore(app).load().paths.isEmpty())
        assertFalse(MediaLibraryRepository.getInstance(app).isIndexedTrack(fixture.target.path))
    }

    private fun pulse(tag: String): Int = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(MediaTrackRevealPulse) ?: 0

    private fun opacity(tag: String): Float = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(MediaTrackRevealOpacity) ?: 0f

    private fun advanceUntil(condition: () -> Boolean) {
        compose.waitUntil(15_000L) { compose.mainClock.advanceTimeByFrame(); condition() }
    }

    private inner class Fixture : Closeable {
        val root = File(app.cacheDir, "main-media-location-${System.nanoTime()}").apply { check(mkdirs()) }
        val target: File
        private val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val keys = listOf("player_mode_enabled", "default_play_mode", "default_speed", "resume_playback",
            "eq_enabled", "show_loudness_waveform", "end_behavior_enabled")
        private val previousPlayer = keys.associateWith { preferences.all[it] }
        private val directoryStore = FavoriteDirectoryStore(app)
        private val previousDirectories = directoryStore.load()
        private val repository = MediaLibraryRepository.getInstance(app)
        private val previousQueue = runBlocking { PlayerLibraryWriter.execute { repository.getCurrentQueue() } }

        init {
            check(preferences.edit().putBoolean("player_mode_enabled", true)
                .putString("default_play_mode", PlayerPlayMode.SEQUENCE.name).putFloat("default_speed", 1f)
                .putBoolean("resume_playback", false).putBoolean("eq_enabled", false)
                .putBoolean("show_loudness_waveform", false).putBoolean("end_behavior_enabled", false).commit())
            directoryStore.replace(emptySet())
            repeat(40) { wave("track-${it.toString().padStart(3, '0')}.wav", seconds = 1) }
            target = wave("zz-located-song.wav", seconds = 60)
            MediaTagAudioRepository.write(target,
                MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "Located title"))
        }

        private fun wave(name: String, seconds: Int): File = File(root, name).apply {
            val rate = 8_000
            val count = rate * seconds
            writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(count * 2)
            }.array())
        }

        override fun close() {
            compose.mainClock.autoAdvance = true
            runBlocking { PlayerLibraryWriter.execute {
                repository.removePlaybackHistory(target.path)
                repository.saveCurrentQueue(previousQueue.first.map { it.path }, previousQueue.second)
            } }
            directoryStore.replace(previousDirectories.paths, previousDirectories.mediaLibraryPaths)
            MediaLibraryFileObserver.syncRoots(app, previousDirectories.mediaLibraryDirectories)
            runBlocking { MediaLibraryFileObserver.reconcile(app, emptyList()) }
            repository.removeDirectoryFromLibrary(root, previousDirectories.mediaLibraryDirectories)
            val editor = preferences.edit()
            previousPlayer.forEach { (key, value) -> restore(editor, key, value) }
            root.listFiles().orEmpty().forEach { editor.remove("last_position_${it.path}"); PlayerTrackMetadataReader.invalidate(it.path) }
            check(editor.commit())
            root.deleteRecursively()
        }
    }

    private inner class Session : Closeable {
        private val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        lateinit var player: PlayerViewModel
        lateinit var main: MainViewModel
        init {
            scenario.onActivity {
                activity = it
                player = ViewModelProvider(it)[PlayerViewModel::class.java]
                main = ViewModelProvider(it)[MainViewModel::class.java]
            }
        }
        override fun close() {
            compose.mainClock.autoAdvance = true
            try { onMain { player.closeSession() } }
            finally { scenario.close() }
        }
    }

    private fun invokePage(activity: MainActivity, page: Int) = MainActivity::class.java
        .getDeclaredMethod("showTopLevelPage", Int::class.javaPrimitiveType!!).apply { isAccessible = true }
        .invoke(activity, page)

    private fun screen(activity: MainActivity): MainActivityScreenState = MainActivity::class.java
        .getDeclaredMethod("getScreenState").apply { isAccessible = true }.invoke(activity) as MainActivityScreenState

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun <T> mainValue(action: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(action()) }
        return value.get()
    }

    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            else -> error("Unexpected fixture preference for $key")
        }
    }
}

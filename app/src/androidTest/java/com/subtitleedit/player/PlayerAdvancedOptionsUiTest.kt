package com.subtitleedit.player

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** Exercises the real player settings without starting native playback or changing device volume. */
@RunWith(AndroidJUnit4::class)
class PlayerAdvancedOptionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: Fixture? = null

    @After fun removeFixture() {
        fixture?.let { fixture ->
            compose.runOnIdle { fixture.store.clear() }
            runBlocking { PlayerLibraryWriter.execute {} }
            fixture.repository.close()
            compose.activity.deleteDatabase(fixture.database)
            val editor = fixture.preferences.edit()
            fixture.savedPreferences.forEach { (key, value) -> restore(editor, key, value) }
            editor.remove("last_position_${fixture.file.path}")
            assertTrue("Restore original player preferences", editor.commit())
            fixture.directory.deleteRecursively()
        }
    }

    @Test fun advancedOptionsDefaultOffAndTheSettingsSwitchControlsTheQuickAction() {
        val fixture = showPlayer()
        compose.onNodeWithTag("player-quick-end-behavior").assertDoesNotExist()
        listOf(PlayerPlayMode.SHUFFLE, PlayerPlayMode.SEQUENCE, PlayerPlayMode.REPEAT_ONE,
            PlayerPlayMode.REPEAT_ALL).forEach { mode ->
            compose.onNodeWithTag("player-mode-chip").performClick()
            assertBehavior(fixture, mode, PlayerEndBehavior())
        }
        compose.runOnIdle { fixture.model.openPanel(PlayerPanel.SETTINGS) }

        val advanced = compose.onNodeWithTag("player-settings-advanced-options")
        advanced.performScrollTo().assertIsOff()
        compose.onNodeWithTag("player-settings-mode-REPEAT_ALL").assertIsDisplayed()
        compose.onNodeWithTag("player-settings-mode-SHUFFLE").assertIsDisplayed()
        compose.onNodeWithTag("player-settings-mode-SEQUENCE").assertIsDisplayed()
        compose.onNodeWithTag("player-settings-mode-REPEAT_ONE").assertIsDisplayed()
        val modeBounds = compose.onNodeWithTag("player-settings-mode-REPEAT_ALL")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Advanced options appear below the default playback modes",
            advanced.fetchSemanticsNode().boundsInRoot.top >= modeBounds.bottom)

        advanced.performClick().assertIsOn()
        compose.onNodeWithTag("player-settings-mode-SEQUENCE").assertDoesNotExist()
        compose.onNodeWithTag("player-settings-mode-REPEAT_ONE").assertDoesNotExist()
        compose.runOnIdle { fixture.model.closePanel() }
        compose.onNodeWithTag("player-quick-end-behavior").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(fixture.model.state.value.showEndBehaviorDialog)
            fixture.model.closeEndBehaviorDialog()
            fixture.model.openPanel(PlayerPanel.SETTINGS)
        }
        advanced.performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("player-settings-mode-SEQUENCE").assertIsDisplayed()
        compose.onNodeWithTag("player-settings-mode-REPEAT_ONE").assertIsDisplayed()
        compose.runOnIdle {
            fixture.model.closePanel()
            fixture.model.openEndBehaviorDialog()
            assertFalse("Disabled advanced options cannot open their dialog",
                fixture.model.state.value.showEndBehaviorDialog)
        }
        compose.onNodeWithTag("player-quick-end-behavior").assertDoesNotExist()
    }

    @Test fun simpleModesAndTheAdvancedSwitchPreserveIndependentEndActions() {
        val fixture = showPlayer()
        val advancedRules = PlayerEndBehavior(
            enabled = true,
            trackAction = PlayerTrackEndAction.REPEAT_TRACK,
            queueAction = PlayerQueueEndAction.NEXT_QUEUE,
            wrapQueues = true,
            reshuffle = false
        )
        compose.runOnIdle { fixture.model.applyEndBehavior(PlayerPlayMode.REPEAT_ALL, advancedRules) }
        compose.onNodeWithTag("player-mode-chip").performClick()
        assertBehavior(fixture, PlayerPlayMode.SHUFFLE, advancedRules)
        compose.onNodeWithTag("player-mode-chip").performClick()
        assertBehavior(fixture, PlayerPlayMode.REPEAT_ALL, advancedRules)

        compose.runOnIdle { fixture.model.openPanel(PlayerPanel.SETTINGS) }
        compose.onNodeWithTag("player-settings-mode-SHUFFLE").performScrollTo().performClick()
        assertBehavior(fixture, PlayerPlayMode.SHUFFLE, advancedRules)
        val advanced = compose.onNodeWithTag("player-settings-advanced-options")
        advanced.performScrollTo().performClick().assertIsOff()
        assertBehavior(fixture, PlayerPlayMode.SHUFFLE, advancedRules.copy(enabled = false))
        compose.onNodeWithTag("player-settings-mode-REPEAT_ONE").performScrollTo().performClick()
        assertBehavior(fixture, PlayerPlayMode.REPEAT_ONE, advancedRules.copy(enabled = false))
        advanced.performScrollTo().performClick().assertIsOn()
        assertBehavior(fixture, PlayerPlayMode.REPEAT_ALL, advancedRules)
        compose.runOnIdle { fixture.model.closePanel() }
        compose.onNodeWithTag("player-quick-end-behavior").assertIsDisplayed()
    }

    private fun assertBehavior(fixture: Fixture, mode: PlayerPlayMode, behavior: PlayerEndBehavior) {
        compose.runOnIdle {
            assertEquals(mode, fixture.model.state.value.mode)
            assertEquals(behavior, fixture.model.state.value.endBehavior)
            val persisted = PlayerPreferences(fixture.preferences)
            assertEquals(mode, persisted.defaultPlayMode)
            assertEquals(behavior, persisted.endBehavior)
        }
    }

    private fun showPlayer(): Fixture {
        val fixture = Fixture().also { this.fixture = it }
        compose.runOnIdle {
            fixture.model.configure(listOf(PlayerTrack(fixture.file.path, fixture.file.name, false)), 0)
        }
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) { PlayerScreen(fixture.model, embedded = true) }
            }
        }
        compose.waitForIdle()
        return fixture
    }

    private inner class Fixture {
        val database = "player-advanced-options-ui-${System.nanoTime()}.db"
        val directory = File(compose.activity.cacheDir, "player-advanced-options-ui-${System.nanoTime()}")
            .apply { check(mkdirs()) }
        val file = File(directory, "fixture.wav").apply { writeBytes(byteArrayOf(0)) }
        val repository = MediaLibraryRepository.open(compose.activity, database) { metadata, duration ->
            MediaLibraryTrack(metadata.path, metadata.file.name, durationMs = duration)
        }
        val store = ViewModelStore()
        val preferences: SharedPreferences = compose.activity
            .getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val changedKeys = listOf(
            "default_play_mode", "end_behavior_enabled", "track_end_action", "queue_end_action",
            "queue_end_wrap", "queue_end_reshuffle", "resume_playback", "show_loudness_waveform", "eq_enabled"
        )
        val savedPreferences = changedKeys.associateWith { preferences.all[it] }

        init {
            val editor = preferences.edit()
            changedKeys.forEach { editor.remove(it) }
            assertTrue(editor.putBoolean("resume_playback", false)
                .putBoolean("show_loudness_waveform", false).putBoolean("eq_enabled", false).commit())
        }

        private val stream = object : PlayerSystemVolume {
            var value = SystemMediaVolume(50, 0, 100)
            override fun read() = value
            override fun setIndex(index: Int) {
                value = value.copy(index = index.coerceIn(0, 100), muted = index == 0)
            }
        }
        val model = compose.runOnIdle {
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(compose.activity.application as Application, repository, stream) as T
            })[PlayerViewModel::class.java]
        }
    }

    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            else -> error("Unsupported preference for $key")
        }
    }
}

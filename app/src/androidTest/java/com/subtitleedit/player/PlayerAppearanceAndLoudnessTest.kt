package com.subtitleedit.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.subtitleedit.MediaConvertMetadataSelection
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.MediaTagEditorActivity
import com.subtitleedit.MediaTagEditorViewModel
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.util.AppThemeMode
import com.subtitleedit.util.SettingsManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sin

/** Uses native playback samples, rendered pixels and the existing tag-editor Activity. */
@RunWith(AndroidJUnit4::class)
class PlayerAppearanceAndLoudnessTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun realLoudnessFollowsSilenceAmplitudeMuteAndTheWaveformSwitch() {
        Fixture("levels", seconds = 16.0) { seconds ->
            when {
                seconds < 2.0 -> 0
                seconds < 6.0 -> 1_000
                seconds < 10.0 -> 10_000
                else -> 0
            }
        }.use { fixture ->
            openPlayer(fixture.track).use { session ->
                val model = session.model
                awaitState(model, "native audio starts") { it.ready && it.playing && it.positionMs > 100L }
                onMain { model.pause(); model.seekTo(0L); model.play() }
                val initialSilence = medianSamples(model, 200L..1_100L)
                val quiet = medianSamples(model, 2_800L..4_300L)
                val loud = medianSamples(model, 6_800L..8_300L)
                val finalSilence = medianSamples(model, 11_000L..12_500L)
                assertEquals("Initial silence must produce zero amplitude", 0f, initialSilence, 0.01f)
                assertTrue("The quiet tone must produce a real level: $quiet", quiet > 0.1f)
                assertTrue("The louder PCM tone must increase the meter: quiet=$quiet loud=$loud", loud > quiet + 0.15f)
                assertEquals("The silent tail must return to zero", 0f, finalSilence, 0.01f)

                onMain { model.seekTo(7_000L) }
                awaitState(model, "seek back to the loud tone") { it.playing && it.positionMs in 7_100L..9_000L && it.loudness > 0.1f }
                val beforeMute = state(model).loudnessSampleId
                onMain { model.setVolume(0) }
                awaitState(model, "local volume mute must zero the measured output") {
                    it.playing && it.loudnessSampleId > beforeMute && it.loudness == 0f
                }
                onMain {
                    model.setVolume(100)
                    model.applyPreset(PlayerEqualizer.PRESETS.first { it.id == "bass" })
                    model.seekTo(7_000L)
                }
                awaitState(model, "loudness survives equalizer processing") { it.playing && it.loudness > 0.1f }
                await("native EQ and analysis filters coexist") {
                    nativeFilters().let { it.contains("firequalizer") && it.contains(PlayerLoudness.FILTER_LABEL) }
                }
                captureIfRequested("player-waveform-preview.png")
                onMain { model.setShowLoudnessWaveform(false) }
                await("disabling the waveform removes only its analysis filter") {
                    nativeFilters().let { it.contains("firequalizer") && !it.contains(PlayerLoudness.FILTER_LABEL) }
                }
                assertFalse(state(model).showLoudnessWaveform)
                assertFalse(fixture.preferences.getBoolean("show_loudness_waveform", true))
                assertEquals(0f, state(model).loudness, 0f)
                Thread.sleep(250L)
                val disabledSampleId = state(model).loudnessSampleId
                Thread.sleep(350L)
                assertEquals("Disabled analysis must stop native polling", disabledSampleId, state(model).loudnessSampleId)

                onMain { model.setShowLoudnessWaveform(true); model.seekTo(7_000L) }
                awaitState(model, "re-enabling resumes real measurements") {
                    it.showLoudnessWaveform && it.loudnessSampleId > disabledSampleId && it.loudness > 0.1f
                }
                assertTrue(fixture.preferences.getBoolean("show_loudness_waveform", false))
                onMain { model.pause() }
                awaitState(model, "paused audio has no loudness") { !it.playing && it.loudness == 0f }
            }
        }
    }

    @Test fun lightAndDarkThemesRenderAndExistingTagEditorReturnsToThePausedPosition() {
        val oldNightMode = mainValue { AppCompatDelegate.getDefaultNightMode() }
        val settings = SettingsManager.getInstance(app)
        val oldTheme = settings.getThemeMode()
        try {
            onMain { settings.setThemeMode(SettingsManager.THEME_LIGHT); AppThemeMode.apply(app, SettingsManager.THEME_LIGHT) }
            Fixture("appearance", seconds = 30.0) { 3_000 }.use { fixture ->
                openPlayer(fixture.track).use { session ->
                    val model = session.model
                    awaitState(model, "appearance fixture starts") { it.ready && it.playing && it.positionMs > 200L }
                    await("light theme reaches the player window") { windowLuminance(session.activity) > 0.6f }
                    Thread.sleep(200L)
                    val light = renderedBackgroundLuminance("player-light-preview.png")
                    onMain { settings.setThemeMode(SettingsManager.THEME_DARK); AppThemeMode.apply(app, SettingsManager.THEME_DARK) }
                    session.activity = resumedActivity(PlayerActivity::class.java)
                    await("dark theme reaches the player window") { windowLuminance(session.activity) < 0.2f }
                    Thread.sleep(200L)
                    val dark = renderedBackgroundLuminance("player-dark-preview.png")
                    assertTrue("Player pixels must follow the theme: light=$light dark=$dark", light > dark + 0.4f)

                    onMain { model.openPanel(PlayerPanel.SETTINGS) }
                    Thread.sleep(400L)
                    captureIfRequested("player-themed-settings-preview.png")
                    onMain { model.closePanel(); model.pause(); model.seekTo(4_000L) }
                    awaitState(model, "prepare a paused position for tag editing") {
                        it.ready && !it.playing && it.positionMs in 3_800L..4_200L
                    }
                    onMain { session.activity.openTagEditor() }
                    val editor = resumedActivity(MediaTagEditorActivity::class.java)
                    try {
                        assertEquals(listOf(fixture.track.path), mainValue {
                            editor.intent.getStringArrayListExtra(MediaTagEditorActivity.EXTRA_FILE_PATHS)
                        })
                        val tagModel = mainValue { ViewModelProvider(editor)[MediaTagEditorViewModel::class.java] }
                        await("the reused editor reads the selected file") {
                            mainValue { tagModel.state.value.selection != MediaConvertMetadataSelection.READING }
                        }
                        assertEquals(listOf(fixture.track.name), mainValue { tagModel.state.value.fileNames })
                        assertTrue("The generated WAV must be editable through the existing tag implementation",
                            mainValue { tagModel.state.value.selection.editable })
                    } finally {
                        onMain { editor.onBackPressedDispatcher.onBackPressed() }
                    }
                    session.activity = resumedActivity(PlayerActivity::class.java)
                    awaitState(model, "returning reloads the original paused position") {
                        it.ready && !it.playing && it.positionMs in 3_800L..4_200L
                    }
                    Thread.sleep(400L)
                    assertTrue("Tag editor must preserve pause intent", !state(model).playing && abs(state(model).positionMs - 4_000L) < 250L)
                    assertNull(state(model).error)

                    onMain { model.play() }
                    awaitState(model, "resume before saving tags") { it.playing && it.positionMs > 4_300L }
                    val savedPosition = state(model).positionMs
                    onMain { session.activity.openTagEditor() }
                    val savingEditor = resumedActivity(MediaTagEditorActivity::class.java)
                    try {
                        val tagModel = mainValue { ViewModelProvider(savingEditor)[MediaTagEditorViewModel::class.java] }
                        await("tag editor is ready to save") { mainValue { tagModel.state.value.selection.editable } }
                        onMain {
                            tagModel.setMetadata(tagModel.state.value.metadata.withValue(MediaConvertMetadataField.TITLE, "Player tag test"))
                            tagModel.save()
                        }
                        session.activity = resumedActivity(PlayerActivity::class.java)
                        awaitState(model, "saving tags resumes the previously playing file") {
                            it.ready && it.playing && it.positionMs > savedPosition + 150L
                        }
                        assertEquals("Player tag test", mainValue { MPVLib.getPropertyString("metadata/by-key/title") })
                    } finally {
                        if (!mainValue { savingEditor.isDestroyed }) onMain { savingEditor.finish() }
                    }
                }
            }
        } finally {
            onMain {
                settings.setThemeMode(oldTheme)
                AppCompatDelegate.setDefaultNightMode(oldNightMode)
            }
        }
    }

    private inner class Fixture(name: String, seconds: Double, amplitude: (Double) -> Int) : Closeable {
        val root = File(app.cacheDir, "player-$name-${System.nanoTime()}").apply { mkdirs() }
        val track = File(root, "$name.wav").apply { writeBytes(pcmWav(seconds, amplitude)) }
        val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val keys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled", "eq_gains", "show_loudness_waveform")
        private val saved = keys.associateWith { preferences.all[it] }
        init {
            assertTrue(preferences.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false)
                .putBoolean("eq_enabled", false).putBoolean("show_loudness_waveform", true).commit())
        }
        override fun close() {
            val editor = preferences.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            editor.remove("last_position_${track.path}")
            assertTrue("Restore original player preferences", editor.commit())
            root.deleteRecursively()
        }
    }

    private fun openPlayer(track: File): Session {
        val intent = Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(track.path))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { app.startActivity(intent) }
        return Session(resumedActivity(PlayerActivity::class.java))
    }

    private inner class Session(var activity: PlayerActivity) : Closeable {
        val model = mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] }
        override fun close() {
            onMain { activity.finish() }
            await("player closes and releases native resources") { mainValue { activity.isDestroyed } }
        }
    }

    private fun medianSamples(model: PlayerViewModel, window: LongRange): Float {
        val samples = mutableListOf<Float>()
        var lastId = -1L
        await("collect real loudness within $window", window.last + 5_000L) {
            val sample = state(model)
            sample.error?.let { throw AssertionError("Native measurement failed: $it") }
            if (sample.positionMs in window && sample.loudnessSampleId != lastId) {
                samples += sample.loudness
                lastId = sample.loudnessSampleId
            }
            sample.positionMs > window.last
        }
        assertTrue("Expected measured samples within $window, got ${samples.size}", samples.size >= 3)
        return samples.sorted()[samples.size / 2]
    }

    private fun windowLuminance(activity: PlayerActivity): Float = mainValue {
        val color = (activity.window.decorView.background as? ColorDrawable)?.color
        if (color != null) Color.luminance(color) else if (
            activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) 0f else 1f
    }

    private fun renderedBackgroundLuminance(name: String): Float {
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("No screenshot")
        return try {
            saveIfRequested(screenshot, name)
            Color.luminance(screenshot.getPixel(screenshot.width / 20, screenshot.height / 4))
        } finally { screenshot.recycle() }
    }

    private fun captureIfRequested(name: String) {
        if (!screenshotsRequested()) return
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        try { saveIfRequested(screenshot, name) } finally { screenshot.recycle() }
    }

    private fun saveIfRequested(screenshot: Bitmap, name: String) {
        if (!screenshotsRequested()) return
        File(app.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun screenshotsRequested() = InstrumentationRegistry.getArguments().getString("playerScreenshot") == "true"
    private fun nativeFilters() = mainValue { MPVLib.getPropertyString("af") }.orEmpty()
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(action()) }
        return result.get()
    }
    private fun <T : Activity> resumedActivity(type: Class<T>): T {
        var result: T? = null
        await("${type.simpleName} resumes") {
            result = mainValue { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { type.isInstance(it) }?.let(type::cast) }
            result != null
        }
        return result!!
    }
    private fun awaitState(model: PlayerViewModel, label: String, predicate: (PlayerUiState) -> Boolean) {
        await(label) {
            val sample = state(model)
            sample.error?.let { throw AssertionError("$label: $it") }
            predicate(sample)
        }
    }
    private fun await(label: String, timeoutMs: Long = 12_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(75L)
        }
        throw AssertionError("Timed out: $label")
    }

    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            else -> error("Unsupported preference type for $key")
        }
    }

    private fun pcmWav(seconds: Double, amplitude: (Double) -> Int): ByteArray {
        val sampleRate = 44_100
        val sampleCount = (sampleRate * seconds).toInt()
        val result = ByteBuffer.allocate(44 + sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        result.put("RIFF".toByteArray()); result.putInt(36 + sampleCount * 2)
        result.put("WAVEfmt ".toByteArray()); result.putInt(16)
        result.putShort(1); result.putShort(1); result.putInt(sampleRate); result.putInt(sampleRate * 2)
        result.putShort(2); result.putShort(16); result.put("data".toByteArray()); result.putInt(sampleCount * 2)
        repeat(sampleCount) { index ->
            val time = index.toDouble() / sampleRate
            result.putShort((sin(2 * Math.PI * 440.0 * time) * amplitude(time)).toInt().toShort())
        }
        return result.array()
    }
}

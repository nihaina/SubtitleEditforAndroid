package com.subtitleedit.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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

/** Runs against native mpv without waiting for the intentionally continuous Compose animation. */
@RunWith(AndroidJUnit4::class)
class PlayerSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun playbackPauseSeekTimersSubtitlesNaturalEndAndReopeningWork() {
        val root = File(app.cacheDir, "player-smoke-${System.nanoTime()}").apply { mkdirs() }
        val settings = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val changedKeys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled", "eq_gains", "subtitle_font_size")
        val saved = changedKeys.associateWith { settings.all[it] }
        val tracks = listOf(
            File(root, "player-first.wav").apply { writeBytes(sineWav(440.0, 30.0)) },
            File(root, "player-second.wav").apply { writeBytes(sineWav(660.0, 30.0)) },
            File(root, "player-third.wav").apply { writeBytes(sineWav(880.0, 30.0)) }
        )
        File(root, "player-first.srt").writeText("1\n00:00:00,000 --> 00:00:29,900\nplayer subtitle\n")
        try {
            assertTrue(settings.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false)
                .putBoolean("eq_enabled", false).putInt("subtitle_font_size", 16).commit())
            openPlayer(tracks).use { session ->
                val model = session.model
                awaitState(model, "initial audio must actually advance") { it.ready && it.playing && it.positionMs > 300L }
                assertEquals("player-first.wav", state(model).currentTrack?.name)
                assertTrue(abs(state(model).durationMs - 30_000L) < 100L)

                onMain { model.pause() }
                awaitState(model, "pause must reach the engine") { !it.playing }
                Thread.sleep(250)
                val pausedAt = state(model).positionMs
                Thread.sleep(700)
                assertTrue("Paused media must not advance", abs(state(model).positionMs - pausedAt) < 150L)
                onMain { model.seekTo(5_000L, fromGesture = true) }
                assertEquals(5_000L, state(model).seekPreviewMs)
                Thread.sleep(250)
                assertTrue("Dragging must not send seeks before commit", abs(state(model).positionMs - pausedAt) < 150L)
                onMain { model.clearSeekPreview(); model.seekTo(4_000L) }
                awaitState(model, "committed seek") { it.positionMs in 3_800L..4_200L }

                val sidecar = state(model).subtitleOptions.single { it.kind == PlayerSubtitleKind.EXTERNAL }
                onMain { model.selectSubtitle(sidecar) }
                awaitState(model, "sidecar text and ID") { it.selectedSubtitleId == sidecar.id && it.subtitleText.contains("player subtitle") }
                session.recreate()
                awaitState(model, "recreated view retains paused position and subtitle") {
                    it.ready && !it.playing && it.selectedSubtitleId == sidecar.id && it.positionMs in 3_800L..4_200L
                }
                onMain { model.selectSubtitle(model.state.value.subtitleOptions.first { it.kind == PlayerSubtitleKind.NONE }) }
                awaitState(model, "disable subtitles") { it.selectedSubtitleId == "none" && it.subtitleText.isEmpty() }
                onMain { model.play() }
                awaitState(model, "play after seeking") { it.playing && it.positionMs > 4_500L }
                onMain { model.setSpeed(2f) }
                Thread.sleep(600)
                assertEquals(2.0, mainValue { com.subtitleedit.mpv.MPVLib.getPropertyDouble("speed") } ?: -1.0, 0.01)
                val beforeFast = state(model).positionMs
                Thread.sleep(1_200)
                val fastAdvance = state(model).positionMs - beforeFast
                assertTrue("2x playback must advance faster: $fastAdvance ms", fastAdvance > 1_700L)
                onMain { model.setSpeed(1f); model.applyPreset(PlayerEqualizer.PRESETS.first { it.id == "bass" }) }
                Thread.sleep(400)
                val nativeFilter = mainValue { com.subtitleedit.mpv.MPVLib.getPropertyString("af") }.orEmpty()
                assertTrue("Equalizer must reach the native audio chain: $nativeFilter", nativeFilter.contains("firequalizer"))
                onMain { model.setEqualizer(PlayerEqualizerState()) }
                assertNull(state(model).error)

                onMain { model.setSleepCountdown(10) }
                assertEquals(10, state(model).sleepTimer.durationMinutes)
                assertTrue(state(model).sleepRemainingMs in 599_000L..600_000L)
                onMain { model.openPanel(PlayerPanel.TIMER) }
                Thread.sleep(500)
                captureIfRequested("player-timer-preview.png")
                onMain { model.closePanel() }
                onMain { model.clearSleepTimer(); model.scheduleStart(20) }
                awaitState(model, "scheduled play pauses current media") { !it.playing }
                assertFalse(state(model).sleepTimer.active)
                assertEquals(20, state(model).scheduledStartMinutes)
                assertNotNull(state(model).scheduledStartRemainingMs)
                onMain { model.cancelScheduledStart(); model.setSubtitleFontSize(22); model.setResumePlayback(true) }
                assertNull(state(model).scheduledStartRemainingMs)
                assertEquals(22, state(model).subtitleFontSize)
                assertTrue(state(model).resumePlayback)
                assertEquals(22, settings.getInt("subtitle_font_size", -1))
                onMain { model.openPanel(PlayerPanel.SETTINGS) }
                Thread.sleep(500)
                captureIfRequested("player-settings-preview.png")
                onMain { model.closePanel() }

                onMain { model.seekTo(30_000L) }
                Thread.sleep(700)
                assertEquals("Paused seek to EOF must not advance queue", 0, state(model).currentIndex)
                assertFalse("Paused seek to EOF must not start audio", state(model).playing)
                onMain { model.play() }
                awaitState(model, "play at EOF restarts current media") { it.playing && it.positionMs in 200L..2_000L }
                onMain { model.setResumePlayback(false); model.next() }
                awaitState(model, "manual next") { it.currentIndex == 1 && it.ready && it.playing && it.positionMs > 250L }
                Thread.sleep(500)
                assertEquals("Replacing media must not skip another track", 1, state(model).currentIndex)
                onMain { model.playIndex(0) }
                awaitState(model, "return to first track") { it.currentIndex == 0 && it.ready && it.positionMs > 100L }
                onMain { model.pause(); model.seekTo(29_600L); model.play() }
                awaitState(model, "natural EOF advances once") { it.currentIndex == 1 && it.ready && it.playing && it.positionMs > 250L }
                Thread.sleep(800)
                assertEquals("EOF consumed exactly once", 1, state(model).currentIndex)
                assertNull(state(model).error)
                captureIfRequested()
                assertTrue(tracks[2].delete())
                onMain { model.playIndex(2) }
                awaitState(model, "missing queued file reports an error") { it.error != null && !it.playing }
                assertEquals("A command error must also pause the native output", true,
                    mainValue { com.subtitleedit.mpv.MPVLib.getPropertyBoolean("pause") })
            }
            openPlayer(tracks).use { session ->
                awaitState(session.model, "player works after release/reopen") { it.currentIndex == 0 && it.ready && it.playing && it.positionMs > 300L }
                assertNull(state(session.model).error)
            }
        } finally {
            val editor = settings.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            tracks.forEach { editor.remove("last_position_${it.path}") }
            assertTrue("Restore original player settings", editor.commit())
            root.deleteRecursively()
        }
    }

    @Test fun videoFramesAndEmbeddedSubtitlesReachThePlayerSurface() {
        val root = File(app.cacheDir, "player-video-${System.nanoTime()}").apply { mkdirs() }
        val video = File(root, "video.mkv")
        val subtitles = File(root, "embedded.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:19,900\nembedded subtitle\n")
        }
        val settings = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val keys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled")
        val saved = keys.associateWith { settings.all[it] }
        try {
            val generated = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-f", "lavfi", "-i", "color=c=red:s=320x180:r=10:d=20",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=20", "-i", subtitles.path,
                "-map", "0:v", "-map", "1:a", "-map", "2:s", "-c:v", "mpeg4", "-q:v", "5",
                "-c:a", "aac", "-c:s", "srt", "-shortest", video.path
            ))
            assertTrue(generated.getAllLogsAsString(), com.arthenica.ffmpegkit.ReturnCode.isSuccess(generated.getReturnCode()))
            assertTrue(settings.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false)
                .putBoolean("eq_enabled", false).commit())
            openPlayer(listOf(video)).use { session ->
                val model = session.model
                awaitState(model, "video playback begins") { it.isVideo && it.ready && it.playing && it.positionMs > 500L }
                assertEquals(320, mainValue { com.subtitleedit.mpv.MPVLib.getPropertyInt("video-params/w") })
                assertEquals("gpu-next", mainValue { com.subtitleedit.mpv.MPVLib.getPropertyString("current-vo") })
                val embedded = state(model).subtitleOptions.single { it.kind == PlayerSubtitleKind.INTERNAL }
                onMain { model.selectSubtitle(embedded); model.setControlsVisible(false) }
                awaitState(model, "embedded subtitle is selected") {
                    it.selectedSubtitleId == embedded.id && it.subtitleText.contains("embedded subtitle")
                }
                Thread.sleep(350)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull(screenshot)
                try {
                    val pixel = screenshot!!.getPixel(screenshot.width / 2, screenshot.height / 2)
                    assertTrue("The rendered viewport must contain the generated red video frame",
                        android.graphics.Color.red(pixel) > 150 && android.graphics.Color.green(pixel) < 100 && android.graphics.Color.blue(pixel) < 100)
                } finally { screenshot?.recycle() }
            }
        } finally {
            val editor = settings.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            editor.remove("last_position_${video.path}")
            assertTrue(editor.commit())
            root.deleteRecursively()
        }
    }

    @Test fun oneMinuteTimersStopAndStartWithTheScreenOff() {
        val root = File(app.cacheDir, "player-timer-${System.nanoTime()}").apply { mkdirs() }
        val track = File(root, "timer.wav").apply { writeBytes(sineWav(440.0, 180.0)) }
        val settings = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val keys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled")
        val saved = keys.associateWith { settings.all[it] }
        val wasInteractive = app.getSystemService(android.os.PowerManager::class.java).isInteractive
        try {
            assertTrue(settings.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false)
                .putBoolean("eq_enabled", false).commit())
            openPlayer(listOf(track)).use { session ->
                val model = session.model
                awaitState(model, "timer media starts") { it.ready && it.playing && it.positionMs > 300L }
                onMain { model.setSleepCountdown(1) }
                shell("input keyevent 223")
                awaitState(model, "one-minute stop expires with screen off", 70_000L) {
                    !it.sleepTimer.active && !it.playing
                }
                val stoppedAt = state(model).positionMs
                assertTrue("The timer must allow the minute of playback before stopping", stoppedAt > 35_000L)
                onMain { model.scheduleStart(1) }
                awaitState(model, "one-minute scheduled play starts with screen off", 70_000L) {
                    it.scheduledStartRemainingMs == null && it.playing && it.positionMs > stoppedAt + 150L
                }
                assertNull(state(model).error)
            }
        } finally {
            if (wasInteractive) { shell("input keyevent 224"); shell("wm dismiss-keyguard") }
            val editor = settings.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            editor.remove("last_position_${track.path}")
            assertTrue(editor.commit())
            root.deleteRecursively()
        }
    }

    @Test fun progressPersistenceKeepsHashCollidingMediaPathsIndependent() {
        val preferenceName = "player-preference-test-${System.nanoTime()}"
        val store = app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        try {
            val preferences = PlayerPreferences(store)
            val first = "/media/Aa.wav"
            val second = "/media/BB.wav"
            assertEquals(first.hashCode(), second.hashCode())
            preferences.saveLastPosition(first, 12_000L)
            preferences.saveLastPosition(second, 34_000L)
            assertEquals(12_000L, preferences.lastPosition(first))
            assertEquals(34_000L, preferences.lastPosition(second))
            preferences.saveLastPosition(first, -5L)
            assertEquals(0L, preferences.lastPosition(first))
            assertEquals(34_000L, preferences.lastPosition(second))
        } finally { app.deleteSharedPreferences(preferenceName) }
    }

    private fun openPlayer(tracks: List<File>): PlayerSession {
        val intent = Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(tracks.map { it.path }))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { app.startActivity(intent) }
        return PlayerSession(awaitActivity())
    }

    private inner class PlayerSession(var activity: PlayerActivity) : Closeable {
        val model: PlayerViewModel = mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] }
        fun recreate() {
            val previous = activity
            onMain { previous.recreate() }
            activity = awaitActivity(previous)
            assertSame(model, mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] })
        }
        override fun close() {
            onMain { activity.finish() }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000L
            while (!mainValue { activity.isDestroyed } && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue("Player must finish and release resources", mainValue { activity.isDestroyed })
        }
    }

    private fun awaitActivity(previous: PlayerActivity? = null): PlayerActivity {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val activity = mainValue { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlayerActivity>().firstOrNull { it !== previous } }
            if (activity != null) return activity
            Thread.sleep(50)
        }
        throw AssertionError("Player activity did not resume")
    }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }
    private fun <T> mainValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(action()) }
        return result.get()
    }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun awaitState(model: PlayerViewModel, label: String, timeoutMs: Long = 12_000L, matches: (PlayerUiState) -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var latest = state(model)
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            latest = state(model)
            if (matches(latest)) return
            if (latest.error != null) throw AssertionError("$label: ${latest.error}")
            Thread.sleep(100)
        }
        throw AssertionError("$label timed out: index=${latest.currentIndex}, ready=${latest.ready}, playing=${latest.playing}, position=${latest.positionMs}, subtitle=${latest.selectedSubtitleId}, text=${latest.subtitleText}, error=${latest.error}")
    }
    private fun captureIfRequested(name: String = "player-preview.png") {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            File(app.getExternalFilesDir(null), name).outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
    }
    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            else -> error("Unsupported preference value for $key")
        }
    }
    private fun sineWav(frequency: Double, seconds: Double): ByteArray {
        val sampleRate = 44100
        val samples = (sampleRate * seconds).toInt()
        val payload = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until samples) payload.putShort((sin(2 * Math.PI * frequency * index / sampleRate) * 2_000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(36 + samples * 2)
        header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
        header.putInt(16); header.putShort(1); header.putShort(1)
        header.putInt(sampleRate); header.putInt(sampleRate * 2)
        header.putShort(2); header.putShort(16)
        header.put("data".toByteArray()); header.putInt(samples * 2)
        return header.array() + payload.array()
    }
}

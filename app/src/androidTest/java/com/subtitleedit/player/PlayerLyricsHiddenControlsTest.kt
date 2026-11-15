package com.subtitleedit.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native playback plus real pointer drags verify recovery from a controls-hidden lyric view. */
@RunWith(AndroidJUnit4::class)
class PlayerLyricsHiddenControlsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun enteringLyricsFromHiddenArtworkAndReturningKeepControlsHidden() = withPlayer { session ->
        val model = session.model
        onMain { model.setControlsVisible(false) }
        await("artwork controls finish fading out") { findTag("player-top-overlay") == null }
        clickTag("player-audio-vinyl")
        awaitState(model, "artwork tap opens lyrics without opening playback controls") {
            it.lyricsVisible && !it.controlsVisible
        }
        await("hidden-controls lyric view fills the player") { findTag("player-lyrics-list") != null }
        assertNull("Opening lyrics alone must not offer the scroll-only restore button", findTag(RESTORE_TAG))
        pressBack()
        awaitState(model, "returning from lyrics keeps the independent controls state") {
            !it.lyricsVisible && !it.controlsVisible
        }
        await("artwork returns while controls remain absent") {
            findTag("player-audio-vinyl") != null && findTag("player-top-overlay") == null
        }
        assertFalse(mainValue { session.activity.isFinishing })
    }

    @Test fun realLyricDragsRevealRefreshAndFadeTheRestoreControl() = withPlayer { session ->
        val model = session.model
        hideControlsInLyrics(model)
        swipeLyrics()
        await("user lyric scrolling offers a restore control") { findTag(RESTORE_TAG) != null }
        captureIfRequested("player-lyrics-hidden-restore-button.png")
        Thread.sleep(1_000L)
        swipeLyrics()
        Thread.sleep(1_250L)
        assertNotNull("A second drag refreshes the two-second visibility deadline", findTag(RESTORE_TAG))
        await("restore control fades away after scrolling stops", timeoutMs = 6_000L) { findTag(RESTORE_TAG) == null }
        // The delayed automatic return to the active lyric must not resurrect this user-only control.
        Thread.sleep(900L)
        assertNull("Automatic following after the drag must not show a restore control", findTag(RESTORE_TAG))
        swipeLyrics()
        await("a later user drag can offer recovery again") { findTag(RESTORE_TAG) != null }
        clickTag(RESTORE_TAG)
        awaitState(model, "restore button reveals playback controls without leaving lyrics") {
            it.controlsVisible && it.lyricsVisible
        }
        await("playback controls are visible again") { findTag("player-top-overlay") != null }
        await("restore button is removed when playback controls are shown") { findTag(RESTORE_TAG) == null }
    }

    @Test fun rightEdgeRestoreControlKeepsTouchAndFeedbackOnTheVisibleCircle() = withPlayer { session ->
        val model = session.model
        hideControlsInLyrics(model)
        swipeLyrics()
        await("user scrolling reveals the right-edge restore control") { findTag(RESTORE_TAG) != null }
        val bounds = Rect().also { requireNotNull(findTag(RESTORE_TAG)).getBoundsInScreen(it) }
        val player = Rect().also { requireNotNull(findTag("player-screen")).getBoundsInScreen(it) }
        assertEquals("The touch target must reach the player right edge", player.right, bounds.right)
        // Accessibility may expand the touch target; press the visible 32dp circle's center.
        val x = player.right - 16f * app.resources.displayMetrics.density
        val y = bounds.exactCenterY()
        assertTrue("The visible circle center must be clickable", bounds.contains(x.toInt(), y.toInt()))
        captureIfRequested("player-lyrics-hidden-restore-button-before-press.png")
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        var released = false
        touch(MotionEvent.ACTION_DOWN)
        try {
            Thread.sleep(220L)
            captureIfRequested("player-lyrics-hidden-restore-button-pressed.png")
            assertFalse("Pressing must not activate controls before the button is released", state(model).controlsVisible)
            touch(MotionEvent.ACTION_UP)
            released = true
        } finally {
            if (!released) touch(MotionEvent.ACTION_CANCEL)
        }
        awaitState(model, "a real touch on the visible right-edge button restores controls without leaving lyrics") {
            it.controlsVisible && it.lyricsVisible
        }
        await("restore control disappears after the real touch") { findTag(RESTORE_TAG) == null }
    }

    @Test fun programmaticFollowingDoesNotRevealTheUserScrollRecoveryControl() = withPlayer { session ->
        val model = session.model
        hideControlsInLyrics(model)
        onMain { model.seekTo(15_000L) }
        awaitState(model, "paused native seek updates the lyric clock") { it.positionMs in 14_800L..15_200L }
        Thread.sleep(900L)
        assertNull("Seeking and automatic centering are not user scrolling", findTag(RESTORE_TAG))
        assertFalse(state(model).controlsVisible)
        onMain { model.setControlsVisible(true) }
        await("normal controls appear") { findTag("player-top-overlay") != null }
        swipeLyrics()
        Thread.sleep(300L)
        assertNull("A visible normal control bar suppresses the recovery control", findTag(RESTORE_TAG))
        assertTrue(state(model).lyricsVisible)
    }

    @Test fun recreationRetainsIndependentHiddenControlsAndStillAllowsScrollRecovery() = withPlayer { session ->
        val model = session.model
        hideControlsInLyrics(model)
        session.recreate()
        awaitState(model, "recreated player retains hidden controls and lyric mode") {
            it.ready && !it.playing && it.lyricsVisible && !it.controlsVisible
        }
        await("recreated lyrics are accessible") { findTag("player-lyrics-list") != null }
        assertNull(findTag(RESTORE_TAG))
        swipeLyrics()
        await("scroll recovery remains available after recreation") { findTag(RESTORE_TAG) != null }
        clickTag(RESTORE_TAG)
        awaitState(model, "recreated recovery shows controls independently") { it.lyricsVisible && it.controlsVisible }
    }

    private fun hideControlsInLyrics(model: PlayerViewModel) {
        onMain { model.setLyricsVisible(true); model.setControlsVisible(false) }
        awaitState(model, "lyric mode is active with controls hidden") { it.lyricsVisible && !it.controlsVisible }
        await("control fade finishes before the gesture") {
            findTag("player-top-overlay") == null && findTag("player-lyrics-list") != null
        }
    }

    private fun swipeLyrics() {
        val bounds = Rect().also { requireNotNull(findTag("player-lyrics-list")).getBoundsInScreen(it) }
        val x = bounds.left + bounds.width() * 0.45f
        val start = bounds.top + bounds.height() * 0.72f
        val end = bounds.top + bounds.height() * 0.30f
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, start)
        try {
            repeat(15) { index ->
                Thread.sleep(20L)
                touch(MotionEvent.ACTION_MOVE, start + (end - start) * (index + 1) / 15f)
            }
            touch(MotionEvent.ACTION_UP, end)
        } catch (error: Throwable) {
            touch(MotionEvent.ACTION_CANCEL, end)
            throw error
        }
    }

    private fun withPlayer(action: (Session) -> Unit) {
        val root = File(app.cacheDir, "player-hidden-lyrics-${System.nanoTime()}").apply { mkdirs() }
        val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val keys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled",
            "show_loudness_waveform", "audio_subtitle_line_spacing", "subtitle_font_size")
        val saved = keys.associateWith { preferences.all[it] }
        val track = createWav(root)
        File(root, "hidden-controls.lrc").writeText((0..59).joinToString("\n") { index ->
            "[00:${(index / 2).toString().padStart(2, '0')}.${if (index % 2 == 0) "00" else "50"}]Lyric context line $index"
        })
        try {
            assertTrue(preferences.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false).putBoolean("eq_enabled", false)
                .putBoolean("show_loudness_waveform", false).putInt("audio_subtitle_line_spacing", 12)
                .putInt("subtitle_font_size", 20).commit())
            openPlayer(track).use { session ->
                awaitState(session.model, "fixture playback and complete lyric document load") {
                    it.ready && it.lyricLines.size == 60
                }
                onMain { session.model.pause() }
                awaitState(session.model, "fixture is paused") { !it.playing }
                action(session)
            }
        } finally {
            val editor = preferences.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            editor.remove("last_position_${track.path}")
            assertTrue(editor.commit())
            root.deleteRecursively()
        }
    }

    private fun openPlayer(file: File): Session {
        val intent = Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(file.path))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { app.startActivity(intent) }
        return Session(awaitActivity()).also { session ->
            onMain { session.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }

    private inner class Session(var activity: PlayerActivity) : Closeable {
        val model = mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] }
        fun recreate() {
            val previous = activity
            onMain { previous.recreate() }
            activity = awaitActivity(previous)
            assertSame(model, mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] })
        }
        override fun close() {
            onMain { activity.finish() }
            await("player releases resources") { mainValue { activity.isDestroyed } }
        }
    }

    private fun awaitActivity(previous: PlayerActivity? = null): PlayerActivity {
        var activity: PlayerActivity? = null
        await("player activity resumes") {
            activity = mainValue { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlayerActivity>().firstOrNull { it !== previous } }
            activity != null
        }
        return requireNotNull(activity)
    }

    private fun findTag(tag: String) = findNode(instrumentation.uiAutomation.rootInActiveWindow) {
        it.viewIdResourceName == tag || it.viewIdResourceName?.substringAfter(":id/") == tag
    }
    private fun findNode(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (matches(node)) return node
        repeat(node.childCount) { findNode(node.getChild(it), matches)?.let { found -> return found } }
        return null
    }
    private fun clickTag(tag: String) {
        await("clickable $tag appears") {
            var node = findTag(tag)
            while (node != null) {
                if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@await true
                node = node.parent
            }
            false
        }
    }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(action()) }
        return result.get()
    }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun awaitState(model: PlayerViewModel, label: String, matches: (PlayerUiState) -> Boolean) {
        try {
            await(label) {
                val current = state(model)
                if (current.error != null) throw AssertionError("$label: ${current.error}")
                matches(current)
            }
        } catch (failure: AssertionError) { throw AssertionError("$label: ${state(model)}", failure) }
    }
    private fun await(label: String, timeoutMs: Long = 12_000L, matches: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (matches()) return
            Thread.sleep(75L)
        }
        captureIfRequested("player-lyrics-hidden-controls-failure.png")
        throw AssertionError("$label timed out")
    }
    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        try { File(app.getExternalFilesDir(null), name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
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
    private fun createWav(root: File) = File(root, "hidden-controls.wav").apply {
        val sampleRate = 8_000
        val samples = sampleRate * 30
        writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array())
    }

    private companion object { const val RESTORE_TAG = "player-lyrics-restore-controls" }
}

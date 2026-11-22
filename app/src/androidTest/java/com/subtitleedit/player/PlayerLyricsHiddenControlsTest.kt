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

/** Native playback and real touches verify the fixed-position audio controls and timed recovery. */
@RunWith(AndroidJUnit4::class)
class PlayerLyricsHiddenControlsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun audioPlayerLeavesFloatingCaptionsToTheSystemWindow() = withPlayer { session ->
        val model = session.model
        onMain { model.seekTo(5_250L) }
        awaitState(model, "seek updates the current lyric") { it.subtitleText.contains("Lyric context line 10") }
        assertNull(findTag("player-floating-subtitle"))

        onMain { model.setLyricsVisible(true) }
        await("opening detailed lyrics still renders the in-app list") {
            findTag("player-lyrics-list") != null && findTag("player-floating-subtitle") == null
        }

        onMain { model.setLyricsVisible(false) }
        await("closing detailed lyrics leaves no duplicate in-app caption") {
            findTag("player-lyrics-list") == null && findTag("player-floating-subtitle") == null
        }
    }

    @Test fun enteringLyricsFromHiddenArtworkAndReturningKeepControlsHidden() = withPlayer { session ->
        val model = session.model
        onMain { model.setControlsVisible(false) }
        await("artwork controls finish fading out") { findTag("player-top-overlay") == null }
        clickTag("player-audio-vinyl")
        awaitState(model, "artwork tap opens lyrics without opening playback controls") {
            it.lyricsVisible && !it.controlsVisible
        }
        await("hidden-controls lyric view fills the player") { findTag("player-lyrics-list") != null }
        assertNull("Opening lyrics alone does not start the recovery timer", findTag(RESTORE_TAG))
        pressBack()
        awaitState(model, "returning from lyrics keeps the independent controls state") {
            !it.lyricsVisible && !it.controlsVisible
        }
        await("artwork returns while controls remain absent") {
            findTag("player-audio-vinyl") != null && findTag("player-top-overlay") == null
        }
        tapAudioBlankArea(controlsVisible = false)
        await("a blank artwork tap offers temporary recovery") { findTag(RESTORE_TAG) != null }
        assertFalse("Offering recovery must not directly expand playback controls", state(model).controlsVisible)
        assertFalse(mainValue { session.activity.isFinishing })
    }

    @Test fun lyricScrollingRefreshesRecoveryAndStoppingLetsItFade() = withPlayer { session ->
        val model = session.model
        hideControlsInLyrics(model)
        assertNull("Recovery waits for user interaction", findTag(RESTORE_TAG))
        swipeLyrics()
        await("a user drag offers recovery") { findTag(RESTORE_TAG) != null }
        captureIfRequested("player-lyrics-hidden-restore-button.png")
        Thread.sleep(1_000L)
        swipeLyrics()
        Thread.sleep(1_250L)
        assertNotNull("A later drag refreshes the two-second visibility deadline", findTag(RESTORE_TAG))
        await("recovery fades after user scrolling stops", timeoutMs = 6_000L) { findTag(RESTORE_TAG) == null }
        Thread.sleep(900L)
        assertNull("Automatic following must not resurrect recovery", findTag(RESTORE_TAG))
        swipeLyrics()
        await("another user drag offers recovery again") { findTag(RESTORE_TAG) != null }
        clickTag(RESTORE_TAG)
        awaitState(model, "restore button reveals playback controls without leaving lyrics") {
            it.controlsVisible && it.lyricsVisible
        }
        await("playback controls are visible again") { findTag("player-top-overlay") != null }
        await("restore button is removed when playback controls are shown") { findTag(RESTORE_TAG) == null }
        assertNotNull("Expanded controls offer the persistent collapse button", findTag(HIDE_TAG))
        Thread.sleep(2_500L)
        assertNotNull("The collapse button never uses the recovery timeout", findTag(HIDE_TAG))
    }

    @Test fun pressingRecoveryPastItsTimeoutKeepsItVisibleAndReleasesIntoTheSamePosition() = withPlayer { session ->
        val model = session.model
        onMain { model.setLyricsVisible(true) }
        await("expanded lyric layout is ready") { findTag("player-lyrics-list") != null && findTag(HIDE_TAG) != null }
        val originalCollapseBounds = assertButtonOutsideControls()
        hideControlsInLyrics(model)
        swipeLyrics()
        await("the right-edge restore control is revealed") { findTag(RESTORE_TAG) != null }
        val restoreBounds = Rect().also { requireNotNull(findTag(RESTORE_TAG)).getBoundsInScreen(it) }
        val player = Rect().also { requireNotNull(findTag("player-screen")).getBoundsInScreen(it) }
        assertEquals("Recovery must use the collapse button's exact position", originalCollapseBounds, restoreBounds)
        assertEquals("The touch target must reach the player right edge", player.right, restoreBounds.right)
        // Accessibility may expand the touch target; press the visible 32dp circle's center.
        val x = player.right - 16f * app.resources.displayMetrics.density
        val y = restoreBounds.exactCenterY()
        assertTrue("The visible circle center must be clickable", restoreBounds.contains(x.toInt(), y.toInt()))
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
            Thread.sleep(2_700L)
            assertNotNull("A held recovery button must not disappear after two seconds", findTag(RESTORE_TAG))
            assertEquals("Holding must not move the recovery target", restoreBounds, bounds(RESTORE_TAG))
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
        await("expanded controls finish their geometry update") {
            findTag(HIDE_TAG) != null && bounds(HIDE_TAG) == restoreBounds
        }
    }

    @Test fun cancellingRecoveryTouchRestartsTheTwoSecondTimeout() = withPlayer { session ->
        val model = session.model
        hideControlsInLyrics(model)
        swipeLyrics()
        await("recovery is offered before the cancelled touch") { findTag(RESTORE_TAG) != null }
        val restoreBounds = bounds(RESTORE_TAG)
        val x = restoreBounds.right - 16f * app.resources.displayMetrics.density
        val y = restoreBounds.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        var cancelled = false
        touch(MotionEvent.ACTION_DOWN)
        try {
            Thread.sleep(2_500L)
            assertNotNull("The recovery target remains visible while touched", findTag(RESTORE_TAG))
            touch(MotionEvent.ACTION_CANCEL)
            cancelled = true
        } finally {
            if (!cancelled) touch(MotionEvent.ACTION_CANCEL)
        }
        assertFalse("Cancelling a touch must not expand playback controls", state(model).controlsVisible)
        Thread.sleep(1_000L)
        assertNotNull("Cancellation starts a new timeout instead of resuming an expired one", findTag(RESTORE_TAG))
        await("recovery fades after the new idle timeout", timeoutMs = 5_000L) { findTag(RESTORE_TAG) == null }
    }

    @Test fun programmaticFollowingDoesNotOfferRecovery() = withPlayer { session ->
        val model = session.model
        hideControlsInLyrics(model)
        onMain { model.seekTo(15_000L) }
        awaitState(model, "paused native seek updates the lyric clock") { it.positionMs in 14_800L..15_200L }
        Thread.sleep(900L)
        assertNull("Seeking and automatic centering do not count as user interaction", findTag(RESTORE_TAG))
        assertFalse(state(model).controlsVisible)
        onMain { model.setControlsVisible(true) }
        await("normal controls appear") { findTag("player-top-overlay") != null }
        swipeLyrics()
        Thread.sleep(300L)
        assertNull("A visible normal control bar suppresses the recovery control", findTag(RESTORE_TAG))
        assertNotNull("Scrolling with controls shown preserves the collapse button", findTag(HIDE_TAG))
        assertTrue(state(model).lyricsVisible)
    }

    @Test fun recreationRetainsHiddenControlsAndScrollRecovery() = withPlayer { session ->
        val model = session.model
        hideControlsInLyrics(model)
        session.recreate()
        awaitState(model, "recreated player retains hidden controls and lyric mode") {
            it.ready && !it.playing && it.lyricsVisible && !it.controlsVisible
        }
        await("recreated lyrics are accessible") { findTag("player-lyrics-list") != null }
        assertNull("Recreation alone must not offer recovery", findTag(RESTORE_TAG))
        swipeLyrics()
        await("scrolling offers recovery after recreation") { findTag(RESTORE_TAG) != null }
        clickTag(RESTORE_TAG)
        awaitState(model, "recreated recovery shows controls independently") { it.lyricsVisible && it.controlsVisible }
        await("recreated expanded controls offer collapse") { findTag(HIDE_TAG) != null }
    }

    @Test fun blankAudioTapsKeepControlsStableAndOnlyTheEdgeButtonCollapsesThem() = withPlayer { session ->
        val model = session.model
        await("the artwork starts with an explicit collapse button") { findTag(HIDE_TAG) != null }
        tapAudioBlankArea(controlsVisible = true)
        assertTrue("An audio blank-area tap must not collapse playback controls", state(model).controlsVisible)

        val collapse = assertButtonOutsideControls()
        clickTag(HIDE_TAG)
        awaitState(model, "the explicit down-arrow button collapses audio controls") { !it.controlsVisible }
        await("collapsing audio briefly offers recovery") {
            findTag("player-top-overlay") == null && findTag(RESTORE_TAG) != null
        }
        val restore = bounds(RESTORE_TAG)
        assertEquals("Collapse and restore share the same rectangle", collapse, restore)
        await("artwork recovery automatically fades", timeoutMs = 6_000L) { findTag(RESTORE_TAG) == null }
        tapAudioBlankArea(controlsVisible = false)
        assertFalse("An audio blank-area tap must not restore controls either", state(model).controlsVisible)
        await("an artwork blank tap only reveals the temporary recovery button") { findTag(RESTORE_TAG) != null }
        assertEquals("Revealing recovery again preserves its location", collapse, bounds(RESTORE_TAG))

        clickTag(RESTORE_TAG)
        awaitState(model, "the recovery arrow explicitly expands controls") { it.controlsVisible }
        await("expanded controls return before rotation") { findTag("player-top-overlay") != null && findTag(HIDE_TAG) != null }
        onMain { session.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await("landscape audio layout is ready") {
            val player = findTag("player-screen") ?: return@await false
            val area = Rect().also { player.getBoundsInScreen(it) }
            area.width() > area.height() && findTag(HIDE_TAG) != null && findTag("player-bottom-overlay") != null
        }
        val landscapeCollapse = assertButtonOutsideControls()
        clickTag(HIDE_TAG)
        awaitState(model, "the landscape collapse button remains usable") { !it.controlsVisible }
        await("landscape has immediate recovery") { findTag(RESTORE_TAG) != null }
        assertEquals("Landscape recovery uses the collapse button's exact position", landscapeCollapse, bounds(RESTORE_TAG))
    }

    @Test fun rotatingWithControlsHiddenKeepsRecoveryAtTheRestoredButtonPosition() = withPlayer { session ->
        val model = session.model
        hideControlsInLyrics(model)
        onMain { session.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await("hidden audio rotates without mounting playback controls") {
            val player = bounds("player-screen")
            player.width() > player.height() && findTag("player-top-overlay") == null
        }
        swipeLyrics()
        await("hidden landscape recovery is offered") { findTag(RESTORE_TAG) != null }
        val recovery = bounds(RESTORE_TAG)
        clickTag(RESTORE_TAG)
        awaitState(model, "recovery expands the rotated controls") { it.controlsVisible }
        await("the restored button keeps the measured hidden-layout position") {
            findTag(HIDE_TAG) != null && bounds(HIDE_TAG) == recovery
        }
        assertButtonOutsideControls()
    }

    private fun bounds(tag: String): Rect = Rect().also { requireNotNull(findTag(tag)).getBoundsInScreen(it) }

    private fun assertButtonOutsideControls(): Rect {
        val button = bounds(HIDE_TAG)
        val top = bounds("player-top-overlay")
        val bottom = bounds("player-bottom-overlay")
        assertTrue("The collapse button must sit below the top control bar", button.top >= top.bottom)
        assertTrue("The collapse button must sit above the bottom control bar", button.bottom <= bottom.top)
        assertEquals("The collapse button keeps the original right-edge alignment", bounds("player-screen").right, button.right)
        return button
    }

    private fun tapAudioBlankArea(controlsVisible: Boolean) {
        val player = bounds("player-screen")
        val x = player.left + 4f * app.resources.displayMetrics.density
        val y = if (controlsVisible) (bounds("player-top-overlay").bottom + bounds("player-bottom-overlay").top) / 2f
            else player.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        // The root distinguishes a single tap from a double tap before dispatching onTap.
        Thread.sleep(450L)
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

    private companion object {
        const val RESTORE_TAG = "player-lyrics-restore-controls"
        const val HIDE_TAG = "player-audio-hide-controls"
    }
}

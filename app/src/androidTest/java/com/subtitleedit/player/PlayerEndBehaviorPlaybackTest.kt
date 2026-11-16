package com.subtitleedit.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.R
import com.subtitleedit.model.FileSortField
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.util.SettingsManager
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real mpv EOF and pointer input, without waiting for the continuous player animation to idle. */
@RunWith(AndroidJUnit4::class)
class PlayerEndBehaviorPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun trackStopRetainsTheCompletedTrackEvenWhenAnotherTrackExists() = Fixture().use { f ->
        val tracks = listOf(f.wav("first.wav"), f.wav("second.wav"))
        openPlayer(tracks).use { session ->
            val model = session.model
            apply(model, PlayerTrackEndAction.STOP, PlayerQueueEndAction.REPEAT_QUEUE)
            finishCurrentTrack(model)
            awaitState(model, "track STOP reaches EOF without advancing") {
                it.currentIndex == 0 && !it.playing && it.positionMs >= it.durationMs - 100L
            }
            assertRemainsStopped(model, tracks.first())
        }
    }

    @Test fun loadNextPausedStartsAtZeroInsteadOfTheSavedResumePosition() = Fixture().use { f ->
        val tracks = listOf(f.wav("first.wav"), f.wav("second.wav"))
        openPlayer(tracks).use { session ->
            val model = session.model
            onMain { model.setResumePlayback(true) }
            assertTrue(f.preferences.edit().putLong("last_position_${tracks[1].path}", 4_000L).commit())
            apply(model, PlayerTrackEndAction.LOAD_NEXT_PAUSED)
            finishCurrentTrack(model)
            awaitState(model, "next track is loaded paused at the beginning") {
                it.currentIndex == 1 && it.ready && !it.playing && it.positionMs in 0L..150L
            }
            assertEquals(tracks[1].path, nativePath())
            assertEquals(true, mainValue { MPVLib.getPropertyBoolean("pause") })
            Thread.sleep(600L)
            assertEquals(1, state(model).currentIndex)
            assertFalse(state(model).playing)
            assertTrue("The paused next track must stay at zero", state(model).positionMs <= 150L)
        }
    }

    @Test fun repeatTrackRestartsTheSameTrackAndKeepsPlaying() = Fixture().use { f ->
        val tracks = listOf(f.wav("first.wav"), f.wav("second.wav"))
        openPlayer(tracks).use { session ->
            val model = session.model
            apply(model, PlayerTrackEndAction.FOLLOW_MODE, mode = PlayerPlayMode.REPEAT_ONE)
            finishCurrentTrack(model)
            awaitState(model, "repeat current track starts over") {
                it.currentIndex == 0 && it.ready && it.playing && it.positionMs in 200L..2_000L
            }
            assertEquals(tracks.first().path, nativePath())
            assertKeepsPlaying(model, expectedIndex = 0)
        }
    }

    @Test fun queueStopKeepsTheLastSongSelectedAndStopped() = Fixture().use { f ->
        val tracks = listOf(f.wav("first.wav"), f.wav("last.wav"))
        openPlayer(tracks, startIndex = 1).use { session ->
            val model = session.model
            apply(model, PlayerTrackEndAction.FOLLOW_MODE, PlayerQueueEndAction.STOP)
            finishCurrentTrack(model)
            awaitState(model, "queue STOP keeps the completed final song") {
                it.currentIndex == 1 && !it.playing && it.positionMs >= it.durationMs - 100L
            }
            assertRemainsStopped(model, tracks.last())
        }
    }

    @Test fun repeatQueueLoadsTheFirstSongAndContinuesPlayback() = Fixture().use { f ->
        val tracks = listOf(f.wav("first.wav"), f.wav("last.wav"))
        openPlayer(tracks, startIndex = 1).use { session ->
            val model = session.model
            apply(model, PlayerTrackEndAction.FOLLOW_MODE, PlayerQueueEndAction.REPEAT_QUEUE)
            finishCurrentTrack(model)
            awaitState(model, "repeat queue restarts at the first song") {
                it.currentIndex == 0 && it.ready && it.playing && it.positionMs in 200L..2_000L
            }
            assertEquals(tracks.first().path, nativePath())
            assertKeepsPlaying(model, expectedIndex = 0)
        }
    }

    @Test fun nextDirectoryHonorsDescendingSortAndWrapsOnlyWhenEnabled() = Fixture().use { f ->
        f.useDescendingNameSort()
        val current = f.wav("c/current.wav")
        f.file("b/readme.txt").writeText("No playable media in this directory")
        val firstInNext = f.wav("a/z.wav")
        val lastInNext = f.wav("a/a.wav")
        f.wav(".hidden/hidden.wav")
        val source = PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, current.parentFile!!.path)
        openPlayer(listOf(current), source = source).use { session ->
            val model = session.model
            apply(model, PlayerTrackEndAction.FOLLOW_MODE, PlayerQueueEndAction.NEXT_QUEUE)
            finishCurrentTrack(model)
            awaitState(model, "EOF skips a non-media directory and opens the next sorted directory") {
                it.queueSource?.key == firstInNext.parentFile!!.path &&
                    it.currentTrack?.path == firstInNext.path && it.ready && it.playing && it.positionMs > 150L
            }
            assertEquals(listOf(firstInNext.path, lastInNext.path), state(model).playlist.map { it.path })
            assertEquals(firstInNext.path, nativePath())

            onMain { model.playIndex(1) }
            awaitState(model, "last song in final directory loads") {
                it.currentIndex == 1 && it.ready && it.playing && it.currentTrack?.path == lastInNext.path
            }
            finishCurrentTrack(model)
            awaitState(model, "without wrapping the final directory stops") {
                it.currentTrack?.path == lastInNext.path && !it.playing && it.positionMs >= it.durationMs - 100L
            }
            assertRemainsStopped(model, lastInNext)

            apply(model, PlayerTrackEndAction.FOLLOW_MODE, PlayerQueueEndAction.NEXT_QUEUE, wrap = true)
            finishCurrentTrack(model)
            awaitState(model, "with wrapping EOF returns to the first directory") {
                it.queueSource == source && it.currentTrack?.path == current.path &&
                    it.currentIndex == 0 && it.ready && it.playing && it.positionMs in 150L..2_000L
            }
            pauseAtBeginning(model)
            onMain { model.previous() }
            awaitState(model, "previous before the first directory wraps to the final song") {
                it.queueSource?.key == lastInNext.parentFile!!.path &&
                    it.currentTrack?.path == lastInNext.path && it.currentIndex == 1 && it.ready && it.playing
            }
            assertEquals(lastInNext.path, nativePath())
        }
    }

    @Test fun backwardShuffleEntryTraversesThePreviousQueueBeforeLeavingIt() = Fixture().use { f ->
        f.useDescendingNameSort()
        val firstSourceTrack = f.wav("c/current.wav")
        val finalSourceTracks = listOf(f.wav("a/first.wav"), f.wav("a/second.wav"), f.wav("a/third.wav"))
        val source = PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, firstSourceTrack.parentFile!!.path)
        openPlayer(listOf(firstSourceTrack), source = source).use { session ->
            val model = session.model
            apply(model, PlayerTrackEndAction.FOLLOW_MODE, PlayerQueueEndAction.NEXT_QUEUE,
                wrap = true, mode = PlayerPlayMode.SHUFFLE)
            pauseAtBeginning(model)
            onMain { model.previous() }
            awaitState(model, "backward entry loads the last traversal item of the previous queue") {
                it.queueSource?.key == finalSourceTracks.first().parentFile!!.path &&
                    it.ready && it.playing && it.playlist.size == 3 && it.currentIndex == it.shuffleOrder.last()
            }
            val entered = state(model)
            val expectedPrevious = entered.shuffleOrder[entered.shuffleOrder.lastIndex - 1]
            onMain { model.previous() }
            awaitState(model, "another Previous remains in the same shuffled queue") {
                it.queueSource == entered.queueSource && it.currentIndex == expectedPrevious && it.ready && it.playing
            }
            assertEquals(entered.shuffleOrder, state(model).shuffleOrder)
        }
    }

    @Test fun settingsButtonEditsDraftAndPersistsOnlyConfirmationWhileShortClickCyclesModes() = Fixture().use { f ->
        val tracks = listOf(f.wav("controls.wav"))
        val initial = PlayerEndBehavior(enabled = true, queueAction = PlayerQueueEndAction.REPEAT_QUEUE)
        val confirmed = initial.copy(trackAction = PlayerTrackEndAction.STOP)
        openPlayer(tracks).use { session ->
            val model = session.model
            pauseAtBeginning(model)
            assertFalse(state(model).endBehavior.enabled)
            assertEndBehaviorIcons(R.string.player_end_continue, R.string.player_end_stop)
            pressTag("player-quick-end-behavior")
            awaitState(model, "settings open without applying an advanced override") { it.showEndBehaviorDialog }
            await("settings open on the advanced options") {
                nodeForTag("player-end-track:STOP") != null &&
                    nodeForTag("player-end-track:LOAD_NEXT_PAUSED") != null && nodeForTag("player-end-mode:SEQUENCE") == null
            }
            captureEndBehaviorIfRequested("player-end-default-advanced.png")
            assertFalse(PlayerPreferences(f.preferences).endBehavior.enabled)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitState(model, "default advanced view can be dismissed without applying") { !it.showEndBehaviorDialog }
            await("default dialog is gone") { boundsForTag("player-end-behavior-dialog") == null }
            onMain { model.applyEndBehavior(PlayerPlayMode.REPEAT_ALL, initial) }
            pressTag("player-mode-chip", longPress = true)
            assertFalse("Holding the mode chip no longer opens playback settings", state(model).showEndBehaviorDialog)
            onMain { model.applyEndBehavior(PlayerPlayMode.REPEAT_ALL, initial) }
            val originalSettings = playbackSettings(f.preferences)
            assertEndBehaviorIcons(R.string.player_end_continue, R.string.player_end_repeat_queue)
            captureEndBehaviorIfRequested("player-end-next-repeat.png")

            val playlistButton = requireNotNull(boundsForTag("player-quick-add-playlist"))
            val settingsButton = requireNotNull(boundsForTag("player-quick-end-behavior"))
            assertTrue("Playback-ending settings sit to the right of Add to playlist",
                settingsButton.left >= playlistButton.right)
            assertEquals("The new button shares the quick action row", playlistButton.centerY(), settingsButton.centerY())
            pressTag("player-quick-end-behavior")
            awaitState(model, "settings button opens playback settings") { it.showEndBehaviorDialog }
            assertEquals("Opening settings must not cycle the mode", PlayerPlayMode.REPEAT_ALL, state(model).mode)
            assertEquals(initial, state(model).endBehavior)
            pressTag("player-end-track:STOP")
            pressTag("player-end-simple")
            pressTag("player-end-mode:SHUFFLE")
            assertEquals("Draft edits must not touch preferences", originalSettings, playbackSettings(f.preferences))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitState(model, "Back discards the dialog draft") { !it.showEndBehaviorDialog }
            await("dismissed dialog leaves the player window") { boundsForTag("player-end-behavior-dialog") == null }
            assertFalse(mainValue { session.activity.isFinishing })
            assertEquals(PlayerPlayMode.REPEAT_ALL, state(model).mode)
            assertEquals(initial, state(model).endBehavior)
            assertEquals(originalSettings, playbackSettings(f.preferences))

            pressTag("player-quick-end-behavior")
            awaitState(model, "settings button reopens applied settings") { it.showEndBehaviorDialog }
            pressTag("player-end-track:STOP")
            pressTag("player-end-confirm")
            awaitState(model, "confirmation applies advanced settings") {
                !it.showEndBehaviorDialog && it.mode == PlayerPlayMode.REPEAT_ALL && it.endBehavior == confirmed
            }
            await("confirmed dialog leaves the player window") { boundsForTag("player-end-behavior-dialog") == null }
            assertEndBehaviorIcons(R.string.player_end_stop, R.string.player_end_repeat_queue)
            captureEndBehaviorIfRequested("player-end-stop-repeat.png")
            assertEquals(confirmed, PlayerPreferences(f.preferences).endBehavior)
            assertEquals(PlayerPlayMode.REPEAT_ALL, PlayerPreferences(f.preferences).defaultPlayMode)
        }

        openPlayer(tracks).use { session ->
            val model = session.model
            pauseAtBeginning(model)
            assertEquals("Advanced settings survive a new player session", confirmed, state(model).endBehavior)
            assertEquals(PlayerPlayMode.REPEAT_ALL, state(model).mode)
            val expectedModes = listOf(PlayerPlayMode.SHUFFLE, PlayerPlayMode.SEQUENCE,
                PlayerPlayMode.REPEAT_ONE, PlayerPlayMode.REPEAT_ALL)
            expectedModes.forEach { expected ->
                pressTag("player-mode-chip")
                awaitState(model, "short click switches to $expected") {
                    it.mode == expected && it.endBehavior.enabled && !it.showEndBehaviorDialog &&
                        it.endBehavior.trackAction == PlayerTrackEndAction.FOLLOW_MODE
                }
                assertEquals(expected, PlayerPreferences(f.preferences).defaultPlayMode)
                assertEquals(confirmed.copy(trackAction = PlayerTrackEndAction.FOLLOW_MODE),
                    PlayerPreferences(f.preferences).endBehavior)
                assertEndBehaviorIcons(R.string.player_end_continue, R.string.player_end_repeat_queue)
                if (expected == PlayerPlayMode.REPEAT_ONE) captureEndBehaviorIfRequested("player-end-repeat-repeat.png")
            }
            onMain { model.applyEndBehavior(PlayerPlayMode.SEQUENCE, PlayerEndBehavior(enabled = true)) }
            assertEndBehaviorIcons(R.string.player_end_continue, R.string.player_end_stop)
            captureEndBehaviorIfRequested("player-end-next-stop.png")
            onMain { model.applyEndBehavior(PlayerPlayMode.SEQUENCE, PlayerEndBehavior(enabled = true,
                trackAction = PlayerTrackEndAction.LOAD_NEXT_PAUSED, queueAction = PlayerQueueEndAction.NEXT_QUEUE)) }
            assertEndBehaviorIcons(R.string.player_end_load_next_paused, R.string.player_end_next_queue)
            captureEndBehaviorIfRequested("player-end-paused-next.png")
        }
    }

    private fun apply(model: PlayerViewModel, track: PlayerTrackEndAction,
        queue: PlayerQueueEndAction = PlayerQueueEndAction.STOP, wrap: Boolean = false,
        mode: PlayerPlayMode = PlayerPlayMode.SEQUENCE) {
        onMain {
            model.applyEndBehavior(mode,
                PlayerEndBehavior(enabled = true, trackAction = track, queueAction = queue, wrapQueues = wrap))
        }
    }

    private fun finishCurrentTrack(model: PlayerViewModel) {
        awaitState(model, "media is ready before EOF seek") { it.ready && it.durationMs > 2_000L }
        onMain { model.pause() }
        awaitState(model, "pause before EOF seek") { !it.playing }
        val nearEnd = state(model).durationMs - 1_000L
        onMain { model.seekTo(nearEnd) }
        awaitState(model, "near-EOF seek reaches the engine") { !it.playing && abs(it.positionMs - nearEnd) < 150L }
        onMain { model.play() }
    }

    private fun pauseAtBeginning(model: PlayerViewModel) {
        onMain { model.pause(); model.seekTo(0L); model.setControlsVisible(true) }
        awaitState(model, "paused at beginning") { it.ready && !it.playing && it.positionMs <= 150L }
    }

    private fun assertRemainsStopped(model: PlayerViewModel, expected: File) {
        val position = state(model).positionMs
        Thread.sleep(600L)
        val after = state(model)
        assertFalse("Completed media must remain stopped", after.playing)
        assertEquals(expected.path, after.currentTrack?.path)
        assertEquals(expected.path, nativePath())
        assertTrue("The completed track must not restart", abs(after.positionMs - position) < 150L)
    }

    private fun assertKeepsPlaying(model: PlayerViewModel, expectedIndex: Int) {
        val position = state(model).positionMs
        awaitState(model, "restarted media continues advancing") {
            it.currentIndex == expectedIndex && it.ready && it.playing && it.positionMs > position + 300L
        }
    }

    private inner class Fixture : Closeable {
        val root = File(app.cacheDir, "player-end-behavior-${System.nanoTime()}").apply { check(mkdirs()) }
        val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val saved = PLAYER_KEYS.associateWith { preferences.all[it] }
        private val browserPreferences = app.getSharedPreferences("subtitle_edit_settings", Context.MODE_PRIVATE)
        private val browserSaved = BROWSER_KEYS.associateWith { browserPreferences.all[it] }
        private var changedSort = false

        init {
            assertTrue(preferences.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false)
                .putBoolean("eq_enabled", false).putBoolean("show_loudness_waveform", false)
                .putBoolean("end_behavior_enabled", false).putString("track_end_action", PlayerTrackEndAction.FOLLOW_MODE.name)
                .putString("queue_end_action", PlayerQueueEndAction.STOP.name).putBoolean("queue_end_wrap", false).commit())
        }

        fun file(path: String) = File(root, path).apply { check(parentFile!!.isDirectory || parentFile!!.mkdirs()) }
        fun wav(path: String) = file(path).apply { writeBytes(silenceWav()) }
        fun useDescendingNameSort() {
            changedSort = true
            SettingsManager.getInstance(app).apply {
                setFileSortField(FileSortField.NAME)
                setFileSortDirection(FileSortDirection.DESCENDING)
                setShowHiddenFilesEnabled(false)
            }
        }

        override fun close() {
            try {
                val editor = preferences.edit()
                saved.forEach { (key, value) -> restore(editor, key, value) }
                root.walkTopDown().filter { it.isFile }.forEach { editor.remove("last_position_${it.path}") }
                assertTrue("Restore original player preferences", editor.commit())
            } finally {
                if (changedSort) {
                    val editor = browserPreferences.edit()
                    browserSaved.forEach { (key, value) -> restore(editor, key, value) }
                    assertTrue("Restore original directory ordering preferences", editor.commit())
                }
                root.deleteRecursively()
            }
        }
    }

    private fun openPlayer(tracks: List<File>, startIndex: Int = 0, source: PlayerQueueSource? = null): Session {
        onMain {
            app.startActivity(Intent(app, PlayerActivity::class.java)
                .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(tracks.map { it.path }))
                .putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        var activity: PlayerActivity? = null
        await("player activity resumes") {
            activity = mainValue { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlayerActivity>().firstOrNull() }
            activity != null
        }
        return Session(requireNotNull(activity)).also { session ->
            if (source != null) onMain {
                session.model.openQueue(tracks.map { PlayerTrack(it.path, it.name, false) }, startIndex, queueSource = source)
            }
        }
    }

    private inner class Session(val activity: PlayerActivity) : Closeable {
        val model = mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] }
        private val oldOrientation = mainValue { activity.requestedOrientation }
        private val oldAccessibilityFlags = automation.serviceInfo.flags

        init {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("portrait fixture") {
                mainValue { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            }
            awaitState(model, "fixture playback advances") { it.ready && it.playing && it.positionMs > 150L }
        }

        override fun close() {
            try {
                onMain { activity.requestedOrientation = oldOrientation; activity.finish() }
                await("player releases native mpv") { mainValue { activity.isDestroyed } }
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = oldAccessibilityFlags }
            }
        }
    }

    private fun pressTag(tag: String, longPress: Boolean = false) {
        var bounds: Rect? = null
        await("visible $tag") { bounds = boundsForTag(tag); bounds != null }
        val target = requireNotNull(bounds)
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, target.exactCenterX(), target.exactCenterY())
        var released = false
        try {
            Thread.sleep(if (longPress) ViewConfiguration.getLongPressTimeout().toLong() + 200L else 60L)
            inject(downTime, MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY())
            released = true
        } finally {
            if (!released) inject(downTime, MotionEvent.ACTION_CANCEL, target.exactCenterX(), target.exactCenterY())
        }
    }

    private fun boundsForTag(tag: String): Rect? = nodeForTag(tag)?.let { node ->
        Rect().also { node.getBoundsInScreen(it) }
    }

    private fun nodeForTag(tag: String): AccessibilityNodeInfo? {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        automation.windows.mapNotNull { it.root }.forEach { nodes.addLast(it) }
        automation.rootInActiveWindow?.let { nodes.addLast(it) }
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            val id = node.viewIdResourceName.orEmpty()
            if (node.isVisibleToUser && (id == tag || id.endsWith(":id/$tag"))) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) return node
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let { nodes.addLast(it) } }
        }
        return null
    }

    private fun assertEndBehaviorIcons(trackLabel: Int, queueLabel: Int) {
        if (Build.VERSION.SDK_INT < 30) return
        val expected = app.getString(R.string.player_end_action_description, app.getString(trackLabel), app.getString(queueLabel))
        var actual: CharSequence? = null
        try {
            await("the settings button describes the active track and queue actions") {
                actual = nodeForTag("player-quick-end-behavior")?.apply { refresh() }?.stateDescription
                actual?.toString() == expected
            }
        } catch (error: AssertionError) {
            captureEndBehaviorIfRequested("player-end-icon-failure.png")
            throw AssertionError("Expected $expected, got $actual", error)
        }
    }

    private fun captureEndBehaviorIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        val bitmap = automation.takeScreenshot() ?: return
        try { File(app.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    private fun inject(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Inject real playback control touch", automation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun playbackSettings(preferences: SharedPreferences) = PLAYBACK_KEYS.associateWith { preferences.all[it] }
    private fun nativePath() = mainValue { MPVLib.getPropertyString("path") }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(action()) }
        return result.get()
    }

    private fun awaitState(model: PlayerViewModel, label: String, predicate: (PlayerUiState) -> Boolean) {
        var latest = state(model)
        try {
            await(label) {
                latest = state(model)
                latest.error?.let { throw AssertionError("$label: $it") }
                predicate(latest)
            }
        } catch (error: AssertionError) {
            throw AssertionError("${error.message}; index=${latest.currentIndex}, path=${latest.currentTrack?.path}, " +
                "ready=${latest.ready}, playing=${latest.playing}, position=${latest.positionMs}/${latest.durationMs}, " +
                "behavior=${latest.endBehavior}, source=${latest.queueSource}", error)
        }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
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
            is Long -> editor.putLong(key, value)
            else -> error("Unexpected fixture preference value for $key: ${value.javaClass.name}")
        }
    }

    private fun silenceWav(): ByteArray {
        val sampleRate = 16_000
        val count = sampleRate * 8
        return ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array()
    }

    private companion object {
        val PLAYBACK_KEYS = listOf("default_play_mode", "end_behavior_enabled", "track_end_action",
            "queue_end_action", "queue_end_wrap", "queue_end_reshuffle")
        val PLAYER_KEYS = PLAYBACK_KEYS + listOf("default_speed", "resume_playback", "eq_enabled", "show_loudness_waveform")
        val BROWSER_KEYS = listOf("file_sort_field", "file_sort_direction", "show_hidden_files")
    }
}


package com.subtitleedit.player

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.MainActivity
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.mpv.MPVLib
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the posted system notification and its real MediaSession against the shared mpv. */
@RunWith(AndroidJUnit4::class)
class PlayerMediaNotificationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Test fun metadataCoverAndSystemControlsFollowTheExistingPlayer() = Fixture().use { f ->
        val first = f.wav("covered.wav").also { f.addTagsAndCover(it) }
        val second = f.wav("second.wav")
        // An external file still shows artwork, while its text stays filename-only.
        f.openMain(listOf(first, second)).use { session ->
            val model = session.model
            val controller = awaitController()
            await("external artwork and filename reach the system session without text tags") {
                val metadata = controller.metadata
                metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "covered" &&
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank() &&
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).isNullOrBlank() &&
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) >= 59_000L && isRed(artwork(metadata))
            }
            await("external notification title uses the filename") {
                notification()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "covered"
            }
            assertEquals(Notification.CATEGORY_TRANSPORT, requireNotNull(notification()).category)
            assertTrue("Playback is protected by a foreground service",
                requireNotNull(notification()).flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            await("system session reports playing") { controller.playbackState?.state == PlaybackState.STATE_PLAYING }
            captureNotificationIfRequested()

            val repository = MediaLibraryRepository.getInstance(app)
            try {
                assertTrue(repository.toggleFavorite(first.path))
                await("favoriting the playing file enables library text and preserves its cover") {
                    val metadata = controller.metadata
                    metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == TITLE &&
                        metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) == ARTIST &&
                        metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) == ALBUM && isRed(artwork(metadata))
                }
                assertTrue(repository.getIndexedTracks().none { it.path == first.path })
            } finally {
                repository.removeFavorite(first.path)
            }
            await("removing the collection clears text tags while preserving the external cover") {
                val metadata = controller.metadata
                metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "covered" &&
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank() &&
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).isNullOrBlank() && isRed(artwork(metadata))
            }

            onMain { controller.transportControls.pause() }
            awaitPaused(model)
            await("system session reports paused") { controller.playbackState?.state == PlaybackState.STATE_PAUSED }
            onMain { controller.transportControls.seekTo(5_000L) }
            awaitState(model, "system seek changes the same paused native media") {
                it.ready && !it.playing && it.positionMs in 4_850L..5_150L
            }
            await("system progress follows the paused seek") {
                abs((controller.playbackState?.position ?: -10_000L) - 5_000L) <= 1_000L
            }
            onMain { controller.transportControls.play() }
            awaitState(model, "system Play resumes existing output") { it.ready && it.playing && it.positionMs > 5_150L }
            onMain { controller.transportControls.skipToNext() }
            awaitState(model, "system Next selects the next queued file") {
                it.currentIndex == 1 && it.currentTrack?.path == second.path && it.ready && it.playing
            }
            assertEquals(second.path, mainValue { MPVLib.getPropertyString("path") })
            assertEquals("Changing tracks keeps one system media session", controller.sessionToken, sessionToken())
            await("metadata from the previous track cannot leak into the next one") {
                val metadata = controller.metadata
                metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "second" &&
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank() &&
                    artwork(metadata) != null && !isRed(artwork(metadata))
            }

            // Exercise the notification PendingIntent too, rather than only direct session APIs.
            await("notification offers Pause for the playing track") {
                notification()?.actions?.getOrNull(1)?.title?.toString() == app.getString(com.subtitleedit.R.string.player_pause)
            }
            requireNotNull(requireNotNull(notification()).actions[1].actionIntent).send()
            awaitPaused(model)
            await("notification reflects the paused session") { controller.playbackState?.state == PlaybackState.STATE_PAUSED }
            await("notification offers Play for the paused track") {
                notification()?.actions?.getOrNull(1)?.title?.toString() == app.getString(com.subtitleedit.R.string.player_play)
            }
            requireNotNull(requireNotNull(notification()).actions[1].actionIntent).send()
            awaitState(model, "notification Play resumes the same queued file") {
                it.currentIndex == 1 && it.ready && it.playing
            }
            onMain { controller.transportControls.seekTo(0L) }
            awaitState(model, "previous starts from the beginning of the second file") {
                it.currentIndex == 1 && it.ready && it.positionMs < 500L
            }
            onMain { controller.transportControls.skipToPrevious() }
            awaitState(model, "system Previous selects the previous queued file") {
                it.currentIndex == 0 && it.currentTrack?.path == first.path && it.ready && it.playing
            }

            val destroyed = AtomicBoolean(false)
            val callback = object : MediaController.Callback() {
                override fun onSessionDestroyed() { destroyed.set(true) }
            }
            controller.registerCallback(callback, Handler(Looper.getMainLooper()))
            try {
                onMain { controller.transportControls.stop() }
                awaitState(model, "system Stop closes the player queue") { it.currentTrack == null && !it.playing }
                await("system Stop removes the notification and releases its session") {
                    notification() == null && destroyed.get()
                }
            } finally { controller.unregisterCallback(callback) }
        }
    }

    @Test fun notificationReturnsToCurrentQueueAndSurvivesActivityRecreation() = Fixture().use { f ->
        f.openMain(listOf(f.wav("return.wav"))).use { session ->
            val model = session.model
            val token = awaitController().sessionToken
            onMain { model.pause(); model.collapse() }
            awaitPaused(model)
            assertFalse(mainValue { model.expanded.value })
            session.scenario.recreate()
            session.scenario.onActivity {
                session.activity = it
                assertSame(model, ViewModelProvider(it)[PlayerViewModel::class.java])
            }
            awaitState(model, "recreated player retains paused output") { it.ready && !it.playing }
            assertEquals("Rotation must not create another system session", token, sessionToken())
            assertFalse(mainValue { model.expanded.value })
            requireNotNull(requireNotNull(notification()).contentIntent).send()
            await("notification opens the current full player") { mainValue { model.expanded.value } }
            assertSame(model, mainValue { ViewModelProvider(session.activity)[PlayerViewModel::class.java] })
            assertEquals("Opening the notification does not start paused media", false, state(model).playing)
            assertEquals(token, sessionToken())
        }
    }

    @Test fun systemControlsWorkWhileTheMainWindowIsBackgroundedAndScreenIsOff() = Fixture().use { f ->
        f.openMain(listOf(f.wav("background.wav"))).use { session ->
            val model = session.model
            val controller = awaitController()
            val power = app.getSystemService(PowerManager::class.java)
            val wasInteractive = power.isInteractive
            try {
                val beforeHome = state(model).positionMs
                shell("input keyevent 3")
                awaitState(model, "audio advances after Home") { it.playing && it.positionMs > beforeHome + 400L }
                assertNotNull(notification())
                val beforeSleep = state(model).positionMs
                shell("input keyevent 223")
                await("display turns off") { !power.isInteractive }
                awaitState(model, "foreground audio continues with the display off") {
                    it.playing && it.positionMs > beforeSleep + 400L
                }
                onMain { controller.transportControls.pause() }
                awaitPaused(model)
                // mpv can publish its final audio position after acknowledging the pause flag.
                Thread.sleep(300L)
                val pausedAt = mainValue { requireNotNull(MPVLib.getPropertyDouble("time-pos")) * 1000 }
                Thread.sleep(500L)
                assertFalse(state(model).playing)
                val stillPausedAt = mainValue { requireNotNull(MPVLib.getPropertyDouble("time-pos")) * 1000 }
                assertTrue("Paused native audio must not advance: $pausedAt -> $stillPausedAt",
                    abs(stillPausedAt - pausedAt) < 200L)
                onMain { controller.transportControls.play() }
                awaitState(model, "system Play resumes audio with the display off") {
                    it.playing && it.positionMs > pausedAt + 200L
                }
            } finally {
                if (wasInteractive) { shell("input keyevent 224"); shell("wm dismiss-keyguard") }
            }
        }
    }

    @Test fun outputHandoffAndLeavingAudioModeRemoveStaleControls() = Fixture().use { f ->
        f.openMain(listOf(f.wav("handoff.wav"))).use { session ->
            val model = session.model
            val oldController = awaitController()
            onMain { model.suspendOutput() }
            await("exclusive output handoff removes system playback controls") { notification() == null }
            assertTrue(mainValue { model.outputSuspended.value })
            onMain { oldController.transportControls.play() }
            Thread.sleep(250L)
            assertFalse("A stale system session must not restart shared mpv", state(model).ready)
            onMain { model.resumeOutput() }
            awaitState(model, "main output resumes after handoff") { it.ready && it.playing }
            val resumedController = awaitController()
            assertNotEquals("Released controls must not target the resumed session",
                oldController.sessionToken, resumedController.sessionToken)
            onMain { oldController.transportControls.pause() }
            val resumedAt = state(model).positionMs
            awaitState(model, "stale controls cannot pause the resumed output") {
                it.playing && it.positionMs > resumedAt + 300L
            }
            onMain {
                PlayerPreferences.getInstance(app).playerModeEnabled = false
                MainActivity::class.java.getDeclaredMethod("refreshPlayerModeNavigation").apply {
                    isAccessible = true
                }.invoke(session.activity)
            }
            awaitState(model, "leaving audio mode closes playback") { it.currentTrack == null && !it.playing }
            await("ordinary mode has no lingering playback notification") { notification() == null }
        }
    }

    @Test fun ordinaryStandalonePlaybackDoesNotPostAnAudioModeNotification() = Fixture(audioMode = false).use { f ->
        val track = f.wav("ordinary.wav")
        val intent = Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(track.path))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0)
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            lateinit var model: PlayerViewModel
            scenario.onActivity { model = ViewModelProvider(it)[PlayerViewModel::class.java] }
            awaitState(model, "ordinary playback still works") { it.ready && it.playing && it.positionMs > 250L }
            Thread.sleep(400L)
            assertNull("Only audio mode publishes a system media notification", notification())
        }
    }

    @Test fun rapidPausedHandoffsRestoreForegroundControlsAndRejectOldNotificationActions() = Fixture().use { f ->
        val track = f.wav("rapid-handoff.wav")
        f.openMain(listOf(track)).use { session ->
            val model = session.model
            onMain { model.pause() }
            awaitPaused(model)
            repeat(3) {
                val oldController = awaitController()
                val oldStop = requireNotNull(requireNotNull(notification()).actions[3].actionIntent)
                onMain { model.suspendOutput(); model.resumeOutput() }
                awaitState(model, "paused output reattaches after rapid handoff") { it.ready && !it.playing }
                await("a fresh foreground system session returns without another playback command") {
                    val posted = notification()
                    posted != null && sessionToken() != oldController.sessionToken &&
                        posted.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
                }
                oldStop.send()
                onMain { oldController.transportControls.play() }
                Thread.sleep(300L)
                assertEquals("Old notification Stop cannot close the resumed session", track.path, state(model).currentTrack?.path)
                assertFalse("Old system Play cannot restart the paused session", state(model).playing)
                assertNotNull(notification())
            }
        }
    }

    private inner class Fixture(audioMode: Boolean = true) : Closeable {
        val root = File(app.cacheDir, "player-media-notification-${System.nanoTime()}").apply { check(mkdirs()) }
        private val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val keys = listOf("player_mode_enabled", "default_play_mode", "default_speed", "resume_playback",
            "eq_enabled", "show_loudness_waveform", "end_behavior_enabled")
        private val saved = keys.associateWith { preferences.all[it] }

        init {
            assertTrue(preferences.edit().putBoolean("player_mode_enabled", audioMode)
                .putString("default_play_mode", PlayerPlayMode.SEQUENCE.name).putFloat("default_speed", 1f)
                .putBoolean("resume_playback", false).putBoolean("eq_enabled", false)
                .putBoolean("show_loudness_waveform", false).putBoolean("end_behavior_enabled", false).commit())
            await("previous media notification is cleaned up") { notification() == null }
        }

        fun wav(name: String) = File(root, name).apply {
            val rate = 16_000
            val count = rate * 60
            writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(count * 2)
            }.array())
        }

        fun addTagsAndCover(track: File) {
            val cover = File(root, "cover.png")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try { cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
            MediaTagAudioRepository.write(track, MediaConvertMetadata(title = TITLE, artist = ARTIST, album = ALBUM,
                cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path,
                editedFields = setOf(MediaConvertMetadataField.TITLE, MediaConvertMetadataField.ARTIST,
                    MediaConvertMetadataField.ALBUM)))
            PlayerTrackMetadataReader.invalidate(track.path)
        }

        fun openMain(tracks: List<File>): MainSession = MainSession(ActivityScenario.launch(MainActivity::class.java)).also { session ->
            onMain { session.model.openQueue(tracks.map { PlayerTrack(it.path, it.name, false) }, 0) }
            awaitState(session.model, "audio-mode native output starts") { it.ready && it.playing && it.positionMs > 250L }
        }

        override fun close() {
            MediaLibraryRepository.getInstance(app).removeDirectoryFromLibrary(root, emptyList())
            val editor = preferences.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            root.walkTopDown().filter { it.isFile }.forEach {
                editor.remove("last_position_${it.path}")
                PlayerTrackMetadataReader.invalidate(it.path)
            }
            assertTrue(editor.commit())
            root.deleteRecursively()
        }
    }

    private inner class MainSession(val scenario: ActivityScenario<MainActivity>) : Closeable {
        lateinit var activity: MainActivity
        lateinit var model: PlayerViewModel
        init { scenario.onActivity { activity = it; model = ViewModelProvider(it)[PlayerViewModel::class.java] } }
        override fun close() {
            try {
                onMain { model.closeSession() }
                await("closing the queue removes its notification") { notification() == null }
            } finally { scenario.close() }
        }
    }

    private fun notification(): Notification? = notifications.activeNotifications
        .firstOrNull { it.id == PlayerMediaNotifications.NOTIFICATION_ID }?.notification

    @Suppress("DEPRECATION")
    private fun sessionToken(): MediaSession.Token? = notification()?.extras?.getParcelable(Notification.EXTRA_MEDIA_SESSION)

    private fun awaitController(): MediaController {
        var token: MediaSession.Token? = null
        await("system media notification exposes a session token") { token = sessionToken(); token != null }
        return MediaController(app, requireNotNull(token))
    }

    private fun artwork(metadata: MediaMetadata?): Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    private fun isRed(bitmap: Bitmap?): Boolean = bitmap?.let {
        val pixel = it.getPixel(it.width / 2, it.height / 2)
        Color.red(pixel) > 220 && Color.green(pixel) < 40 && Color.blue(pixel) < 40
    } ?: false

    private fun awaitPaused(model: PlayerViewModel) = awaitState(model, "native output acknowledges system Pause") {
        it.ready && !it.playing && MPVLib.getPropertyBoolean("pause") == true
    }

    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(action()) }
        return result.get()
    }

    private fun awaitState(model: PlayerViewModel, label: String, predicate: (PlayerUiState) -> Boolean) = await(label) {
        mainValue {
            val current = model.state.value
            current.error?.let { throw AssertionError("$label: $it") }
            predicate(current)
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(75L)
        }
        throw AssertionError("Timed out: $label")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun captureNotificationIfRequested() {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        shell("cmd statusbar expand-notifications")
        try {
            Thread.sleep(500L)
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
            try { File(app.getExternalFilesDir(null), "player-system-media-notification.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { bitmap.recycle() }
        } finally { shell("cmd statusbar collapse") }
    }

    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            else -> error("Unexpected preference value for $key")
        }
    }

    private companion object {
        const val TITLE = "System notification title"
        const val ARTIST = "Notification artist"
        const val ALBUM = "Notification album"
    }
}

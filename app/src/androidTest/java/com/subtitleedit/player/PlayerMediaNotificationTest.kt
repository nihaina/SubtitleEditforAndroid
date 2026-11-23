package com.subtitleedit.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.MainActivity
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.R
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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

    @Test fun optedInNotificationPublishesTheCurrentLyricAndClearsItWhenDisabled() = Fixture().use { f ->
        val track = f.wav("with-lyrics.wav")
        File(track.parentFile, "with-lyrics.srt").writeText(
            "1\n00:00:00,000 --> 00:00:25,000\nFirst lyric\n\n" +
                "2\n00:00:30,000 --> 00:00:45,000\nSecond lyric\n",
        )
        f.openMain(listOf(track)).use { session ->
            val model = session.model
            val controller = awaitController()
            awaitState(model, "the first lyric is active") { it.subtitleText == "First lyric" }
            onMain { controller.transportControls.pause() }
            awaitPaused(model)
            assertNull(controller.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
            assertNotEquals("First lyric", notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())

            val preferences = PlayerPreferences.getInstance(app)
            onMain { preferences.notificationsShowLyrics = true }
            await("opt-in publishes the lyric on the current paused session") {
                controller.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) == "First lyric" &&
                    controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) == "First lyric" &&
                    notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "First lyric"
            }
            assertEquals("with-lyrics", controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
            assertEquals(controller.sessionToken, sessionToken())

            onMain { controller.transportControls.seekTo(32_000L) }
            await("a paused system seek publishes the new lyric") {
                controller.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) == "Second lyric" &&
                    notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Second lyric"
            }

            onMain { controller.transportControls.seekTo(27_000L) }
            await("a gap clears the previous lyric") {
                controller.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) == null &&
                    notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() != "Second lyric"
            }

            onMain { controller.transportControls.seekTo(32_000L) }
            await("lyrics return after the gap") {
                controller.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) == "Second lyric"
            }
            onMain { preferences.notificationsShowLyrics = false }
            await("disabling the preference clears the lyric without restarting playback") {
                controller.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) == null &&
                    controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank() &&
                    notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() != "Second lyric"
            }
            assertEquals(controller.sessionToken, sessionToken())
        }
    }

    @Test fun enabledFloatingLyricsShowOverHomeAndDisappearWhenTurnedOff() {
        assumeTrue("Grant Display over other apps to the test build", Settings.canDrawOverlays(app))
        Fixture().use { f ->
            val track = f.wav("desktop.wav")
            File(track.parentFile, "desktop.srt").writeText(
                "1\n00:00:00,000 --> 00:00:25,000\nDesktop first lyric\n\n" +
                    "2\n00:00:30,000 --> 00:00:55,000\nDesktop next lyric\n",
            )
            f.openMain(listOf(track)).use { session ->
                val model = session.model
                val controller = awaitController()
                val preferences = PlayerPreferences.getInstance(app)
                awaitState(model, "the first lyric is active while the switch is off") { it.subtitleText == "Desktop first lyric" }
                assertFalse(isFloatingLyricOnScreen())

                onMain { preferences.floatingSubtitleEnabled = true }
                await("the enabled caption is visible over the player") { isFloatingLyricOnScreen() }
                onMain { model.setLyricsVisible(true) }
                await("detailed lyrics hide the floating caption over the player") { !isFloatingLyricOnScreen() }

                shell("input keyevent KEYCODE_HOME")
                await("the player leaves the foreground") {
                    mainValue { !session.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                }
                await("the real overlay becomes visible over the desktop") { isFloatingLyricOnScreen() }

                onMain { controller.transportControls.seekTo(32_000L) }
                await("the player continues to publish the active lyric on the desktop") {
                    PlayerMediaNotifications.state.value?.floatingSubtitleText == "Desktop next lyric" &&
                        isFloatingLyricOnScreen()
                }
                assertNull("The notification lyric switch is independent", controller.metadata?.getString(
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                ))

                onMain { controller.transportControls.seekTo(27_000L) }
                await("a lyric gap hides the overlay on the desktop") { !isFloatingLyricOnScreen() }
                onMain { controller.transportControls.seekTo(32_000L) }
                await("a new cue restores the subtitle window") { isFloatingLyricOnScreen() }

                onMain { preferences.floatingSubtitleEnabled = false }
                await("disabling removes the overlay on the desktop") { !isFloatingLyricOnScreen() }
                onMain { preferences.floatingSubtitleEnabled = true }
                await("re-enabling brings back the caption") { isFloatingLyricOnScreen() }
            }
            await("closing playback removes the overlay") { !isFloatingLyricOnScreen() }
        }
    }

    @Test fun statusBarSubtitlesResizeAcrossThePhysicalScreenAndStayInTheUpperThird() {
        assumeTrue("Grant Display over other apps to the test build", Settings.canDrawOverlays(app))
        val preferences = PlayerPreferences.getInstance(app)
        val original = preferences.statusBarLyrics
        try {
            Fixture().use { f ->
                val track = f.wav("status-lyric.wav")
                File(track.parentFile, "status-lyric.srt").writeText(
                    "1\n00:00:00,000 --> 00:00:25,000\nStatus bar lyric\n",
                )
                onMain { preferences.statusBarLyrics = PlayerStatusBarLyricsSettings() }
                f.openMain(listOf(track)).use { session ->
                    awaitState(session.model, "the current cue has timed boundaries") {
                        it.subtitleText == "Status bar lyric" && it.subtitleCueTiming == PlayerSubtitleCueTiming(0L, 25_000L)
                    }
                    val controller = awaitController()
                    assertNull(captionWindow("PlayerStatusBarLyrics"))
                    @Suppress("DEPRECATION")
                    val screenSize = Point().also(app.getSystemService(WindowManager::class.java).defaultDisplay::getRealSize)
                    onMain { preferences.statusBarLyrics = PlayerStatusBarLyricsSettings(enabled = true) }
                    shell("input keyevent KEYCODE_HOME")
                    var nearTop: Rect? = null
                    await("the status bar window is visible and passes touches through") {
                        val window = captionWindow("PlayerStatusBarLyrics")
                        nearTop = window?.let(::windowFrame)
                        window?.contains("NOT_TOUCHABLE") == true && nearTop != null
                    }
                    assertEquals("Full width reaches the physical left edge", 0, requireNotNull(nearTop).left)
                    assertEquals("Full width reaches the physical right edge", screenSize.x, requireNotNull(nearTop).right)
                    assertFalse(isFloatingLyricOnScreen())
                    assertNull(controller.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))

                    onMain { preferences.statusBarLyrics = preferences.statusBarLyrics.copy(widthPercent = 60) }
                    await("the width slider resizes the window around the center of the physical display") {
                        captionWindow("PlayerStatusBarLyrics")?.let(::windowFrame)?.let { frame ->
                            abs(frame.width() - screenSize.x * .6) <= 1 && abs(frame.left + frame.right - screenSize.x) <= 1
                        } == true
                    }
                    onMain { preferences.statusBarLyrics = preferences.statusBarLyrics.copy(verticalPositionPercent = 100) }
                    val upperThird = screenSize.y / 3
                    var lowered: Rect? = null
                    await("the slider moves the status bar window downward from $nearTop") {
                        lowered = captionWindow("PlayerStatusBarLyrics")?.let(::windowFrame)
                        lowered?.top?.let { it > requireNotNull(nearTop).top } == true
                    }
                    assertTrue("Frame $lowered must end within the upper third of $screenSize",
                        requireNotNull(lowered).bottom <= upperThird + 2)
                    onMain { preferences.statusBarLyrics = preferences.statusBarLyrics.copy(enabled = false) }
                    await("disabling status bar lyrics removes only that window") { captionWindow("PlayerStatusBarLyrics") == null }
                    assertEquals(controller.sessionToken, sessionToken())
                }
            }
        } finally {
            onMain { preferences.statusBarLyrics = original }
        }
    }

    @Test fun unlockedFloatingLyricsCanBeMovedAndControlledFromHome() {
        assumeTrue("Grant Display over other apps to the test build", Settings.canDrawOverlays(app))
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val originalFlags = info.flags
        info.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val preferences = PlayerPreferences.getInstance(app)
        val originalLocked = preferences.floatingSubtitleLocked
        val originalStyle = preferences.floatingSubtitleStyle
        val originalPosition = preferences.floatingSubtitlePosition
        try {
            Fixture().use { f ->
                val tracks = listOf(f.wav("overlay-first.wav"), f.wav("overlay-second.wav"))
                val lyrics = listOf("Movable desktop caption", "Caption from next track")
                tracks.zip(lyrics).forEach { (track, lyric) ->
                    File(track.parentFile, "${track.nameWithoutExtension}.srt")
                        .writeText("1\n00:00:00,000 --> 00:01:00,000\n$lyric\n")
                }
                f.openMain(tracks).use { session ->
                    val model = session.model
                    val controller = awaitController()
                    onMain {
                        model.pause()
                        preferences.floatingSubtitleLocked = false
                        preferences.floatingSubtitleStyle = PlayerFloatingSubtitleStyle()
                        preferences.floatingSubtitlePosition = null
                        preferences.floatingSubtitleEnabled = true
                    }
                    awaitPaused(model)
                    awaitState(model, "desktop caption is active") { it.subtitleText == lyrics[0] }
                    shell("input keyevent KEYCODE_HOME")
                    await("the player leaves the foreground") {
                        mainValue { !session.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                    }

                    val originalBounds = floatingCaptionBounds(lyrics[0])
                    val moveBy = (96 * app.resources.displayMetrics.density).toInt()
                    val x = originalBounds.centerX()
                    val y = originalBounds.centerY()
                    val initialPosition = preferences.floatingSubtitlePosition
                    shell("input swipe $x $y ${x + moveBy} $y 450")
                    assertEquals("A horizontal swipe keeps the floating caption in place",
                        originalBounds, floatingCaptionBounds(lyrics[0]))
                    assertEquals(initialPosition, preferences.floatingSubtitlePosition)
                    assertNull("A horizontal swipe must not open the controls", floatingNodeBounds {
                        it.contentDescription?.toString() == app.getString(R.string.player_floating_subtitle_lock)
                    })
                    onMain { preferences.floatingSubtitleStyle = preferences.floatingSubtitleStyle.copy(leftMarginPercent = 0) }
                    await("0 percent aligns the text to the left inside the window") {
                        floatingNodeBounds { it.text?.toString() == lyrics[0] }?.centerX()?.let { it < x } == true
                    }
                    onMain { preferences.floatingSubtitleStyle = preferences.floatingSubtitleStyle.copy(leftMarginPercent = 100) }
                    await("100 percent aligns the text to the right inside the window") {
                        floatingNodeBounds { it.text?.toString() == lyrics[0] }?.centerX()?.let { it > x } == true
                    }
                    onMain { preferences.floatingSubtitleStyle = preferences.floatingSubtitleStyle.copy(leftMarginPercent = 50) }
                    await("50 percent centers the text again") {
                        floatingNodeBounds { it.text?.toString() == lyrics[0] } == originalBounds
                    }
                    shell("input swipe $x $y ${x + moveBy} ${y - moveBy} 450")
                    await("drag saves a new position and moves the real window") {
                        preferences.floatingSubtitlePosition != null &&
                            floatingNodeBounds { it.text?.toString() == lyrics[0] }?.let {
                                it.centerX() == x && it.centerY() < y - moveBy / 2
                            } == true
                    }
                    val draggedPosition = preferences.floatingSubtitlePosition
                    tap(floatingCaptionBounds(lyrics[0]))
                    val lock = floatingControlBounds(R.string.player_floating_subtitle_lock)
                    val play = floatingControlBounds(R.string.player_play)
                    val close = floatingControlBounds(R.string.player_floating_subtitle_close)
                    assertTrue(lock.centerX() < play.centerX() && play.centerX() < close.centerX())
                    assertTrue("Lock and Close flank the three transport buttons symmetrically",
                        abs(lock.centerX() + close.centerX() - 2 * play.centerX()) <= 4)

                    val defaultSize = preferences.floatingSubtitleStyle.fontSize
                    tap(floatingControlBounds(R.string.player_floating_subtitle_larger))
                    await("plus increases the persisted font size") { preferences.floatingSubtitleStyle.fontSize > defaultSize }
                    tap(floatingControlBounds(R.string.player_floating_subtitle_smaller))
                    await("minus restores the original font size") { preferences.floatingSubtitleStyle.fontSize == defaultSize }
                    val colorPalette = floatingControlBounds(R.string.player_floating_subtitle_font_color)
                    val backgroundPalette = floatingControlBounds(R.string.player_floating_subtitle_background_color)
                    assertTrue(colorPalette.centerX() < backgroundPalette.centerX())
                    assertEquals(colorPalette.centerY(), backgroundPalette.centerY())
                    tap(colorPalette)
                    setFloatingColorHex("#FF44CC88")
                    await("confirming the color picker persists the custom color") {
                        preferences.floatingSubtitleStyle.colorArgb == Color.rgb(0x44, 0xCC, 0x88)
                    }
                    tap(floatingControlBounds(R.string.player_floating_subtitle_background_color))
                    val backgroundTitle = app.getString(R.string.player_floating_subtitle_background_color)
                    awaitFloatingBounds(backgroundTitle) { it.text?.toString() == backgroundTitle }
                    setFloatingColorHex("#66441199")
                    await("confirming the background picker persists its custom RGB and opacity") {
                        preferences.floatingSubtitleStyle.backgroundColorArgb == Color.argb(0x66, 0x44, 0x11, 0x99)
                    }
                    assertEquals(Color.rgb(0x44, 0xCC, 0x88), preferences.floatingSubtitleStyle.colorArgb)

                    tap(floatingControlBounds(R.string.player_play))
                    awaitState(model, "the window resumes native playback") { it.ready && it.playing }
                    tap(floatingControlBounds(R.string.player_pause))
                    awaitPaused(model)
                    tap(floatingControlBounds(R.string.player_next))
                    awaitState(model, "the window advances the same native queue") {
                        it.currentTrack?.path == tracks[1].path && it.subtitleText == lyrics[1]
                    }
                    onMain { controller.transportControls.seekTo(0L) }
                    awaitState(model, "previous will start from the beginning of the second track") { it.positionMs < 500L }
                    openFloatingControlsIfClosed(lyrics[1])
                    tap(floatingControlBounds(R.string.player_previous))
                    awaitState(model, "the window returns to the previous track") {
                        it.currentTrack?.path == tracks[0].path && it.subtitleText == lyrics[0]
                    }

                    openFloatingControlsIfClosed(lyrics[0])
                    tap(floatingControlBounds(R.string.player_floating_subtitle_lock))
                    await("locking keeps the caption visible and lets touches pass through") {
                        preferences.floatingSubtitleLocked && floatingLyricWindow()?.contains("NOT_TOUCHABLE") == true
                    }
                    assertEquals(draggedPosition, preferences.floatingSubtitlePosition)

                    onMain { preferences.floatingSubtitleLocked = false }
                    await("unlocking restores touch support") {
                        floatingLyricWindow()?.let { "NOT_TOUCHABLE" !in it } == true
                    }
                    tap(floatingCaptionBounds(lyrics[0]))
                    tap(floatingControlBounds(R.string.player_floating_subtitle_close))
                    await("Close turns off the preference and removes the window") {
                        !preferences.floatingSubtitleEnabled && !isFloatingLyricOnScreen()
                    }
                    assertEquals(tracks[0].path, state(model).currentTrack?.path)
                    assertEquals(controller.sessionToken, sessionToken())
                    assertEquals(draggedPosition, preferences.floatingSubtitlePosition)
                }
            }
        } finally {
            onMain {
                preferences.floatingSubtitleLocked = originalLocked
                preferences.floatingSubtitleStyle = originalStyle
                preferences.floatingSubtitlePosition = originalPosition
            }
            info.flags = originalFlags
            automation.serviceInfo = info
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

    @Test fun restartingAudioModeRestoresTheSelectedQueueEntryAndPositionPaused(): Unit = Fixture(resumePlayback = true).use { f ->
        val repeated = f.wav("repeated.wav")
        val queue = listOf(repeated, f.wav("other.wav"), repeated)
        val paths = queue.map { it.path }
        lateinit var previousModel: PlayerViewModel
        var exitPosition = 0L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                previousModel = ViewModelProvider(activity)[PlayerViewModel::class.java]
                previousModel.openQueue(queue.map { PlayerTrack(it.path, it.name, false) }, startIndex = 2,
                    allowDuplicates = true)
            }
            awaitState(previousModel, "the second occurrence of the repeated audio file is playing") {
                it.ready && it.playing && it.currentIndex == 2 && it.positionMs > 250L
            }
            onMain { previousModel.seekTo(12_000L) }
            awaitState(previousModel, "native playback reaches the seek target") {
                it.playing && it.positionMs >= 12_000L && (MPVLib.getPropertyDouble("time-pos") ?: 0.0) >= 12.0
            }
            scenario.onActivity { activity ->
                exitPosition = previousModel.state.value.positionMs
                activity.finish()
            }
        }
        await("the previous player's notification is removed") { notification() == null }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var restoredModel: PlayerViewModel
            scenario.onActivity { restoredModel = ViewModelProvider(it)[PlayerViewModel::class.java] }
            assertNotSame(previousModel, restoredModel)
            awaitState(restoredModel, "the same queue occurrence and position are restored with native output paused") {
                it.ready && !it.playing && it.currentIndex == 2 && it.playlist.map { track -> track.path } == paths &&
                    abs(it.positionMs - exitPosition) <= 1_000L && MPVLib.getPropertyString("path") == repeated.path &&
                    MPVLib.getPropertyBoolean("pause") == true &&
                    abs((MPVLib.getPropertyDouble("time-pos") ?: 0.0) * 1_000 - exitPosition) <= 1_000
            }
            val restoredPosition = state(restoredModel).positionMs
            val controller = awaitController()
            await("the restored notification reports paused playback") {
                controller.playbackState?.state == PlaybackState.STATE_PAUSED
            }
            onMain { controller.transportControls.play() }
            awaitState(restoredModel, "manual Play resumes from the restored position") {
                it.playing && it.positionMs > restoredPosition + 250L
            }
            onMain { controller.transportControls.stop() }
            await("an explicit Stop clears the player's active queue") {
                state(restoredModel).currentTrack == null && notification() == null
            }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertNull(ViewModelProvider(it)[PlayerViewModel::class.java].state.value.currentTrack) }
        }
    }

    @Test fun concurrentPlaybackReleasesAudioFocusAndRestoresItWhenDisabled() = Fixture().use { f ->
        val preferences = PlayerPreferences.getInstance(app)
        val audio = app.getSystemService(AudioManager::class.java)
        val competitorLostFocus = AtomicBoolean(false)
        val competitor = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { if (it < 0) competitorLostFocus.set(true) }
            .build()
        f.openMain(listOf(f.wav("concurrent.wav"))).use { session ->
            val model = session.model
            fun requestCompetingFocus() = assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                mainValue { audio.requestAudioFocus(competitor) })

            try {
                requestCompetingFocus()
                awaitPaused(model)

                onMain { preferences.allowConcurrentPlayback = true; model.play() }
                val startedAt = state(model).positionMs
                awaitState(model, "audio advances alongside the competing focus owner") {
                    it.playing && it.positionMs > startedAt + 500L
                }
                assertFalse("Starting playback leaves the other client focused", competitorLostFocus.get())

                onMain { preferences.allowConcurrentPlayback = false }
                await("disabling concurrent playback reclaims focus") { competitorLostFocus.get() }
                requestCompetingFocus()
                awaitPaused(model)
            } finally {
                onMain { audio.abandonAudioFocusRequest(competitor) }
            }
        }
    }

    private inner class Fixture(audioMode: Boolean = true, resumePlayback: Boolean = false) : Closeable {
        val root = File(app.cacheDir, "player-media-notification-${System.nanoTime()}").apply { check(mkdirs()) }
        private val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val keys = listOf("player_mode_enabled", "default_play_mode", "default_speed", "resume_playback",
            "eq_enabled", "show_loudness_waveform", "end_behavior_enabled", "notification_lyrics", "floating_subtitle_enabled",
            "allow_concurrent_playback")
        private val saved = keys.associateWith { preferences.all[it] }
        private val sessionPreferences = app.getSharedPreferences("main_player_session", Context.MODE_PRIVATE)
        private val savedSession = sessionPreferences.getString("session", null)
        private val repository = MediaLibraryRepository.getInstance(app)
        private val savedQueue = runBlocking { PlayerLibraryWriter.execute(repository::getCurrentQueue) }

        init {
            assertTrue(sessionPreferences.edit().remove("session").commit())
            assertTrue(preferences.edit().putBoolean("player_mode_enabled", audioMode)
                .putString("default_play_mode", PlayerPlayMode.SEQUENCE.name).putFloat("default_speed", 1f)
                .putBoolean("resume_playback", resumePlayback).putBoolean("eq_enabled", false)
                .putBoolean("show_loudness_waveform", false).putBoolean("end_behavior_enabled", false)
                .putBoolean("allow_concurrent_playback", false)
                .putBoolean("notification_lyrics", false).putBoolean("floating_subtitle_enabled", false).commit())
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
            runBlocking {
                PlayerLibraryWriter.execute {
                    repository.removeDirectoryFromLibrary(root, emptyList())
                    root.walkTopDown().filter { it.isFile }.forEach { repository.removePlaybackHistory(it.path) }
                    repository.saveCurrentQueue(savedQueue.first.map { it.path }, savedQueue.second, allowDuplicates = true)
                }
            }
            val editor = preferences.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            root.walkTopDown().filter { it.isFile }.forEach {
                editor.remove("last_position_${it.path}")
                PlayerTrackMetadataReader.invalidate(it.path)
            }
            assertTrue(editor.commit())
            assertTrue(sessionPreferences.edit().putString("session", savedSession).commit())
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

    private fun captionWindow(title: String): String? {
        val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys window windows").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
        return dump.split(Regex("(?m)^\\s*Window #\\d+ ")).firstOrNull { window ->
            window.lineSequence().first().contains(title) &&
                "package=${app.packageName} " in window && "isOnScreen=true" in window &&
                ("ty=APPLICATION_OVERLAY" in window || "ty=2038" in window)
        }
    }

    private fun floatingLyricWindow(): String? = captionWindow("PlayerFloatingSubtitle")

    private fun windowFrame(dump: String): Rect? {
        val frame = Regex("\\bframe=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").find(dump) ?: return null
        val (left, top, right, bottom) = frame.destructured
        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    private fun isFloatingLyricOnScreen(): Boolean = floatingLyricWindow() != null

    private fun tap(bounds: Rect) = shell("input tap ${bounds.centerX()} ${bounds.centerY()}")

    private fun floatingCaptionBounds(text: String): Rect = awaitFloatingBounds(text) { it.text?.toString() == text }

    private fun openFloatingControlsIfClosed(caption: String) {
        val captionBounds = floatingCaptionBounds(caption)
        val lockLabel = app.getString(R.string.player_floating_subtitle_lock)
        if (floatingNodeBounds { it.contentDescription?.toString() == lockLabel } == null) tap(captionBounds)
    }

    private fun floatingControlBounds(@StringRes id: Int): Rect {
        val description = app.getString(id)
        return awaitFloatingBounds(description) { it.contentDescription?.toString() == description }
    }

    private fun setFloatingColorHex(hex: String) {
        await("the existing color picker exposes an editable HEX value") {
            floatingNode { it.isEditable && it.contentDescription?.toString() == "HEX" }
                ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, hex)
                }) == true
        }
        tap(awaitFloatingBounds("color confirmation") { it.text?.toString() == "确定" })
    }

    private fun awaitFloatingBounds(label: String, matches: (AccessibilityNodeInfo) -> Boolean): Rect {
        var result: Rect? = null
        await("floating window provides $label") {
            result = floatingNodeBounds(matches)
            result != null
        }
        return requireNotNull(result)
    }

    private fun floatingNodeBounds(matches: (AccessibilityNodeInfo) -> Boolean): Rect? =
        floatingNode { matches(it) && !Rect().also(it::getBoundsInScreen).isEmpty }
            ?.let { Rect().also(it::getBoundsInScreen) }

    private fun floatingNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        automation.windows.mapNotNull { it.root }
            .filterTo(pending) { it.packageName?.toString() == app.packageName && it.refresh() }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && matches(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return null
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

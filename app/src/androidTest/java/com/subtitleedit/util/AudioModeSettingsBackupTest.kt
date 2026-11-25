package com.subtitleedit.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.MediaCreditRole
import com.subtitleedit.library.SingerSortField
import com.subtitleedit.player.PlayerEndBehavior
import com.subtitleedit.player.PlayerActivity
import com.subtitleedit.player.PlayerEqualizerState
import com.subtitleedit.player.PlayerFloatingSubtitlePosition
import com.subtitleedit.player.PlayerFloatingSubtitleStyle
import com.subtitleedit.player.PlayerFloatingSubtitleWeight
import com.subtitleedit.player.PlayerPlayMode
import com.subtitleedit.player.PlayerPreferences
import com.subtitleedit.player.PlayerQueueEndAction
import com.subtitleedit.player.PlayerStatusBarLyricsSettings
import com.subtitleedit.player.PlayerSubtitleAlignment
import com.subtitleedit.player.PlayerTrackEndAction
import com.subtitleedit.player.PlayerUsbVolumeMode
import com.subtitleedit.player.PlayerViewModel
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.ui.AlbumGridSize
import com.subtitleedit.ui.AlbumViewMode
import com.subtitleedit.ui.MediaAlbumPreferences
import com.subtitleedit.ui.MediaAlbumViewOptions
import com.subtitleedit.ui.MediaSingerPreferences
import com.subtitleedit.ui.MediaSingerViewOptions
import com.subtitleedit.ui.SingerMoreInfo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Executes the production JSON export/restore path against Android SharedPreferences. */
@RunWith(AndroidJUnit4::class)
class AudioModeSettingsBackupTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val player get() = PlayerPreferences.getInstance(app)
    private val albums get() = MediaAlbumPreferences(app)
    private val preferenceNames = listOf("player_settings", "media_album_view", "media_singer_view",
        "media_artist_view", "media_composer_view", "media_genre_view")
    private lateinit var original: Map<String, Map<String, *>>

    @Before fun savePreferences() {
        original = preferenceNames.associateWith { prefs(it).all.toMap() }
        preferenceNames.forEach { assertTrue(prefs(it).edit().clear().commit()) }
    }

    @After fun restorePreferences() {
        original.forEach { (name, values) ->
            val editor = prefs(name).edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            assertTrue(editor.commit())
        }
    }

    @Test fun allSixGroupsRoundTripWithIndependentCategorySettings() {
        setCustomPlayerSettings()
        val albumOptions = MediaAlbumViewOptions(
            viewMode = AlbumViewMode.LIST_NO_ART, gridSize = AlbumGridSize.SMALL,
            showMoreInfo = true, groupTracks = false, quickSearch = false,
            sortField = AlbumSortField.YEAR, ascending = false,
            trackSort = AlbumTrackSortOptions(AlbumTrackSortField.DURATION, false)
        )
        albums.save(albumOptions)
        val categories = MediaCreditRole.entries.mapIndexed { index, role ->
            role to MediaSingerViewOptions(
                moreInfo = SingerMoreInfo.entries[index + 1], groupTracks = index % 2 == 0,
                quickSearch = index % 2 != 0, sortField = SingerSortField.entries[index],
                ascending = index % 2 == 0, albumSortField = AlbumSortField.entries[index + 1],
                albumAscending = index % 2 != 0,
                trackSort = AlbumTrackSortOptions(AlbumTrackSortField.entries[index], false)
            )
        }.toMap()
        categories.forEach { (role, options) -> MediaSingerPreferences(app, role).save(options) }
        player.saveLastPosition("/not-backed-up.wav", 12_345L)
        player.floatingSubtitlePosition = PlayerFloatingSubtitlePosition(0.9f)

        val backup = exportAudioSettings()
        assertEquals(setOf("player", "albums", "singers", "artists", "composers", "genres"), backup.keySet())
        assertEquals(36, backup.getJSONObject("player").length())
        listOf("albums", "singers", "artists", "composers", "genres").forEach {
            assertEquals(9, backup.getJSONObject(it).length())
        }
        assertEquals("float", backup.getJSONObject("player").getJSONObject("default_speed").getString("type"))
        assertEquals("boolean", backup.getJSONObject("player").getJSONObject("allow_concurrent_playback").getString("type"))
        assertEquals(true, backup.value("player", "allow_concurrent_playback"))
        assertEquals(true, backup.value("player", "usb_exclusive_enabled"))
        assertFalse(backup.toString().contains("last_position_"))
        assertFalse(backup.toString().contains("floating_subtitle_position_"))
        preferenceNames.forEach { assertTrue(prefs(it).edit().clear().commit()) }
        player.saveLastPosition("/not-backed-up.wav", 98_765L)
        val destinationPosition = PlayerFloatingSubtitlePosition(0.25f)
        player.floatingSubtitlePosition = destinationPosition

        restoreAudioSettings(backup)

        assertCustomPlayerSettings()
        assertEquals(albumOptions, albums.load())
        categories.forEach { (role, options) -> assertEquals(role.name, options, MediaSingerPreferences(app, role).load()) }
        assertEquals(98_765L, player.lastPosition("/not-backed-up.wav"))
        assertEquals(destinationPosition, PlayerPreferences(prefs("player_settings")).floatingSubtitlePosition)
    }

    @Test fun untouchedDefaultsAreExportedAndReplaceDifferentDestinationValues() {
        val defaults = exportAudioSettings()
        assertEquals(false, defaults.value("player", "player_mode_enabled"))
        assertEquals(false, defaults.value("player", "allow_concurrent_playback"))
        assertEquals(false, defaults.value("player", "usb_exclusive_enabled"))
        assertEquals(true, defaults.value("player", "resume_playback"))
        assertEquals(true, defaults.value("player", "show_loudness_waveform"))
        assertEquals(1.0, defaults.getJSONObject("player").getJSONObject("default_speed").getDouble("value"), 0.0)
        assertEquals(20, defaults.value("player", "subtitle_font_size"))
        assertEquals(24, defaults.value("player", "audio_subtitle_line_spacing"))
        assertEquals("CENTER", defaults.value("player", "audio_subtitle_alignment"))
        assertEquals(false, defaults.value("player", "floating_subtitle_enabled"))
        assertEquals(false, defaults.value("player", "floating_subtitle_locked"))
        assertEquals(20, defaults.value("player", "floating_subtitle_size"))
        assertEquals(50, defaults.value("player", "floating_subtitle_left_margin"))
        assertEquals("NORMAL", defaults.value("player", "floating_subtitle_weight"))
        assertEquals(android.graphics.Color.WHITE, defaults.value("player", "floating_subtitle_color"))
        assertEquals(0xCCFFFFFF.toInt(), defaults.value("player", "floating_subtitle_background_color"))
        assertEquals(android.graphics.Color.TRANSPARENT, defaults.value("player", "floating_subtitle_outline_color"))
        assertEquals(false, defaults.value("player", "floating_subtitle_scroll"))
        assertEquals(false, defaults.value("player", "status_bar_lyrics_enabled"))
        assertEquals(14, defaults.value("player", "status_bar_lyrics_size"))
        assertEquals(android.graphics.Color.BLACK, defaults.value("player", "status_bar_lyrics_outline_color"))
        assertEquals(false, defaults.value("player", "status_bar_lyrics_scroll"))
        assertEquals(0, defaults.value("player", "status_bar_lyrics_position"))
        assertEquals(100, defaults.value("player", "status_bar_lyrics_width"))
        assertEquals(false, defaults.value("player", "notification_lyrics"))
        assertEquals("GRID", defaults.value("albums", "view_mode"))
        assertEquals(true, defaults.value("albums", "group_tracks"))
        setCustomPlayerSettings()
        albums.save(MediaAlbumViewOptions(viewMode = AlbumViewMode.LIST_ART, groupTracks = false))
        MediaCreditRole.entries.forEach { MediaSingerPreferences(app, it).save(MediaSingerViewOptions(quickSearch = false)) }

        restoreAudioSettings(defaults)

        assertFalse(player.playerModeEnabled)
        assertFalse(player.allowConcurrentPlayback)
        assertFalse(player.usbExclusiveEnabled)
        assertEquals(PlayerUsbVolumeMode.SOFTWARE, player.usbVolumeMode)
        assertEquals(PlayerPlayMode.REPEAT_ALL, player.defaultPlayMode)
        assertEquals(PlayerEndBehavior(), player.endBehavior)
        assertEquals(1f, player.defaultSpeed, 0f)
        assertEquals(PlayerEqualizerState(), player.equalizer)
        assertEquals(PlayerFloatingSubtitleStyle(), player.floatingSubtitleStyle)
        assertEquals(PlayerStatusBarLyricsSettings(), player.statusBarLyrics)
        assertFalse(player.floatingSubtitleEnabled)
        assertFalse(player.floatingSubtitleLocked)
        assertFalse(player.notificationsShowLyrics)
        assertEquals(MediaAlbumViewOptions(), albums.load())
        MediaCreditRole.entries.forEach { assertEquals(MediaSingerViewOptions(), MediaSingerPreferences(app, it).load()) }
    }

    @Test fun legacyBackupDoesNotOverwriteAnyAudioSettings() {
        setCustomPlayerSettings()
        val before = exportAudioSettings().toString()

        SettingsBackupManager.restore(app, JSONObject().put("schemaVersion", 1).toString())

        assertEquals(before, exportAudioSettings().toString())
        assertCustomPlayerSettings()
    }

    @Test fun olderFloatingSubtitleAlignmentAndVerticalPositionStillLoad() {
        val raw = prefs("player_settings")
        assertTrue(raw.edit()
            .putString("floating_subtitle_alignment", "LEFT")
            .putFloat("floating_subtitle_position_x", .8f)
            .putFloat("floating_subtitle_position_y", .3f)
            .commit())
        assertEquals(0, player.floatingSubtitleStyle.leftMarginPercent)
        assertEquals(PlayerFloatingSubtitlePosition(.3f), player.floatingSubtitlePosition)

        player.floatingSubtitleStyle = player.floatingSubtitleStyle.copy(leftMarginPercent = 63)
        restoreAudioSettings(JSONObject().put("player", JSONObject()
            .put("floating_subtitle_alignment", typed("string", "RIGHT"))))
        assertEquals(100, player.floatingSubtitleStyle.leftMarginPercent)
        assertEquals(PlayerFloatingSubtitlePosition(.3f), player.floatingSubtitlePosition)
    }

    @Test fun partialBackupMergesOnlyKnownCorrectlyTypedKeysInTheirOwnGroup() {
        setCustomPlayerSettings()
        val artist = MediaSingerViewOptions(quickSearch = false, moreInfo = SingerMoreInfo.YEAR)
        MediaSingerPreferences(app, MediaCreditRole.ARTIST).save(artist)
        player.saveLastPosition("/kept.wav", 999L)
        restoreAudioSettings(JSONObject()
            .put("player", JSONObject()
                .put("default_speed", typed("float", 1.25))
                .put("subtitle_font_size", typed("string", "wrong type"))
                .put("status_bar_lyrics_position", typed("int", 150))
                .put("status_bar_lyrics_width", typed("int", 3))
                .put("status_bar_lyrics_scroll", typed("boolean", false))
                .put("status_bar_lyrics_background_color", typed("int", android.graphics.Color.WHITE))
                .put("resume_playback", typed("int", 1))
                .put("last_position_/kept.wav", typed("long", 0L))
                .put("unknown_setting", typed("boolean", true)))
            .put("singers", JSONObject().put("quick_search", typed("boolean", false)))
            .put("unrecognized_store", JSONObject().put("player_mode_enabled", typed("boolean", false))))

        assertEquals(1.25f, player.defaultSpeed, 0f)
        assertEquals(32, player.subtitleFontSize)
        assertEquals(customizedStatusBarLyrics.copy(
            style = customizedStatusBarLyrics.style.copy(scrollEnabled = false),
            verticalPositionPercent = 100,
            widthPercent = 10,
        ), player.statusBarLyrics)
        assertEquals(customizedFloatingSubtitle, player.floatingSubtitleStyle)
        assertFalse(prefs("player_settings").contains("status_bar_lyrics_background_color"))
        assertFalse(player.resumePlayback)
        assertTrue(player.playerModeEnabled)
        assertFalse(prefs("player_settings").contains("unknown_setting"))
        assertEquals(999L, player.lastPosition("/kept.wav"))
        assertFalse(MediaSingerPreferences(app).load().quickSearch)
        assertEquals(artist, MediaSingerPreferences(app, MediaCreditRole.ARTIST).load())
    }

    @Test fun existingPlayerReloadsRestoredSettingsWithoutRestarting() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var model: PlayerViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlayerViewModel::class.java]
        }
        try {
            instrumentation.runOnMainSync { model.setMode(PlayerPlayMode.SEQUENCE) }
            player.defaultPlayMode = PlayerPlayMode.REPEAT_ALL
            restoreAudioSettings(JSONObject().put("albums", JSONObject().put("quick_search", typed("boolean", false))))
            instrumentation.runOnMainSync { model.refreshRestoredSettings() }
            assertEquals("Restoring category settings must not change the active playback mode",
                PlayerPlayMode.SEQUENCE, model.state.value.mode)

            setCustomPlayerSettings()
            val backup = exportAudioSettings()
            preferenceNames.forEach { assertTrue(prefs(it).edit().clear().commit()) }
            restoreAudioSettings(backup)
            instrumentation.runOnMainSync { model.refreshRestoredSettings() }
            val state = model.state.value
            assertEquals(PlayerPlayMode.SHUFFLE, state.mode)
            assertEquals(player.endBehavior, state.endBehavior)
            assertEquals(2.5f, state.speed, 0f)
            assertEquals(player.equalizer, state.equalizer)
            assertEquals(32, state.subtitleFontSize)
            assertEquals(40, state.audioSubtitleLineSpacing)
            assertEquals(PlayerSubtitleAlignment.LEFT, state.audioSubtitleAlignment)
            assertFalse(state.resumePlayback)
            assertFalse(state.showLoudnessWaveform)
            assertTrue(state.playlist.isEmpty())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test fun returningToNativePlayerAppliesRestoreWithoutChangingTrackOrPosition() {
        val wav = File.createTempFile("settings-restore-", ".wav", app.cacheDir)
        val samples = 8_000 * 30
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1.toShort()); putShort(1.toShort()); putInt(8_000); putInt(16_000)
            putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray()); putInt(samples * 2)
        }
        wav.outputStream().use { it.write(header.array()); it.write(ByteArray(samples * 2)) }
        setCustomPlayerSettings()
        val backup = exportAudioSettings()
        assertTrue(prefs("player_settings").edit().clear().putBoolean("resume_playback", false).commit())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            ActivityScenario.launch<PlayerActivity>(Intent(app, PlayerActivity::class.java)
                .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(wav.path))).use { scenario ->
                lateinit var model: PlayerViewModel
                scenario.onActivity { model = ViewModelProvider(it)[PlayerViewModel::class.java] }
                await { model.state.value.ready && model.state.value.playing }
                instrumentation.runOnMainSync { model.pause(); model.seekTo(2_000L) }
                await { !model.state.value.playing && kotlin.math.abs(model.state.value.positionMs - 2_000L) < 200L }
                val before = model.state.value
                scenario.moveToState(Lifecycle.State.STARTED)

                restoreAudioSettings(backup)
                scenario.moveToState(Lifecycle.State.RESUMED)

                await { model.state.value.speed == 2.5f && model.state.value.subtitleFontSize == 32 }
                val after = model.state.value
                assertEquals(before.playlist, after.playlist)
                assertEquals(before.currentIndex, after.currentIndex)
                assertTrue(kotlin.math.abs(before.positionMs - after.positionMs) < 200L)
                assertFalse(after.playing)
                assertEquals(player.endBehavior, after.endBehavior)
                assertEquals(PlayerSubtitleAlignment.LEFT, after.audioSubtitleAlignment)
                instrumentation.runOnMainSync {
                    assertEquals(2.5, MPVLib.getPropertyDouble("speed")!!, 0.001)
                    assertEquals(wav.path, MPVLib.getPropertyString("path"))
                    assertTrue(MPVLib.getPropertyString("af").orEmpty().contains("firequalizer"))
                }
            }
        } finally {
            wav.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L)
        assertTrue("Timed out waiting for native playback state", condition())
    }

    private fun setCustomPlayerSettings() {
        player.playerModeEnabled = true
        player.allowConcurrentPlayback = true
        player.usbExclusiveEnabled = true
        player.usbVolumeMode = PlayerUsbVolumeMode.FIXED
        player.savePlaybackBehavior(PlayerPlayMode.SHUFFLE, PlayerEndBehavior(
            enabled = true, trackAction = PlayerTrackEndAction.LOAD_NEXT_PAUSED,
            queueAction = PlayerQueueEndAction.NEXT_QUEUE, wrapQueues = true, reshuffle = false
        ))
        player.defaultSpeed = 2.5f
        player.equalizer = PlayerEqualizerState(true, List(10) { it.toFloat() - 4f })
        player.subtitleFontSize = 32
        player.audioSubtitleLineSpacing = 40
        player.audioSubtitleAlignment = PlayerSubtitleAlignment.LEFT
        player.floatingSubtitleEnabled = true
        player.floatingSubtitleLocked = true
        player.floatingSubtitleStyle = customizedFloatingSubtitle
        player.statusBarLyrics = customizedStatusBarLyrics
        player.notificationsShowLyrics = true
        player.resumePlayback = false
        player.showLoudnessWaveform = false
    }

    private fun assertCustomPlayerSettings() {
        assertTrue(player.playerModeEnabled)
        assertTrue(player.allowConcurrentPlayback)
        assertTrue(player.usbExclusiveEnabled)
        assertEquals(PlayerUsbVolumeMode.FIXED, player.usbVolumeMode)
        assertEquals(PlayerPlayMode.SHUFFLE, player.defaultPlayMode)
        assertEquals(PlayerEndBehavior(true, PlayerTrackEndAction.LOAD_NEXT_PAUSED,
            PlayerQueueEndAction.NEXT_QUEUE, true, false), player.endBehavior)
        assertEquals(2.5f, player.defaultSpeed, 0f)
        assertEquals(PlayerEqualizerState(true, List(10) { it.toFloat() - 4f }), player.equalizer)
        assertEquals(32, player.subtitleFontSize)
        assertEquals(40, player.audioSubtitleLineSpacing)
        assertEquals(PlayerSubtitleAlignment.LEFT, player.audioSubtitleAlignment)
        assertTrue(player.floatingSubtitleEnabled)
        assertTrue(player.floatingSubtitleLocked)
        assertEquals(customizedFloatingSubtitle, player.floatingSubtitleStyle)
        assertEquals(customizedStatusBarLyrics, player.statusBarLyrics)
        assertTrue(player.notificationsShowLyrics)
        assertFalse(player.resumePlayback)
        assertFalse(player.showLoudnessWaveform)
    }

    private val customizedFloatingSubtitle = PlayerFloatingSubtitleStyle(
        fontSize = 27,
        leftMarginPercent = 73,
        weight = PlayerFloatingSubtitleWeight.BOLD,
        colorArgb = android.graphics.Color.CYAN,
        backgroundColorArgb = 0x80441199.toInt(),
        outlineColorArgb = android.graphics.Color.MAGENTA,
        scrollEnabled = true,
    )

    private val customizedStatusBarLyrics = PlayerStatusBarLyricsSettings(
        enabled = true,
        style = PlayerFloatingSubtitleStyle(
            fontSize = 18,
            leftMarginPercent = 25,
            weight = PlayerFloatingSubtitleWeight.MEDIUM,
            colorArgb = android.graphics.Color.YELLOW,
            backgroundColorArgb = android.graphics.Color.TRANSPARENT,
            outlineColorArgb = android.graphics.Color.BLUE,
            scrollEnabled = true,
        ),
        verticalPositionPercent = 54,
        widthPercent = 62,
    )

    private fun prefs(name: String): SharedPreferences = app.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun exportAudioSettings(): JSONObject = JSONObject(SettingsBackupManager.export(app)).getJSONObject("audioModeSettings")
    private fun restoreAudioSettings(settings: JSONObject) = SettingsBackupManager.restore(app,
        JSONObject().put("schemaVersion", 1).put("audioModeSettings", settings).toString())
    private fun typed(type: String, value: Any): JSONObject = JSONObject().put("type", type).put("value", value)
    private fun JSONObject.value(group: String, key: String): Any = getJSONObject(group).getJSONObject(key).get("value")
    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
}

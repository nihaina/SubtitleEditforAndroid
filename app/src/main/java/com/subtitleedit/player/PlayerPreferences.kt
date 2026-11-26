package com.subtitleedit.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.subtitleedit.util.SettingsSnapshotStore
import com.subtitleedit.util.restoreSettingsSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

internal enum class PlayerFloatingSubtitleWeight(val value: Int) {
    LIGHT(300), NORMAL(400), MEDIUM(500), BOLD(700),
}

private const val DEFAULT_FLOATING_SUBTITLE_BACKGROUND = 0xCCFFFFFF.toInt()
private const val DEFAULT_STATUS_BAR_LYRICS_FONT_SIZE = 14

internal data class PlayerFloatingSubtitleStyle(
    val fontSize: Int = PlayerPreferences.DEFAULT_SUBTITLE_FONT_SIZE,
    val leftMarginPercent: Int = 50,
    val weight: PlayerFloatingSubtitleWeight = PlayerFloatingSubtitleWeight.NORMAL,
    val colorArgb: Int = Color.WHITE,
    val backgroundColorArgb: Int = DEFAULT_FLOATING_SUBTITLE_BACKGROUND,
    val outlineColorArgb: Int = Color.TRANSPARENT,
    val scrollEnabled: Boolean = false,
)

internal data class PlayerStatusBarLyricsSettings(
    val enabled: Boolean = false,
    val style: PlayerFloatingSubtitleStyle = PlayerFloatingSubtitleStyle(
        fontSize = DEFAULT_STATUS_BAR_LYRICS_FONT_SIZE,
        backgroundColorArgb = Color.TRANSPARENT,
        outlineColorArgb = Color.BLACK,
    ),
    val verticalPositionPercent: Int = 0,
    val widthPercent: Int = 100,
) {
    companion object {
        const val MIN_WIDTH_PERCENT = 10
    }
}

internal val PlayerFloatingSubtitleStyle.backgroundOpacityPercent: Int
    get() = ((backgroundColorArgb ushr 24) * 100f / 255).roundToInt()

internal fun PlayerFloatingSubtitleStyle.withBackgroundOpacity(percent: Int): PlayerFloatingSubtitleStyle {
    val alpha = (percent.coerceIn(0, 100) * 255f / 100).roundToInt()
    return copy(backgroundColorArgb = (backgroundColorArgb and 0x00FFFFFF) or (alpha shl 24))
}

internal enum class PlayerFloatingSubtitleColor { TEXT, BACKGROUND, OUTLINE }

internal fun PlayerFloatingSubtitleStyle.colorFor(target: PlayerFloatingSubtitleColor): Int = when (target) {
    PlayerFloatingSubtitleColor.TEXT -> colorArgb
    PlayerFloatingSubtitleColor.BACKGROUND -> backgroundColorArgb
    PlayerFloatingSubtitleColor.OUTLINE -> outlineColorArgb
}

internal fun PlayerFloatingSubtitleStyle.withColor(target: PlayerFloatingSubtitleColor, argb: Int): PlayerFloatingSubtitleStyle =
    when (target) {
        PlayerFloatingSubtitleColor.TEXT -> copy(colorArgb = argb)
        PlayerFloatingSubtitleColor.BACKGROUND -> copy(backgroundColorArgb = argb)
        PlayerFloatingSubtitleColor.OUTLINE -> copy(outlineColorArgb = argb)
    }

internal fun floatingSubtitleLeftOffset(freeSpace: Int, percent: Int): Int =
    (freeSpace.coerceAtLeast(0) * (percent.coerceIn(0, 100) / 100f)).roundToInt()

internal val PlayerFloatingSubtitleStyle.multilineAlignment: PlayerSubtitleAlignment
    get() = when (leftMarginPercent) {
        0 -> PlayerSubtitleAlignment.LEFT
        100 -> PlayerSubtitleAlignment.RIGHT
        else -> PlayerSubtitleAlignment.CENTER
    }

/** Player-only preferences, kept in their own file so the player stays self-contained. */
internal enum class PlayerUsbVolumeMode { SOFTWARE, HARDWARE, FIXED }

internal enum class PlayerUsbSampleRate(val hz: Int) {
    AUTO(0),
    HZ_44100(44_100),
    HZ_48000(48_000),
    HZ_88200(88_200),
    HZ_96000(96_000),
    HZ_176400(176_400),
    HZ_192000(192_000),
    HZ_352800(352_800),
    HZ_384000(384_000),
    HZ_705600(705_600),
    HZ_768000(768_000),
}

internal enum class PlayerUsbBitDepth(val option: String) {
    AUTO("auto"), BIT_32("32"), BIT_16("16")
}

internal class PlayerPreferences(private val prefs: SharedPreferences) : SettingsSnapshotStore {

    /** Opt in explicitly; a fresh installation uses the editor for media files. */
    var playerModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_MODE, value).apply()

    var defaultPlayMode: PlayerPlayMode
        get() = runCatching { PlayerPlayMode.valueOf(prefs.getString(KEY_MODE, "") ?: "") }
            .getOrDefault(PlayerPlayMode.REPEAT_ALL).let { if (endBehavior.enabled) it.asSimpleMode() else it }
        set(value) = prefs.edit().putString(KEY_MODE, (if (endBehavior.enabled) value.asSimpleMode() else value).name).apply()

    val endBehavior: PlayerEndBehavior
        get() = PlayerEndBehavior(
            enabled = prefs.getBoolean("end_behavior_enabled", false),
            trackAction = runCatching {
                PlayerTrackEndAction.valueOf(prefs.getString("track_end_action", "").orEmpty())
            }.getOrDefault(PlayerTrackEndAction.FOLLOW_MODE),
            queueAction = runCatching {
                PlayerQueueEndAction.valueOf(prefs.getString("queue_end_action", "").orEmpty())
            }.getOrDefault(PlayerQueueEndAction.STOP),
            wrapQueues = prefs.getBoolean("queue_end_wrap", false),
            reshuffle = prefs.getBoolean("queue_end_reshuffle", true)
        )

    fun savePlaybackBehavior(mode: PlayerPlayMode, behavior: PlayerEndBehavior) {
        prefs.edit().putString(KEY_MODE, (if (behavior.enabled) mode.asSimpleMode() else mode).name)
            .putBoolean("end_behavior_enabled", behavior.enabled)
            .putString("track_end_action", behavior.trackAction.name)
            .putString("queue_end_action", behavior.queueAction.name)
            .putBoolean("queue_end_wrap", behavior.wrapQueues)
            .putBoolean("queue_end_reshuffle", behavior.reshuffle)
            .apply()
    }

    var defaultSpeed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f).takeIf { it.isFinite() }?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f
        set(value) = prefs.edit().putFloat(KEY_SPEED, value.takeIf { it.isFinite() }?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f).apply()

    var equalizer: PlayerEqualizerState
        get() = PlayerEqualizerState(
            enabled = prefs.getBoolean(KEY_EQ_ENABLED, false),
            gains = PlayerEqualizer.normalize(
                (prefs.getString(KEY_EQ_GAINS, "") ?: "")
                    .split(',').mapNotNull { runCatching { it.toFloat() }.getOrNull() }
                    .takeIf { it.size == PlayerEqualizer.BAND_COUNT }
                    ?: PlayerEqualizer.FLAT
            )
        )
        set(value) = prefs.edit()
            .putBoolean(KEY_EQ_ENABLED, value.enabled)
            .putString(KEY_EQ_GAINS, PlayerEqualizer.normalize(value.gains).joinToString(",") { it.toString() })
            .apply()

    var subtitleFontSize: Int
        get() = prefs.getInt(KEY_SUB_SIZE, DEFAULT_SUBTITLE_FONT_SIZE)
            .coerceIn(MIN_SUBTITLE_FONT_SIZE, MAX_SUBTITLE_FONT_SIZE)
        set(value) = prefs.edit().putInt(KEY_SUB_SIZE,
            value.coerceIn(MIN_SUBTITLE_FONT_SIZE, MAX_SUBTITLE_FONT_SIZE)).apply()

    var audioSubtitleLineSpacing: Int
        get() = prefs.getInt(KEY_AUDIO_SUB_SPACING, DEFAULT_AUDIO_SUBTITLE_LINE_SPACING)
            .coerceIn(MIN_AUDIO_SUBTITLE_LINE_SPACING, MAX_AUDIO_SUBTITLE_LINE_SPACING)
        set(value) = prefs.edit().putInt(KEY_AUDIO_SUB_SPACING,
            value.coerceIn(MIN_AUDIO_SUBTITLE_LINE_SPACING, MAX_AUDIO_SUBTITLE_LINE_SPACING)).apply()

    var audioSubtitleAlignment: PlayerSubtitleAlignment
        get() = runCatching { PlayerSubtitleAlignment.valueOf(prefs.getString(KEY_AUDIO_SUB_ALIGNMENT, "").orEmpty()) }
            .getOrDefault(PlayerSubtitleAlignment.CENTER)
        set(value) = prefs.edit().putString(KEY_AUDIO_SUB_ALIGNMENT, value.name).apply()

    var floatingSubtitleStyle: PlayerFloatingSubtitleStyle
        get() = PlayerFloatingSubtitleStyle(
            fontSize = prefs.getInt(KEY_FLOATING_SUB_SIZE, DEFAULT_SUBTITLE_FONT_SIZE)
                .coerceIn(MIN_SUBTITLE_FONT_SIZE, MAX_SUBTITLE_FONT_SIZE),
            leftMarginPercent = prefs.getInt(
                KEY_FLOATING_SUB_LEFT_MARGIN, legacyLeftMargin(prefs.getString(KEY_FLOATING_SUB_ALIGNMENT, null)) ?: 50,
            ).coerceIn(0, 100),
            weight = PlayerFloatingSubtitleWeight.entries.firstOrNull {
                it.name == prefs.getString(KEY_FLOATING_SUB_WEIGHT, null)
            } ?: PlayerFloatingSubtitleWeight.NORMAL,
            colorArgb = prefs.getInt(KEY_FLOATING_SUB_COLOR, Color.WHITE),
            backgroundColorArgb = prefs.getInt(KEY_FLOATING_SUB_BACKGROUND, DEFAULT_FLOATING_SUBTITLE_BACKGROUND),
            outlineColorArgb = prefs.getInt(KEY_FLOATING_SUB_OUTLINE_COLOR, Color.TRANSPARENT),
            scrollEnabled = prefs.getBoolean(KEY_FLOATING_SUB_SCROLL, false),
        )
        set(value) = prefs.edit()
            .putInt(KEY_FLOATING_SUB_SIZE, value.fontSize.coerceIn(MIN_SUBTITLE_FONT_SIZE, MAX_SUBTITLE_FONT_SIZE))
            .putInt(KEY_FLOATING_SUB_LEFT_MARGIN, value.leftMarginPercent.coerceIn(0, 100))
            .putString(KEY_FLOATING_SUB_WEIGHT, value.weight.name)
            .putInt(KEY_FLOATING_SUB_COLOR, value.colorArgb)
            .putInt(KEY_FLOATING_SUB_BACKGROUND, value.backgroundColorArgb)
            .putInt(KEY_FLOATING_SUB_OUTLINE_COLOR, value.outlineColorArgb)
            .putBoolean(KEY_FLOATING_SUB_SCROLL, value.scrollEnabled)
            .remove(KEY_FLOATING_SUB_ALIGNMENT)
            .apply()

    val floatingSubtitleStyleChanges: Flow<PlayerFloatingSubtitleStyle> = observe(
        setOf(
            KEY_FLOATING_SUB_SIZE, KEY_FLOATING_SUB_LEFT_MARGIN, KEY_FLOATING_SUB_ALIGNMENT, KEY_FLOATING_SUB_WEIGHT,
            KEY_FLOATING_SUB_COLOR, KEY_FLOATING_SUB_BACKGROUND, KEY_FLOATING_SUB_OUTLINE_COLOR, KEY_FLOATING_SUB_SCROLL,
        )
    ) { floatingSubtitleStyle }

    var floatingSubtitleEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_SUB_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_SUB_ENABLED, value).apply()

    val floatingSubtitleEnabledChanges: Flow<Boolean> = observe(setOf(KEY_FLOATING_SUB_ENABLED)) { floatingSubtitleEnabled }

    var floatingSubtitleLocked: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_SUB_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_SUB_LOCKED, value).apply()

    val floatingSubtitleLockedChanges: Flow<Boolean> = observe(setOf(KEY_FLOATING_SUB_LOCKED)) { floatingSubtitleLocked }

    // Placement belongs to this display and is kept outside settings backups.
    var floatingSubtitlePosition: PlayerFloatingSubtitlePosition?
        get() {
            val y = prefs.getFloat(KEY_FLOATING_SUB_POSITION_Y, Float.NaN)
            return y.takeIf { it in 0f..1f }?.let(::PlayerFloatingSubtitlePosition)
        }
        set(value) {
            val editor = prefs.edit().remove(KEY_FLOATING_SUB_POSITION_X)
            if (value == null || !value.y.isFinite()) {
                editor.remove(KEY_FLOATING_SUB_POSITION_Y)
            } else {
                editor.putFloat(KEY_FLOATING_SUB_POSITION_Y, value.y.coerceIn(0f, 1f))
            }
            editor.apply()
        }

    var notificationsShowLyrics: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_LYRICS, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_LYRICS, value).apply()

    val notificationLyricsChanges: Flow<Boolean> = observe(setOf(KEY_NOTIFICATION_LYRICS)) { notificationsShowLyrics }

    var statusBarLyrics: PlayerStatusBarLyricsSettings
        get() = PlayerStatusBarLyricsSettings(
            enabled = prefs.getBoolean(KEY_STATUS_LYRICS_ENABLED, false),
            style = PlayerFloatingSubtitleStyle(
                fontSize = prefs.getInt(KEY_STATUS_LYRICS_SIZE, DEFAULT_STATUS_BAR_LYRICS_FONT_SIZE)
                    .coerceIn(MIN_SUBTITLE_FONT_SIZE, MAX_SUBTITLE_FONT_SIZE),
                leftMarginPercent = prefs.getInt(KEY_STATUS_LYRICS_LEFT_MARGIN, 50).coerceIn(0, 100),
                weight = PlayerFloatingSubtitleWeight.entries.firstOrNull {
                    it.name == prefs.getString(KEY_STATUS_LYRICS_WEIGHT, null)
                } ?: PlayerFloatingSubtitleWeight.NORMAL,
                colorArgb = prefs.getInt(KEY_STATUS_LYRICS_COLOR, Color.WHITE),
                backgroundColorArgb = Color.TRANSPARENT,
                outlineColorArgb = prefs.getInt(KEY_STATUS_LYRICS_OUTLINE_COLOR, Color.BLACK),
                scrollEnabled = prefs.getBoolean(KEY_STATUS_LYRICS_SCROLL, false),
            ),
            verticalPositionPercent = prefs.getInt(KEY_STATUS_LYRICS_POSITION, 0).coerceIn(0, 100),
            widthPercent = prefs.getInt(KEY_STATUS_LYRICS_WIDTH, 100)
                .coerceIn(PlayerStatusBarLyricsSettings.MIN_WIDTH_PERCENT, 100),
        )
        set(value) = prefs.edit()
            .putBoolean(KEY_STATUS_LYRICS_ENABLED, value.enabled)
            .putInt(KEY_STATUS_LYRICS_SIZE, value.style.fontSize.coerceIn(MIN_SUBTITLE_FONT_SIZE, MAX_SUBTITLE_FONT_SIZE))
            .putInt(KEY_STATUS_LYRICS_LEFT_MARGIN, value.style.leftMarginPercent.coerceIn(0, 100))
            .putString(KEY_STATUS_LYRICS_WEIGHT, value.style.weight.name)
            .putInt(KEY_STATUS_LYRICS_COLOR, value.style.colorArgb)
            .putInt(KEY_STATUS_LYRICS_OUTLINE_COLOR, value.style.outlineColorArgb)
            .putBoolean(KEY_STATUS_LYRICS_SCROLL, value.style.scrollEnabled)
            .putInt(KEY_STATUS_LYRICS_POSITION, value.verticalPositionPercent.coerceIn(0, 100))
            .putInt(KEY_STATUS_LYRICS_WIDTH, value.widthPercent.coerceIn(PlayerStatusBarLyricsSettings.MIN_WIDTH_PERCENT, 100))
            .apply()

    val statusBarLyricsChanges: Flow<PlayerStatusBarLyricsSettings> = observe(
        setOf(
            KEY_STATUS_LYRICS_ENABLED, KEY_STATUS_LYRICS_SIZE, KEY_STATUS_LYRICS_LEFT_MARGIN, KEY_STATUS_LYRICS_WEIGHT,
            KEY_STATUS_LYRICS_COLOR, KEY_STATUS_LYRICS_OUTLINE_COLOR, KEY_STATUS_LYRICS_SCROLL, KEY_STATUS_LYRICS_POSITION,
            KEY_STATUS_LYRICS_WIDTH,
        )
    ) { statusBarLyrics }

    private fun <T> observe(keys: Set<String>, read: () -> T): Flow<T> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    var resumePlayback: Boolean
        get() = prefs.getBoolean(KEY_RESUME, true)
        set(value) = prefs.edit().putBoolean(KEY_RESUME, value).apply()

    var allowConcurrentPlayback: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_CONCURRENT_PLAYBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_CONCURRENT_PLAYBACK, value).apply()

    val allowConcurrentPlaybackChanges = observe(setOf(KEY_ALLOW_CONCURRENT_PLAYBACK)) { allowConcurrentPlayback }

    var usbExclusiveEnabled: Boolean
        get() = prefs.getBoolean(KEY_USB_EXCLUSIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_USB_EXCLUSIVE, value).apply()

    val usbExclusiveEnabledChanges = observe(setOf(KEY_USB_EXCLUSIVE)) { usbExclusiveEnabled }

    var usbVolumeMode: PlayerUsbVolumeMode
        get() = PlayerUsbVolumeMode.entries.firstOrNull { it.name == prefs.getString(KEY_USB_VOLUME_MODE, null) }
            ?: PlayerUsbVolumeMode.SOFTWARE
        set(value) = prefs.edit().putString(KEY_USB_VOLUME_MODE, value.name).apply()

    val usbVolumeModeChanges = observe(setOf(KEY_USB_VOLUME_MODE)) { usbVolumeMode }

    var usbSampleRate: PlayerUsbSampleRate
        get() {
            val hz = prefs.getInt(KEY_USB_SAMPLE_RATE, 0)
            return PlayerUsbSampleRate.entries.firstOrNull { it.hz == hz } ?: PlayerUsbSampleRate.AUTO
        }
        set(value) = prefs.edit().putInt(KEY_USB_SAMPLE_RATE, value.hz).apply()

    val usbSampleRateChanges = observe(setOf(KEY_USB_SAMPLE_RATE)) { usbSampleRate }

    var usbBitDepth: PlayerUsbBitDepth
        get() = PlayerUsbBitDepth.entries.firstOrNull { it.name == prefs.getString(KEY_USB_BIT_DEPTH, null) }
            ?: PlayerUsbBitDepth.AUTO
        set(value) = prefs.edit().putString(KEY_USB_BIT_DEPTH, value.name).apply()

    val usbBitDepthChanges = observe(setOf(KEY_USB_BIT_DEPTH)) { usbBitDepth }

    var hideBackgroundPlaybackGuide: Boolean
        get() = prefs.getBoolean(KEY_HIDE_BACKGROUND_PLAYBACK_GUIDE, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_BACKGROUND_PLAYBACK_GUIDE, value).apply()

    var showLoudnessWaveform: Boolean
        get() = prefs.getBoolean(KEY_LOUDNESS_WAVEFORM, true)
        set(value) = prefs.edit().putBoolean(KEY_LOUDNESS_WAVEFORM, value).apply()

    fun lastPosition(mediaPath: String): Long = prefs.getLong("$KEY_LAST_POS$mediaPath", 0L).coerceAtLeast(0L)

    fun saveLastPosition(mediaPath: String, positionMs: Long) {
        prefs.edit().putLong("$KEY_LAST_POS$mediaPath", positionMs.coerceAtLeast(0L)).apply()
    }

    override fun exportSettingsSnapshot(): Map<String, Any> {
        val behavior = endBehavior
        val eq = equalizer
        val floating = floatingSubtitleStyle
        val statusLyrics = statusBarLyrics
        return linkedMapOf(
            KEY_PLAYER_MODE to playerModeEnabled,
            KEY_MODE to defaultPlayMode.name,
            "end_behavior_enabled" to behavior.enabled,
            "track_end_action" to behavior.trackAction.name,
            "queue_end_action" to behavior.queueAction.name,
            "queue_end_wrap" to behavior.wrapQueues,
            "queue_end_reshuffle" to behavior.reshuffle,
            KEY_SPEED to defaultSpeed,
            KEY_EQ_ENABLED to eq.enabled,
            KEY_EQ_GAINS to eq.gains.joinToString(","),
            KEY_SUB_SIZE to subtitleFontSize,
            KEY_AUDIO_SUB_SPACING to audioSubtitleLineSpacing,
            KEY_AUDIO_SUB_ALIGNMENT to audioSubtitleAlignment.name,
            KEY_FLOATING_SUB_ENABLED to floatingSubtitleEnabled,
            KEY_FLOATING_SUB_LOCKED to floatingSubtitleLocked,
            KEY_FLOATING_SUB_SIZE to floating.fontSize,
            KEY_FLOATING_SUB_LEFT_MARGIN to floating.leftMarginPercent,
            KEY_FLOATING_SUB_WEIGHT to floating.weight.name,
            KEY_FLOATING_SUB_COLOR to floating.colorArgb,
            KEY_FLOATING_SUB_BACKGROUND to floating.backgroundColorArgb,
            KEY_FLOATING_SUB_OUTLINE_COLOR to floating.outlineColorArgb,
            KEY_FLOATING_SUB_SCROLL to floating.scrollEnabled,
            KEY_STATUS_LYRICS_ENABLED to statusLyrics.enabled,
            KEY_STATUS_LYRICS_SIZE to statusLyrics.style.fontSize,
            KEY_STATUS_LYRICS_LEFT_MARGIN to statusLyrics.style.leftMarginPercent,
            KEY_STATUS_LYRICS_WEIGHT to statusLyrics.style.weight.name,
            KEY_STATUS_LYRICS_COLOR to statusLyrics.style.colorArgb,
            KEY_STATUS_LYRICS_OUTLINE_COLOR to statusLyrics.style.outlineColorArgb,
            KEY_STATUS_LYRICS_SCROLL to statusLyrics.style.scrollEnabled,
            KEY_STATUS_LYRICS_POSITION to statusLyrics.verticalPositionPercent,
            KEY_STATUS_LYRICS_WIDTH to statusLyrics.widthPercent,
            KEY_NOTIFICATION_LYRICS to notificationsShowLyrics,
            KEY_RESUME to resumePlayback,
            KEY_ALLOW_CONCURRENT_PLAYBACK to allowConcurrentPlayback,
            KEY_USB_EXCLUSIVE to usbExclusiveEnabled,
            KEY_USB_VOLUME_MODE to usbVolumeMode.name,
            KEY_USB_SAMPLE_RATE to usbSampleRate.hz,
            KEY_USB_BIT_DEPTH to usbBitDepth.name,
            KEY_LOUDNESS_WAVEFORM to showLoudnessWaveform
        )
    }

    override fun restoreSettingsSnapshot(values: Map<String, Any>) {
        val legacyMargin = legacyLeftMargin(values[KEY_FLOATING_SUB_ALIGNMENT] as? String)
        val normalized = if (values[KEY_FLOATING_SUB_LEFT_MARGIN] !is Int && legacyMargin != null) {
            values + (KEY_FLOATING_SUB_LEFT_MARGIN to legacyMargin)
        } else values
        prefs.restoreSettingsSnapshot(normalized, exportSettingsSnapshot())
    }

    companion object {
        const val DEFAULT_SUBTITLE_FONT_SIZE = 20
        const val MIN_SUBTITLE_FONT_SIZE = 10
        const val MAX_SUBTITLE_FONT_SIZE = 60
        const val DEFAULT_AUDIO_SUBTITLE_LINE_SPACING = 24
        const val MIN_AUDIO_SUBTITLE_LINE_SPACING = 0
        const val MAX_AUDIO_SUBTITLE_LINE_SPACING = 48
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        val SPEED_STEPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

        private const val KEY_PLAYER_MODE = "player_mode_enabled"
        private const val KEY_MODE = "default_play_mode"
        private const val KEY_SPEED = "default_speed"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_GAINS = "eq_gains"
        private const val KEY_SUB_SIZE = "subtitle_font_size"
        private const val KEY_AUDIO_SUB_SPACING = "audio_subtitle_line_spacing"
        private const val KEY_AUDIO_SUB_ALIGNMENT = "audio_subtitle_alignment"
        private const val KEY_FLOATING_SUB_ENABLED = "floating_subtitle_enabled"
        private const val KEY_FLOATING_SUB_LOCKED = "floating_subtitle_locked"
        private const val KEY_FLOATING_SUB_POSITION_X = "floating_subtitle_position_x"
        private const val KEY_FLOATING_SUB_POSITION_Y = "floating_subtitle_position_y"
        private const val KEY_FLOATING_SUB_SIZE = "floating_subtitle_size"
        private const val KEY_FLOATING_SUB_ALIGNMENT = "floating_subtitle_alignment"
        private const val KEY_FLOATING_SUB_LEFT_MARGIN = "floating_subtitle_left_margin"
        private const val KEY_FLOATING_SUB_WEIGHT = "floating_subtitle_weight"
        private const val KEY_FLOATING_SUB_COLOR = "floating_subtitle_color"
        private const val KEY_FLOATING_SUB_BACKGROUND = "floating_subtitle_background_color"
        private const val KEY_FLOATING_SUB_OUTLINE_COLOR = "floating_subtitle_outline_color"
        private const val KEY_FLOATING_SUB_SCROLL = "floating_subtitle_scroll"
        private const val KEY_STATUS_LYRICS_ENABLED = "status_bar_lyrics_enabled"
        private const val KEY_STATUS_LYRICS_SIZE = "status_bar_lyrics_size"
        private const val KEY_STATUS_LYRICS_LEFT_MARGIN = "status_bar_lyrics_left_margin"
        private const val KEY_STATUS_LYRICS_WEIGHT = "status_bar_lyrics_weight"
        private const val KEY_STATUS_LYRICS_COLOR = "status_bar_lyrics_color"
        private const val KEY_STATUS_LYRICS_OUTLINE_COLOR = "status_bar_lyrics_outline_color"
        private const val KEY_STATUS_LYRICS_SCROLL = "status_bar_lyrics_scroll"
        private const val KEY_STATUS_LYRICS_POSITION = "status_bar_lyrics_position"
        private const val KEY_STATUS_LYRICS_WIDTH = "status_bar_lyrics_width"
        private const val KEY_NOTIFICATION_LYRICS = "notification_lyrics"
        private const val KEY_RESUME = "resume_playback"
        private const val KEY_ALLOW_CONCURRENT_PLAYBACK = "allow_concurrent_playback"
        private const val KEY_USB_EXCLUSIVE = "usb_exclusive_enabled"
        private const val KEY_USB_VOLUME_MODE = "usb_volume_mode"
        private const val KEY_USB_SAMPLE_RATE = "usb_sample_rate"
        private const val KEY_USB_BIT_DEPTH = "usb_bit_depth"
        private const val KEY_HIDE_BACKGROUND_PLAYBACK_GUIDE = "hide_background_playback_guide"
        private const val KEY_LOUDNESS_WAVEFORM = "show_loudness_waveform"
        private const val KEY_LAST_POS = "last_position_"

        private fun legacyLeftMargin(alignment: String?): Int? = when (alignment) {
            PlayerSubtitleAlignment.LEFT.name -> 0
            PlayerSubtitleAlignment.CENTER.name -> 50
            PlayerSubtitleAlignment.RIGHT.name -> 100
            else -> null
        }

        @Volatile
        private var instance: PlayerPreferences? = null

        fun getInstance(context: Context): PlayerPreferences =
            instance ?: synchronized(this) {
                instance ?: PlayerPreferences(
                    context.applicationContext.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}

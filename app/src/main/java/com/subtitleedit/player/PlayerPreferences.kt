package com.subtitleedit.player

import android.content.Context
import android.content.SharedPreferences

/** Player-only preferences, kept in their own file so the player stays self-contained. */
internal class PlayerPreferences(private val prefs: SharedPreferences) {

    /** Opt in explicitly; a fresh installation uses the editor for media files. */
    var playerModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_MODE, value).apply()

    var defaultPlayMode: PlayerPlayMode
        get() = runCatching { PlayerPlayMode.valueOf(prefs.getString(KEY_MODE, "") ?: "") }
            .getOrDefault(PlayerPlayMode.REPEAT_ALL)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

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
        prefs.edit().putString(KEY_MODE, mode.name)
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

    var resumePlayback: Boolean
        get() = prefs.getBoolean(KEY_RESUME, true)
        set(value) = prefs.edit().putBoolean(KEY_RESUME, value).apply()

    var showLoudnessWaveform: Boolean
        get() = prefs.getBoolean(KEY_LOUDNESS_WAVEFORM, true)
        set(value) = prefs.edit().putBoolean(KEY_LOUDNESS_WAVEFORM, value).apply()

    fun lastPosition(mediaPath: String): Long = prefs.getLong("$KEY_LAST_POS$mediaPath", 0L).coerceAtLeast(0L)

    fun saveLastPosition(mediaPath: String, positionMs: Long) {
        prefs.edit().putLong("$KEY_LAST_POS$mediaPath", positionMs.coerceAtLeast(0L)).apply()
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
        private const val KEY_RESUME = "resume_playback"
        private const val KEY_LOUDNESS_WAVEFORM = "show_loudness_waveform"
        private const val KEY_LAST_POS = "last_position_"

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

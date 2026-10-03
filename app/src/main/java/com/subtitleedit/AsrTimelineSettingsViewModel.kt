package com.subtitleedit

import android.app.Application
import android.widget.Toast
import com.subtitleedit.ui.settings.AsrTimelineSettingsState
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.TokenTimestampGenerator
import java.util.Locale
import kotlin.math.roundToInt

/** Shared timeline settings for SenseVoice / Parakeet; [modelType] is fixed per page instance. */
internal class AsrTimelineSettingsViewModel(
    application: Application,
    private val modelType: String
) : AppViewModel<AsrTimelineSettingsState, Nothing>(application, AsrTimelineSettingsState()) {
    private val settingsManager = SettingsManager.getInstance(application)

    init {
        setState { loadSettings() }
    }

    private fun loadSettings(): AsrTimelineSettingsState {
        val tokenTimestampModelAvailable = hasUsableTokenTimestampModel()
        val fixedSegmentSeconds = settingsManager.getSpeechFixedSegmentSeconds()
        val mergeGapMs = settingsManager.getSpeechTokenTimestampMergeGapMs()
        return AsrTimelineSettingsState(
            useVadTimestamp = settingsManager.isAsrVadTimestampEnabled(modelType),
            fixedSegmentSeconds = fixedSegmentSeconds,
            fixedSegmentSecondsText = String.format(Locale.US, "%d", fixedSegmentSeconds),
            fixedVadSegmentation = settingsManager.isSpeechFixedVadSegmentationEnabled(modelType),
            tokenTimestampModelAvailable = tokenTimestampModelAvailable,
            tokenTimestampEnabled = tokenTimestampModelAvailable &&
                settingsManager.isSpeechTokenTimestampEnabled(),
            tokenTimestampMerge = settingsManager.isSpeechTokenTimestampMergeEnabled(),
            smartMerge = settingsManager.isSpeechTokenTimestampSmartMergeEnabled(),
            filterLongMerge = settingsManager.isSpeechTokenTimestampLongSegmentFilterEnabled(),
            mergeMaxCharacters = settingsManager.getSpeechTokenTimestampMergeMaxCharacters(),
            mergeGapMs = mergeGapMs,
            mergeGapText = String.format(Locale.US, "%.0f", mergeGapMs.toFloat())
        )
    }

    fun setUseVadTimestamp(enabled: Boolean) {
        settingsManager.setAsrVadTimestampEnabled(modelType, enabled)
        setState {
            copy(
                useVadTimestamp = enabled,
                tokenTimestampEnabled = if (!enabled) {
                    tokenTimestampModelAvailable && settingsManager.isSpeechTokenTimestampEnabled()
                } else {
                    tokenTimestampEnabled
                }
            )
        }
    }

    fun setFixedSegmentSeconds(value: Float) {
        val seconds = value.roundToInt().coerceIn(5, 120)
        setState {
            copy(
                fixedSegmentSeconds = seconds,
                fixedSegmentSecondsText = String.format(Locale.US, "%d", seconds)
            )
        }
        settingsManager.setSpeechFixedSegmentSeconds(seconds)
    }

    fun setFixedSegmentSecondsText(text: String) {
        setState { copy(fixedSegmentSecondsText = text) }
        val value = text.toIntOrNull() ?: return
        val clamped = value.coerceIn(5, 120)
        val snapped = (((clamped + 2) / 5) * 5).coerceIn(5, 120)
        setState { copy(fixedSegmentSeconds = snapped) }
        settingsManager.setSpeechFixedSegmentSeconds(clamped)
    }

    fun setFixedVadSegmentation(enabled: Boolean) {
        setState { copy(fixedVadSegmentation = enabled) }
        settingsManager.setSpeechFixedVadSegmentationEnabled(modelType, enabled)
    }

    fun setTokenTimestampEnabled(enabled: Boolean) {
        if (enabled && !hasUsableTokenTimestampModel()) {
            toast(R.string.activity_speech_to_subtitle_settings_text_47, duration = Toast.LENGTH_LONG)
            return
        }
        setState { copy(tokenTimestampEnabled = enabled) }
        settingsManager.setSpeechTokenTimestampEnabled(enabled)
    }

    fun setTokenTimestampMerge(enabled: Boolean) {
        setState { copy(tokenTimestampMerge = enabled) }
        settingsManager.setSpeechTokenTimestampMergeEnabled(enabled)
    }

    fun setSmartMerge(enabled: Boolean) {
        setState { copy(smartMerge = enabled) }
        settingsManager.setSpeechTokenTimestampSmartMergeEnabled(enabled)
    }

    fun setFilterLongMerge(enabled: Boolean) {
        setState { copy(filterLongMerge = enabled) }
        settingsManager.setSpeechTokenTimestampLongSegmentFilterEnabled(enabled)
    }

    fun setMergeMaxCharacters(value: Float) {
        val count = value.roundToInt().coerceIn(15, 50)
        setState { copy(mergeMaxCharacters = count) }
        settingsManager.setSpeechTokenTimestampMergeMaxCharacters(count)
    }

    fun setMergeGap(value: Float) {
        val gapMs = snap(value, 50f, 0f, 5000f).roundToInt()
        setState {
            copy(
                mergeGapMs = gapMs,
                mergeGapText = String.format(Locale.US, "%.0f", gapMs.toFloat())
            )
        }
        settingsManager.setSpeechTokenTimestampMergeGapMs(gapMs)
    }

    fun setMergeGapText(text: String) {
        setState { copy(mergeGapText = text) }
        if (text.isBlank() || text.endsWith(".")) return
        val value = text.toFloatOrNull() ?: return
        setMergeGap(value)
    }

    private fun hasUsableTokenTimestampModel(): Boolean {
        return settingsManager.getAsrModelType() == modelType && TokenTimestampGenerator.isConfigured(app)
    }

    private fun snap(value: Float, step: Float, min: Float, max: Float): Float {
        val clamped = value.coerceIn(min, max)
        return (Math.round((clamped - min) / step) * step + min).coerceIn(min, max)
    }
}

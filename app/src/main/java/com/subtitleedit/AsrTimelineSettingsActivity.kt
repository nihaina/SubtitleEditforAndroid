package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.ui.settings.AsrTimelineSettingsScreen
import com.subtitleedit.ui.settings.AsrTimelineSettingsState
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.TokenTimestampGenerator
import java.util.Locale
import kotlin.math.roundToInt

abstract class AsrTimelineSettingsActivity : AppComposeActivity() {

    protected abstract val modelType: String
    protected abstract val modelName: String

    private lateinit var settingsManager: SettingsManager
    private var settingsState by mutableStateOf(AsrTimelineSettingsState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager.getInstance(this)
        settingsState = loadSettings()

        setContent {
            SubtitleEditComposeTheme {
                AsrTimelineSettingsScreen(
                    title = "$modelName 配置",
                    state = settingsState,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenVadSettings = {
                        startActivity(Intent(this, VadModelSettingsActivity::class.java))
                    },
                    onUseVadTimestampChanged = ::setUseVadTimestamp,
                    onFixedSegmentSecondsChanged = ::setFixedSegmentSeconds,
                    onFixedSegmentSecondsTextChanged = ::setFixedSegmentSecondsText,
                    onFixedVadSegmentationChanged = { enabled ->
                        settingsState = settingsState.copy(fixedVadSegmentation = enabled)
                        settingsManager.setSpeechFixedVadSegmentationEnabled(modelType, enabled)
                    },
                    onTokenTimestampEnabledChanged = ::setTokenTimestampEnabled,
                    onTokenTimestampMergeChanged = { enabled ->
                        settingsState = settingsState.copy(tokenTimestampMerge = enabled)
                        settingsManager.setSpeechTokenTimestampMergeEnabled(enabled)
                    },
                    onSmartMergeChanged = { enabled ->
                        settingsState = settingsState.copy(smartMerge = enabled)
                        settingsManager.setSpeechTokenTimestampSmartMergeEnabled(enabled)
                    },
                    onFilterLongMergeChanged = { enabled ->
                        settingsState = settingsState.copy(filterLongMerge = enabled)
                        settingsManager.setSpeechTokenTimestampLongSegmentFilterEnabled(enabled)
                    },
                    onMergeMaxCharactersChanged = { value ->
                        val count = value.roundToInt().coerceIn(15, 50)
                        settingsState = settingsState.copy(mergeMaxCharacters = count)
                        settingsManager.setSpeechTokenTimestampMergeMaxCharacters(count)
                    },
                    onMergeGapChanged = ::setMergeGap,
                    onMergeGapTextChanged = ::setMergeGapText
                )
            }
        }
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

    private fun setUseVadTimestamp(enabled: Boolean) {
        settingsManager.setAsrVadTimestampEnabled(modelType, enabled)
        settingsState = settingsState.copy(
            useVadTimestamp = enabled,
            tokenTimestampEnabled = if (!enabled) {
                settingsState.tokenTimestampModelAvailable &&
                    settingsManager.isSpeechTokenTimestampEnabled()
            } else {
                settingsState.tokenTimestampEnabled
            }
        )
    }

    private fun setFixedSegmentSeconds(value: Float) {
        val seconds = value.roundToInt().coerceIn(5, 120)
        settingsState = settingsState.copy(
            fixedSegmentSeconds = seconds,
            fixedSegmentSecondsText = String.format(Locale.US, "%d", seconds)
        )
        settingsManager.setSpeechFixedSegmentSeconds(seconds)
    }

    private fun setFixedSegmentSecondsText(text: String) {
        settingsState = settingsState.copy(fixedSegmentSecondsText = text)
        val value = text.toIntOrNull() ?: return
        val clamped = value.coerceIn(5, 120)
        val snapped = (((clamped + 2) / 5) * 5).coerceIn(5, 120)
        settingsState = settingsState.copy(fixedSegmentSeconds = snapped)
        settingsManager.setSpeechFixedSegmentSeconds(clamped)
    }

    private fun setTokenTimestampEnabled(enabled: Boolean) {
        if (enabled && !hasUsableTokenTimestampModel()) {
            OverwritingToast.makeText(
                this,
                getString(R.string.activity_speech_to_subtitle_settings_text_47),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        settingsState = settingsState.copy(tokenTimestampEnabled = enabled)
        settingsManager.setSpeechTokenTimestampEnabled(enabled)
    }

    private fun setMergeGap(value: Float) {
        val gapMs = snap(value, 50f, 0f, 5000f).roundToInt()
        settingsState = settingsState.copy(
            mergeGapMs = gapMs,
            mergeGapText = String.format(Locale.US, "%.0f", gapMs.toFloat())
        )
        settingsManager.setSpeechTokenTimestampMergeGapMs(gapMs)
    }

    private fun setMergeGapText(text: String) {
        settingsState = settingsState.copy(mergeGapText = text)
        if (text.isBlank() || text.endsWith(".")) return
        val value = text.toFloatOrNull() ?: return
        val gapMs = snap(value, 50f, 0f, 5000f).roundToInt()
        settingsState = settingsState.copy(
            mergeGapMs = gapMs,
            mergeGapText = String.format(Locale.US, "%.0f", gapMs.toFloat())
        )
        settingsManager.setSpeechTokenTimestampMergeGapMs(gapMs)
    }

    private fun hasUsableTokenTimestampModel(): Boolean {
        return settingsManager.getAsrModelType() == modelType && TokenTimestampGenerator.isConfigured(this)
    }

    private fun snap(value: Float, step: Float, min: Float, max: Float): Float {
        val clamped = value.coerceIn(min, max)
        return (Math.round((clamped - min) / step) * step + min).coerceIn(min, max)
    }
}

class SenseVoiceSettingsActivity : AsrTimelineSettingsActivity() {
    override val modelType = SettingsManager.ASR_MODEL_SENSEVOICE
    override val modelName = "SenseVoice"
}

class ParakeetSettingsActivity : AsrTimelineSettingsActivity() {
    override val modelType: String by lazy {
        SettingsManager.getInstance(this).getAsrModelType()
    }
    override val modelName = "Parakeet"
}

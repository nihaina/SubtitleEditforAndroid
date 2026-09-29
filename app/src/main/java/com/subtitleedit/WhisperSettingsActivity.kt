package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.ui.settings.WhisperSettingsScreen
import com.subtitleedit.ui.settings.WhisperSettingsState
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager
import kotlin.math.roundToInt

/** Whisper-specific controls. Shared recognition-flow settings are configured globally. */
class WhisperSettingsActivity : AppCompatActivity() {
    private lateinit var settings: SettingsManager
    private var settingsState by mutableStateOf(WhisperSettingsState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager.getInstance(this)
        settingsState = WhisperSettingsState(
            threads = settings.getSpeechWhisperThreads(),
            hotwordsEnabled = settings.isSpeechHotwordsEnabled(),
            hotwords = settings.getSpeechHotwords(),
            hotwordsScore = settings.getSpeechHotwordsScore()
        )

        setContent {
            SubtitleEditComposeTheme {
                WhisperSettingsScreen(
                    state = settingsState,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenVadSettings = {
                        startActivity(Intent(this, VadModelSettingsActivity::class.java))
                    },
                    onThreadsChanged = { value ->
                        val threads = value.roundToInt().coerceIn(1, 8)
                        settingsState = settingsState.copy(threads = threads)
                        settings.setSpeechWhisperThreads(threads)
                    },
                    onHotwordsEnabledChanged = { enabled ->
                        settingsState = settingsState.copy(hotwordsEnabled = enabled)
                        settings.setSpeechHotwordsEnabled(enabled)
                    },
                    onHotwordsChanged = { hotwords ->
                        settingsState = settingsState.copy(hotwords = hotwords)
                    },
                    onSaveHotwords = {
                        settings.setSpeechHotwords(settingsState.hotwords)
                    },
                    onHotwordsScoreChanged = { score ->
                        settingsState = settingsState.copy(hotwordsScore = score)
                        settings.setSpeechHotwordsScore(score)
                    }
                )
            }
        }
    }
}
